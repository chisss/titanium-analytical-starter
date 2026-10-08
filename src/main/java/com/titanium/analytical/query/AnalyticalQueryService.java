package com.titanium.analytical.query;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import com.titanium.analytical.AnalyticalQueryException;
import com.titanium.analytical.config.AnalyticalEntityDefinition;
import com.titanium.analytical.config.AnalyticalEntityProperties;
import com.titanium.analytical.query.TranslatedQuery.NullGuard;
import com.titanium.analytical.query.TranslatedQuery.ValuePlan;
import com.titanium.metadata.analytical.AnalyticalHandshakeResponse;
import com.titanium.metadata.analytical.AnalyticalQueryRequest;
import com.titanium.metadata.analytical.AnalyticalQueryResponse;
import com.titanium.metadata.analytical.AnalyticalValue;
import com.titanium.metadata.errorcode.FeatureCenterErrorCode;

import lombok.extern.slf4j.Slf4j;

/**
 * 取数请求的**执行与组装**：分派（Bean 逃生舱 / YAML 通用执行器）→ 执行参数化查询 →
 * 按取值计划组装响应。
 *
 * <p>本类只做「把已经编译好的查询跑出来并如实回报」，业务判定（该不该受理、该打哪张表、
 * 该带哪些条件）全在 {@link AnalyticalQueryTranslator} 与 {@link AnalyticalTableSchemaGuard}，
 * 故这里没有一处 {@code if} 判断字段能力位。</p>
 *
 * <h3>分派顺序与重复实体码</h3>
 * <p>Bean 逃生舱<b>优先于</b> YAML 声明；但两者若声明了同一个实体码，则<b>启动期即失败</b>
 * （由构造函数拒绝），不采用「后者覆盖前者」：两种执行路径的取值口径可能不同
 * （一个读专用分析视图、一个读自定义 SQL），谁生效取决于遍历顺序 ⇒
 * <b>换一次启动顺序就换一个取值口径，且两边都"看起来正常"</b>。</p>
 *
 * <h3>空集合语义（R-3）不由本类实现，而是如实转录</h3>
 * <p>{@code SUM}/{@code AVG}/{@code MIN}/{@code MAX} 在无匹配行时 SQL 就返回 {@code null}，
 * {@code COUNT} 返回 {@code 0}——本类<b>原样透传</b>，绝不把 null 折算成 0
 * （「没有明细」与「明细之和为 0」是两件事，折算后前者永久不可见）。</p>
 *
 * <h3>🔴 明细模式「零行」必须回报逐别名 null，不能回报空列表</h3>
 * <p>本契约里 {@code alias} 是请求与响应的<b>唯一配对键</b>，消费侧
 * （feature-center 的 {@code FeatureResolutionOrchestrator#indexByAlias}）对
 * 「响应里找不到某个别名」的处理是<b>抛 {@code ANALYTICAL_RESPONSE_INVALID} 失败</b>，
 * 而不是当成「没取到」。故若明细查询 0 行时回报 {@code values = []}，
 * 每一次「该主体确实没有这份数据」都会在下游变成<b>响应契约违约</b>——
 * 一次合法查询被报成对端不守约。故零行时按<b>逐个取数项</b>回报 {@code value = null}
 * （别名齐全、类型如实），让「没有数据」与「响应不合契约」在下游可区分。</p>
 *
 * <h3>为何不标注 {@code @Transactional}</h3>
 * <p>单条只读查询不需要事务边界（{@code JdbcTemplate} 自行取还连接），
 * 而标注后本模块就将「宿主域必须存在事务管理器」变成一条隐式依赖——
 * 引入 starter 的域若尚未配置事务管理器，症状是<b>首次取数抛 NoSuchBeanDefinition</b>，
 * 与「取数功能没接好」同形。KISS：不加。</p>
 */
@Slf4j
public class AnalyticalQueryService {

    private final AnalyticalEntityProperties properties;
    private final AnalyticalQueryTranslator translator;
    private final AnalyticalValueNormalizer normalizer;
    private final JdbcTemplate jdbcTemplate;

    /** 实体码 → Bean 逃生舱实现（构造期索引，查询期只查一次 Map）。 */
    private final Map<String, AnalyticalEntitySource> beanSources;

    /**
     * 构造执行器。
     *
     * @param properties   取数声明（YAML）
     * @param translator   翻译器
     * @param normalizer   值规范化器
     * @param jdbcTemplate 本域数据源上的只读执行器
     * @param beanSources  属主域自写的取数实现（可为空列表）
     * @throws IllegalArgumentException Bean 之间实体码重复，或 Bean 与 YAML 实体码冲突
     */
    public AnalyticalQueryService(AnalyticalEntityProperties properties,
                                  AnalyticalQueryTranslator translator,
                                  AnalyticalValueNormalizer normalizer,
                                  JdbcTemplate jdbcTemplate,
                                  List<AnalyticalEntitySource> beanSources) {
        this.properties = properties;
        this.translator = translator;
        this.normalizer = normalizer;
        this.jdbcTemplate = jdbcTemplate;

        Map<String, AnalyticalEntitySource> indexed = new HashMap<>();
        for (AnalyticalEntitySource source : beanSources == null ? List.<AnalyticalEntitySource>of() : beanSources) {
            String entityCode = source.entityCode();
            if (entityCode == null || entityCode.isBlank()) {
                throw new IllegalArgumentException(
                        "取数实现 " + source.getClass().getName() + " 未声明实体码(entityCode)");
            }
            if (source.consistency() == null) {
                throw new IllegalArgumentException(
                        "取数实现 " + source.getClass().getName() + "（实体 " + entityCode
                                + "）未声明一致性等级(consistency)——握手端点靠它如实回报，缺失会让 R-14 对账失去输入");
            }
            if (properties.entities().containsKey(entityCode)) {
                throw new IllegalArgumentException(
                        "实体 " + entityCode + " 同时被 YAML 声明与 "
                                + source.getClass().getName() + " 实现：两种执行路径都声称负责它，"
                                + "谁生效取决于遍历顺序（换一次启动顺序即换一个取值口径，而两边都看起来正常）");
            }
            AnalyticalEntitySource previous = indexed.putIfAbsent(entityCode, source);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "实体 " + entityCode + " 被两个取数实现同时声明："
                                + previous.getClass().getName() + " 与 " + source.getClass().getName());
            }
        }
        this.beanSources = Map.copyOf(indexed);
    }

    /**
     * 执行一次取数。
     *
     * @param request  取数请求（非空，已过契约构造期校验）
     * @param tenantId 租户标识（非空，由控制器从 {@code X-Tenant-Id} 取得并强制非空）
     * @return 取数响应（{@code consistency} 为属主域声明值，{@code asOf} 见下）
     * @throws AnalyticalQueryException 请求不被支持、实体/字段未声明、值不合法、
     *                                 表结构核验不通过、执行失败
     */
    public AnalyticalQueryResponse query(AnalyticalQueryRequest request, String tenantId) {
        String entityCode = request.entityCode();
        AnalyticalEntitySource bean = beanSources.get(entityCode);
        if (bean != null) {
            return requireEchoed(bean.query(request, tenantId), entityCode,
                    bean.consistency().name(), bean.getClass().getName());
        }

        AnalyticalEntityDefinition entity = properties.requireEntity(entityCode);
        TranslatedQuery translated = translator.translate(request, entity, entityCode, tenantId);
        List<AnalyticalValue> values = execute(translated, entityCode);
        // 🔴 asOf 对 YAML 来源实体恒为 null：读模型没有逐行的数据时点，
        // 而契约明确允许为空并说明「编造一个时点比 null 更危险」（下游会据此误判数据够新）。
        return new AnalyticalQueryResponse(entityCode, entity.consistency().name(), null, values, false, List.of());
    }

    /**
     * 能力握手：如实回报**本域实际提供**的实体、一致性与执行来源。
     *
     * <p>这是 R-14「声明与实现不符」唯一的机械发现手段——静态代码查不出某域在注册表里写了
     * {@code STRONG} 而实际读的是有投影延迟的读模型，只有让提供方自己回报才能对账。
     * 故本方法<b>如实转录声明</b>，不做任何「按表名推断一致性」的猜测：猜测会让
     * 「声明错了」与「声明对了」在握手响应上同形，恰好抹掉这个端点存在的意义。</p>
     *
     * <p>按实体码排序输出，使响应与声明顺序无关（比对两侧时少一个干扰维度）。</p>
     *
     * @return 握手响应（无任何声明时为空列表，不是失败）
     */
    public AnalyticalHandshakeResponse handshake() {
        List<AnalyticalHandshakeResponse.EntityCapability> capabilities = new ArrayList<>();
        for (Map.Entry<String, AnalyticalEntityDefinition> entry : properties.entities().entrySet()) {
            capabilities.add(new AnalyticalHandshakeResponse.EntityCapability(
                    entry.getKey(), entry.getValue().consistency().name(),
                    AnalyticalHandshakeResponse.SOURCE_YAML));
        }
        for (Map.Entry<String, AnalyticalEntitySource> entry : beanSources.entrySet()) {
            capabilities.add(new AnalyticalHandshakeResponse.EntityCapability(
                    entry.getKey(), entry.getValue().consistency().name(),
                    AnalyticalHandshakeResponse.SOURCE_BEAN));
        }
        capabilities.sort(Comparator.comparing(AnalyticalHandshakeResponse.EntityCapability::entityCode));
        return new AnalyticalHandshakeResponse(capabilities);
    }

    // ------------------------------------------------------------------ 执行

    private List<AnalyticalValue> execute(TranslatedQuery translated, String entityCode) {
        try {
            // 显式声明抽取器类型：lambda 同时匹配 ResultSetExtractor 与 RowCallbackHandler，
            // 不标注则重载解析失败（两个都"匹配"）
            return jdbcTemplate.query(translated.sql(),
                    statement -> bind(statement, translated.parameters()),
                    (ResultSetExtractor<List<AnalyticalValue>>) resultSet -> extract(resultSet, translated, entityCode));
        } catch (DataAccessException ex) {
            // 执行失败与「拒绝了请求」是两件事：前者属环境/数据，后者属请求形状，错误码不同
            log.error("取数执行失败，实体 {}，查询 {}", entityCode, translated.describe(), ex);
            throw new AnalyticalQueryException(FeatureCenterErrorCode.ANALYTICAL_QUERY_FAILED,
                    "实体 " + entityCode + " 的取数执行失败（" + translated.describe() + "）："
                            + ex.getMessage() + "——本查询已按参数化执行，故失败属环境或数据问题，"
                            + "不是筛选条件的问题");
        } catch (IllegalArgumentException | ArithmeticException ex) {
            // 出参规范化失败（如聚合结果溢出）属执行侧问题，不归因给请求
            throw new AnalyticalQueryException(FeatureCenterErrorCode.ANALYTICAL_QUERY_FAILED,
                    "实体 " + entityCode + " 的取数结果无法按声明类型规范化："
                            + ex.getMessage() + "（查询 " + translated.describe() + "）");
        }
    }

    private void bind(PreparedStatement statement, List<Object> parameters) throws SQLException {
        for (int i = 0; i < parameters.size(); i++) {
            statement.setObject(i + 1, parameters.get(i));
        }
    }

    /**
     * 逐列读取（**按列序号**，不按列名）：SQL 里没有 {@code AS} 别名，
     * 别名只存在于 Java 侧的取值计划中（见 {@link TranslatedQuery}）。
     */
    private List<AnalyticalValue> extract(ResultSet resultSet, TranslatedQuery translated, String entityCode)
            throws SQLException {
        List<Object> firstRow = null;
        int rowCount = 0;
        while (resultSet.next()) {
            rowCount++;
            if (firstRow == null) {
                firstRow = readRow(resultSet);
            }
        }

        if (translated.detailMode()) {
            if (rowCount > 1) {
                throw new AnalyticalQueryException(FeatureCenterErrorCode.ANALYTICAL_REQUEST_REJECTED,
                        "实体 " + entityCode + " 的明细取数命中多行（≥ " + rowCount
                                + " 行），而本契约的响应是扁平的 values 列表、没有行维度："
                                + "多行无法归属到唯一别名，静默取第一行即「取首个」缺陷。"
                                + "请补筛选条件把结果收敛到一行，或改为聚合取数");
            }
            return rowCount == 0
                    ? emptyDetails(translated, entityCode)
                    : detailsOf(firstRow, translated);
        }

        if (rowCount != 1) {
            // 无 GROUP BY 的聚合查询恒返回 1 行；0 行或多行说明数据库或驱动行为异常
            throw new AnalyticalQueryException(FeatureCenterErrorCode.ANALYTICAL_QUERY_FAILED,
                    "实体 " + entityCode + " 的聚合取数返回了 " + rowCount
                            + " 行，而不带分组的聚合查询恒为 1 行——本次结果不可信，故不回报");
        }
        verifyMeasureGuards(firstRow, translated, entityCode);
        return aggregatesOf(firstRow, translated);
    }

    private List<Object> readRow(ResultSet resultSet) throws SQLException {
        int columnCount = resultSet.getMetaData().getColumnCount();
        List<Object> row = new ArrayList<>(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            row.add(resultSet.getObject(i));
        }
        return row;
    }

    private List<AnalyticalValue> detailsOf(List<Object> row, TranslatedQuery translated) {
        List<ValuePlan> plans = translated.valuePlans();
        List<AnalyticalValue> values = new ArrayList<>(plans.size());
        for (int i = 0; i < plans.size(); i++) {
            values.add(toValue(plans.get(i), row.get(i)));
        }
        return values;
    }

    /**
     * 明细零行：逐个取数项回报 {@code null} 值（别名齐全），见类注释的「零行必须回报逐别名 null」。
     */
    private List<AnalyticalValue> emptyDetails(TranslatedQuery translated, String entityCode) {
        log.debug("明细取数零行，实体 {}：按取数项回报 null 值（别名齐全是下游配对的前提）", entityCode);
        List<AnalyticalValue> values = new ArrayList<>(translated.valuePlans().size());
        for (ValuePlan plan : translated.valuePlans()) {
            values.add(toValue(plan, null));
        }
        return values;
    }

    private List<AnalyticalValue> aggregatesOf(List<Object> row, TranslatedQuery translated) {
        List<ValuePlan> plans = translated.valuePlans();
        List<AnalyticalValue> values = new ArrayList<>(plans.size());
        for (int i = 0; i < plans.size(); i++) {
            values.add(toValue(plans.get(i), row.get(i)));
        }
        return values;
    }

    /**
     * 空值度量守卫：本组行数 > 某度量列的非空计数 ⇒ 该字段有明细取不到值。
     *
     * <p>SQL 的 {@code SUM}/{@code AVG}/{@code MIN}/{@code MAX} 会跳过 NULL 行，
     * 而基准实现（{@code FeatureAggregationService}）对该情形直接失败——
     * 于是同一份数据在两个档位得到<b>两个都"成功"返回、数值却不同</b>的结果，
     * 且没有任何异常或日志。故这里显式失败，把差异收口成「两处都对或两处都失败」。</p>
     */
    private void verifyMeasureGuards(List<Object> row, TranslatedQuery translated, String entityCode) {
        for (NullGuard guard : translated.nullGuards()) {
            long rows = countAt(row, guard.rowCountIndex());
            long nonNull = countAt(row, guard.nonNullCountIndex());
            if (rows > nonNull) {
                throw new AnalyticalQueryException(FeatureCenterErrorCode.ANALYTICAL_QUERY_FAILED,
                        "实体 " + entityCode + " 的度量字段 " + guard.fieldRef() + " 有 "
                                + (rows - nonNull) + " 行取不到值（共 " + rows + " 行明细，其中 "
                                + nonNull + " 行该字段非空）——SQL 会把这几行从聚合里静默剔除，"
                                + "而实时档（L1）与物化档（L3）因此会算出两个都成功返回的不同数值。"
                                + "故此处显式失败，不回报一个建立在静默剔除之上的值");
            }
        }
    }

    private long countAt(List<Object> row, int index) {
        Object value = row.get(index);
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new AnalyticalQueryException(FeatureCenterErrorCode.ANALYTICAL_QUERY_FAILED,
                "空值守卫的计数列（序号 " + index + "）不是数值：" + value
                        + "——守卫无法判定，故不回报一个未经守卫的聚合值");
    }

    private AnalyticalValue toValue(ValuePlan plan, Object jdbcValue) {
        return new AnalyticalValue(plan.alias(), plan.type().getCode(),
                normalizer.toResponse(jdbcValue, plan.type()));
    }

    /**
     * Bean 路径的响应校验：实现者回报的实体码与一致性必须与本实现的声明一致。
     *
     * <p>两者都是「自以为在回报、实际报错对象」的入口：实体码错 ⇒ 响应在下游配到别的实体上；
     * 一致性错 ⇒ R-14 的对账被一次笔误骗过（把 {@code EVENTUAL} 报成 {@code STRONG} 恰好是对账要抓的违约）。
     * 这两条是 starter 在 Bean 路径上**唯一能机械校验**的东西，其余防线（租户/删除标记/参数化）
     * 由实现者自证——见 {@link AnalyticalEntitySource} 的 javadoc。</p>
     */
    private AnalyticalQueryResponse requireEchoed(AnalyticalQueryResponse response, String entityCode,
                                                  String declaredConsistency, String implementation) {
        if (response == null) {
            throw new AnalyticalQueryException(FeatureCenterErrorCode.ANALYTICAL_QUERY_FAILED,
                    "取数实现 " + implementation + "（实体 " + entityCode + "）返回了 null 响应");
        }
        if (!entityCode.equals(response.entityCode())) {
            throw new AnalyticalQueryException(FeatureCenterErrorCode.ANALYTICAL_QUERY_FAILED,
                    "取数实现 " + implementation + " 被调用取实体 " + entityCode
                            + "，却在响应里回报实体码 " + response.entityCode()
                            + "——下游按别名取值，实体码错配会被归到一个错误的实体上");
        }
        if (!declaredConsistency.equals(response.consistency())) {
            throw new AnalyticalQueryException(FeatureCenterErrorCode.ANALYTICAL_CONSISTENCY_MISMATCH,
                    "取数实现 " + implementation + "（实体 " + entityCode + "）声明一致性为 "
                            + declaredConsistency + "，本次响应却回报 " + response.consistency()
                            + "——握手端点按声明回报，二者不一致意味着对账会看到一个不实的声明");
        }
        return response;
    }
}
