package com.titanium.analytical.query;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.titanium.analytical.AnalyticalQueryException;
import com.titanium.analytical.config.AnalyticalEntityDefinition;
import com.titanium.analytical.config.AnalyticalFieldDefinition;
import com.titanium.analytical.query.AnalyticalTableSchemaGuard.DeletedColumnStyle;
import com.titanium.analytical.query.TranslatedQuery.NullGuard;
import com.titanium.analytical.query.TranslatedQuery.ValuePlan;
import com.titanium.metadata.analytical.AnalyticalAggregation;
import com.titanium.metadata.analytical.AnalyticalFieldSelection;
import com.titanium.metadata.analytical.AnalyticalFilter;
import com.titanium.metadata.analytical.AnalyticalOperator;
import com.titanium.metadata.analytical.AnalyticalQueryRequest;
import com.titanium.metadata.enums.FieldDataType;
import com.titanium.metadata.errorcode.FeatureCenterErrorCode;

/**
 * 结构化取数请求 → **参数化** SQL 的翻译器（G08/g08-06 的实现核心）。
 *
 * <p>这是「值绝不进入 SQL 文本」这条纪律的落点，也是 {@code AnalyticalOperator} 与
 * {@code AnalyticalAggregation} 两张白名单在提供方的执行者。</p>
 *
 * <h3>四条结构性纪律（不是风格偏好，是缺陷防线）</h3>
 * <ol>
 *   <li><b>值只走占位符</b>：筛选条件的每个值都经 {@link AnalyticalValueNormalizer} 按字段声明类型
 *       规范化后进 {@code parameters}，SQL 文本里只有 {@code ?}。契约不存在原始表达式字符串，
 *       本类也<b>不保留</b>任何字符串解析分支——没有解析器就没有解析器绕过。</li>
 *   <li><b>标识符只来自声明</b>：表名/列名一律取自 YAML（经启动期白名单校验），
 *       请求侧的任何字符串（别名、字段引用的后半段）都<b>只用于查表</b>，
 *       查到什么列就用什么列——请求文本不会原样进 SQL。</li>
 *   <li><b>别名不进 SQL</b>：{@code SELECT} 列表不写 {@code AS}，结果按**列序号**读取
 *       （见 {@link TranslatedQuery}）。别名是调用方自由文本（feature-center 用的
 *       {@code featureCode} 按口径含点），给它定字符规则必然拒掉真实请求。</li>
 *   <li><b>租户与删除标记由本类强制注入、无开关</b>：{@code tenant_id} 是常量，
 *       删除标记列由 {@link AnalyticalTableSchemaGuard} 核验后决定写法；
 *       两者都不读 YAML 的「开关」，YAML 只能<b>陈述事实</b>（如该表确无删除列）。</li>
 * </ol>
 *
 * <h3>算子语义与 feature-center 基准实现同口径</h3>
 * <p>{@code COUNT} 数的是<b>明细条数</b>（{@code COUNT(*)}）而非「该字段有值的条数」——
 * 与 {@code FeatureAggregationService} 的 {@code values.size()} 一致；
 * 空集合语义（R-3）由 SQL 天然给出：无匹配行时 {@code SUM/AVG/MIN/MAX} 得 {@code null}、
 * {@code COUNT} 得 {@code 0}，本类<b>不做任何「null 折算成 0」的补偿</b>。</p>
 *
 * <p>🔴 <b>一处必须显式补齐的口径差异（勿当冗余删掉）</b>：SQL 的 {@code SUM}/{@code AVG}/
 * {@code MIN}/{@code MAX} <b>忽略 NULL 行</b>，而基准实现对该情形<b>直接失败</b>。
 * 差异只在「度量列有 NULL」时显现，症状是<b>两个档位算出两个都"成功"返回的不同数值</b>
 * （L1 走 SQL、L3 走物化快照 + 基准实现）。故聚合查询额外带两个探测列
 * （{@code COUNT(*)} 与每个度量列的 {@code COUNT(col)}），行数 > 非空计数即判失败
 * （{@link NullGuard}）。这不是防御性冗余：忽略 NULL 等于把「这一行没取到值」静默从合计里抹掉，
 * 与「该行值为 0」在数值上同形（R-3 的同一族缺陷）。</p>
 *
 * <h3>当前形状下<b>不支持</b>的请求（全部显式失败，不截断、不忽略）</h3>
 * <ul>
 *   <li>{@code groupBy} 非空：扁平 {@code values} 里没有分组维度可归属，N 个分组无法装进唯一别名；</li>
 *   <li>聚合项与明细项混用：一个结果集要么一行明细、要么一行聚合，混着来无法判定行语义；</li>
 *   <li>明细多行：同上，且截断成第一行即「静默取首个」；</li>
 *   <li>时效实体缺 {@code businessTime}：没有时点就无法做时点回溯。</li>
 * </ul>
 * <p>（反向：非时效实体携带 {@code businessTime} 有意<b>不</b>拒绝——消费侧解析链路的该值恒非空，
 * 详见 {@code FeatureCenterErrorCode#ANALYTICAL_REQUEST_REJECTED} 的说明。）</p>
 */
public class AnalyticalQueryTranslator {

    /** 明细模式的多行探测上限（取 2 行即可判定「多于 1 行」）。 */
    private static final int DETAIL_PROBE_LIMIT = 2;

    /** 时序区间的起列比较符（含起日）。 */
    private static final String RANGE_START = " <= ?";

    private final AnalyticalValueNormalizer normalizer;
    private final AnalyticalTableSchemaGuard schemaGuard;

    /**
     * 构造翻译器。
     *
     * @param normalizer  值规范化器
     * @param schemaGuard 表结构守卫（提供删除标记列的**实际**形态）
     */
    public AnalyticalQueryTranslator(AnalyticalValueNormalizer normalizer,
                                     AnalyticalTableSchemaGuard schemaGuard) {
        this.normalizer = normalizer;
        this.schemaGuard = schemaGuard;
    }

    /**
     * **启动期**校验一份实体声明的全部标识符与结构完整性。
     *
     * <p>由自动装配在应用就绪时对每个实体调用一次；失败即服务不进入可用状态。
     * 校验只针对「声明本身」，不触碰数据库——表结构对账是
     * {@link AnalyticalTableSchemaGuard#verify} 的职责（两者互补，不重复）。</p>
     *
     * @param entityCode 实体码（错误定位）
     * @param entity     实体声明
     * @throws IllegalStateException 标识符非法或时点列只给了一半
     */
    public void validate(String entityCode, AnalyticalEntityDefinition entity) {
        AnalyticalSqlIdentifiers.require(entity.table(), entityCode, "table");
        AnalyticalSqlIdentifiers.require(entity.subjectColumn(), entityCode, "subject-column");
        if (!entity.declaresNoDeletedColumn()) {
            AnalyticalSqlIdentifiers.require(entity.deletedColumn(), entityCode, "deleted-column");
        }
        if (entity.catalogTable() != null) {
            AnalyticalSqlIdentifiers.require(entity.catalogTable(), entityCode, "catalog-table");
        }
        String temporalGap = entity.describeTemporalGap();
        if (temporalGap != null) {
            throw new IllegalStateException(
                    "取数声明不合法：实体 " + entityCode + " 声明了时点回溯却缺 " + temporalGap
                            + "——起止列必须成对给出（只给一列时「时点区间」不成立，"
                            + "而按单边过滤会把区间外的数据也取进来）");
        }
        if (entity.isTemporal()) {
            AnalyticalSqlIdentifiers.require(entity.effectiveFromColumn(), entityCode, "effective-from-column");
            AnalyticalSqlIdentifiers.require(entity.effectiveToColumn(), entityCode, "effective-to-column");
        }
        for (AnalyticalFieldDefinition field : entity.fields()) {
            AnalyticalSqlIdentifiers.require(field.normalizedColumn(), entityCode,
                    "字段 " + field.code() + " 的 column");
        }
    }

    /**
     * 翻译一次取数请求。
     *
     * @param request    取数请求（非空）
     * @param entity     请求实体对应的声明
     * @param entityCode 实体码（请求与声明共用；用于字段引用前段比对与错误定位）
     * @param tenantId   租户标识（非空；由调用方从请求头取到并强制非空）
     * @return 可执行的参数化查询
     * @throws AnalyticalQueryException 请求形状不被支持、字段未声明、能力位未开放、取值不合法
     */
    public TranslatedQuery translate(AnalyticalQueryRequest request, AnalyticalEntityDefinition entity,
                                     String entityCode, String tenantId) {
        requireSupportedShape(request, entity, entityCode);
        DeletedColumnStyle deletion = schemaGuard.verify(entityCode, entity);

        List<String> conditions = new ArrayList<>();
        List<Object> parameters = new ArrayList<>();
        conditions.add(AnalyticalTableSchemaGuard.TENANT_COLUMN + " = ?");
        parameters.add(tenantId);
        if (!request.isCrossSubject()) {
            conditions.add(entity.subjectColumn() + " = ?");
            parameters.add(request.subjectId().trim());
        }
        conditions.add(deletionCondition(deletion, entity));
        if (request.hasFilter()) {
            conditions.add("(" + compileFilter(request.filterCondition(), entity, entityCode, parameters) + ")");
        }
        appendTemporalCondition(request, entity, conditions, parameters);

        List<String> selectItems = new ArrayList<>();
        List<ValuePlan> valuePlans = new ArrayList<>();
        boolean hasAggregate = false;
        boolean hasDetail = false;
        for (AnalyticalFieldSelection selection : request.selections()) {
            AnalyticalFieldDefinition field = requireDeclaredField(entity, entityCode,
                    selection.fieldCode(), "取数项");
            requireSelectionCapability(field, selection, entityCode);
            hasAggregate |= selection.isAggregating();
            hasDetail |= !selection.isAggregating();
            selectItems.add(expressionOf(field, selection.aggregation()));
            valuePlans.add(new ValuePlan(selection.alias(), resultTypeOf(field, selection.aggregation())));
        }
        if (hasAggregate && hasDetail) {
            throw reject("同一次取数不得混用聚合项与明细项：结果集要么是「一行明细」"
                    + "（取值计划按列对应每个字段），要么是「一行聚合」"
                    + "（每列是一个聚合值），混着来则每一列的行语义都不确定");
        }

        List<NullGuard> nullGuards = hasAggregate
                ? appendNullGuards(request, entity, entityCode, selectItems)
                : List.of();
        boolean detailMode = !hasAggregate;

        String sql = "SELECT " + String.join(", ", selectItems)
                + " FROM " + entity.table()
                + " WHERE " + String.join(" AND ", conditions)
                + (detailMode ? " LIMIT " + DETAIL_PROBE_LIMIT : "");
        return new TranslatedQuery(sql, parameters, valuePlans, nullGuards, detailMode);
    }

    // ------------------------------------------------------------------ 请求形状

    private void requireSupportedShape(AnalyticalQueryRequest request, AnalyticalEntityDefinition entity,
                                       String entityCode) {
        if (!request.groupBy().isEmpty()) {
            throw reject("请求携带了分组维度 " + request.groupBy() + "，但本契约的响应是扁平 values 列表："
                    + "分组结果有 N 组、而别名是请求与响应的唯一配对键，N 组值无法装进同一个别名"
                    + "（静默取其一即串值缺陷）。请改为逐主体/逐条件多次取数");
        }
        if (entity.isTemporal() && request.businessTime() == null) {
            throw reject("实体 " + entityCode + " 声明了时点回溯（effective-from/to-column），"
                    + "但请求未携带业务时点(businessTime)：无时点则「取哪一刻的有效数据」不可判定，"
                    + "而不施加区间过滤会把历史与当前数据一起算进来");
        }
        if (request.isCrossSubject() && !entity.allowCrossSubject()) {
            // 67000015：失败关闭，不得退化成跨主体取数（取错了一个还在、不报错）
            throw new AnalyticalQueryException(FeatureCenterErrorCode.ANALYTICAL_SUBJECT_REQUIRED,
                    "实体 " + entityCode + " 未声明允许跨主体聚合（allow-cross-subject），"
                            + "而请求未携带主体标识(subjectId)：放行会得到「所有主体混在一起」的聚合值，"
                            + "下游无从察觉取错了主体");
        }
    }

    // ------------------------------------------------------------------ 字段解析

    private AnalyticalFieldDefinition requireDeclaredField(AnalyticalEntityDefinition entity, String entityCode,
                                                           String fieldPath, String role) {
        if (fieldPath == null || fieldPath.isBlank()) {
            throw undeclared(entityCode, fieldPath, role + "的字段引用为空");
        }
        int dot = fieldPath.indexOf('.');
        if (dot <= 0 || dot == fieldPath.length() - 1) {
            throw undeclared(entityCode, fieldPath,
                    role + "的字段引用不是 {实体码}.{字段码} 两段式（按第一个点切分后有空段）");
        }
        String declaredEntity = fieldPath.substring(0, dot);
        if (!declaredEntity.equals(entityCode)) {
            throw undeclared(entityCode, fieldPath,
                    role + "引用的实体码是 " + declaredEntity + "，而本次请求取的是 " + entityCode
                            + "——跨实体字段引用在本契约里不可能成立（一次取数一个实体）");
        }
        String fieldCode = fieldPath.substring(dot + 1);
        AnalyticalFieldDefinition field = entity.fieldsByCode().get(fieldCode);
        if (field == null) {
            throw undeclared(entityCode, fieldPath,
                    "字段码 " + fieldCode + " 不在本实体的取数声明中（已声明："
                            + entity.fieldsByCode().keySet() + "）");
        }
        return field;
    }

    private void requireSelectionCapability(AnalyticalFieldDefinition field,
                                            AnalyticalFieldSelection selection, String entityCode) {
        if (!field.analyzable()) {
            throw undeclared(entityCode, selection.fieldCode(),
                    "该字段未开放取数能力位(analyzable=false)");
        }
        if (!selection.isAggregating()) {
            return;
        }
        if (!field.aggregatable()) {
            throw undeclared(entityCode, selection.fieldCode(),
                    "该字段未开放聚合能力位(aggregatable=false)，算子 " + selection.aggregation().getCode());
        }
        requireAggregationSupportsType(field, selection.aggregation(), entityCode);
    }

    private void requireFilterCapability(AnalyticalFieldDefinition field, String fieldPath, String entityCode) {
        if (!field.analyzable()) {
            throw undeclared(entityCode, fieldPath, "该字段未开放取数能力位(analyzable=false)");
        }
        if (!field.filterable()) {
            throw undeclared(entityCode, fieldPath, "该字段未开放筛选能力位(filterable=false)");
        }
        if (field.type() == FieldDataType.JSON) {
            throw undeclared(entityCode, fieldPath,
                    "JSON 字段不参与筛选：对 JSON 做等值/范围比较在数据库间口径不一（文本比较 vs 结构化比较）");
        }
    }

    private AnalyticalQueryException undeclared(String entityCode, String fieldPath, String reason) {
        return new AnalyticalQueryException(FeatureCenterErrorCode.ANALYTICAL_FIELD_NOT_DECLARED,
                "实体 " + entityCode + " 的字段引用 " + fieldPath + " 不可用：" + reason
                        + "——逐项显式失败，不静默跳过该字段或该条件"
                        + "（跳过会让「筛掉了」与「没筛」在结果上同形）");
    }

    // ------------------------------------------------------------------ 表达式

    /**
     * 取数项 → SELECT 列表项（{@code 列名} 或 {@code 聚合函数(列名)}）。
     *
     * <p>穷尽 switch、<b>无 default</b>：新增算子漏配映射即编译失败，
     * 不会静默落进某个兜底分支。</p>
     */
    private String expressionOf(AnalyticalFieldDefinition field, AnalyticalAggregation aggregation) {
        String column = field.normalizedColumn();
        return switch (aggregation) {
            case NONE -> column;
            // COUNT(*) 而非 COUNT(column)：数的是明细条数（与基准实现的 values.size() 同口径），
            // 且这是它能对空集合返回 0 的原因
            case COUNT -> "COUNT(*)";
            case SUM -> "SUM(" + column + ")";
            case AVG -> "AVG(" + column + ")";
            case MIN -> "MIN(" + column + ")";
            case MAX -> "MAX(" + column + ")";
        };
    }

    /**
     * 取数项 → 结果列类型（出参规范化的依据，也是响应里如实回报的 {@code dataType}）。
     *
     * <p>聚合会改变类型：{@code COUNT} 恒为整数；{@code SUM}/{@code AVG} 用 {@code DECIMAL}
     * （整数列求和可能超出 int 范围，而 MySQL 的 {@code SUM(int)} 本身就返回 DECIMAL）；
     * {@code MIN}/{@code MAX} 返回列自身的类型。{@code NONE} 即字段声明类型。</p>
     */
    private FieldDataType resultTypeOf(AnalyticalFieldDefinition field, AnalyticalAggregation aggregation) {
        return switch (aggregation) {
            case NONE, MIN, MAX -> field.type();
            case COUNT -> FieldDataType.INTEGER;
            case SUM, AVG -> FieldDataType.DECIMAL;
        };
    }

    private void requireAggregationSupportsType(AnalyticalFieldDefinition field,
                                                AnalyticalAggregation aggregation, String entityCode) {
        FieldDataType type = field.type();
        boolean numeric = type == FieldDataType.INTEGER || type == FieldDataType.DECIMAL;
        if (!numeric && (aggregation == AnalyticalAggregation.SUM || aggregation == AnalyticalAggregation.AVG
                || aggregation == AnalyticalAggregation.MIN || aggregation == AnalyticalAggregation.MAX)) {
            throw undeclared(entityCode, field.code(),
                    "算子 " + aggregation.getCode() + " 要求度量列是数值型（INTEGER/DECIMAL），"
                            + "而该字段声明为 " + type.getCode()
                            + "——非数值列求和/求均值在库间口径不一，且基准实现同口径拒绝"
                            + "（对 TEXT 求和正是 G07 能力位校验拒绝的情形）");
        }
    }

    // ------------------------------------------------------------------ 条件编译

    /**
     * 编译条件树（穷尽 switch）。参数按**出现顺序**追加进 {@code parameters}，
     * 故与 SQL 里占位符的位置严格一一对应。
     */
    private String compileFilter(AnalyticalFilter filter, AnalyticalEntityDefinition entity,
                                 String entityCode, List<Object> parameters) {
        return switch (filter.kind()) {
            case COMPARISON -> compileComparison(filter, entity, entityCode, parameters);
            case AND -> compileJunction(filter, " AND ", entity, entityCode, parameters);
            case OR -> compileJunction(filter, " OR ", entity, entityCode, parameters);
        };
    }

    private String compileJunction(AnalyticalFilter filter, String separator,
                                   AnalyticalEntityDefinition entity, String entityCode,
                                   List<Object> parameters) {
        List<String> parts = new ArrayList<>(filter.operands().size());
        for (AnalyticalFilter operand : filter.operands()) {
            parts.add("(" + compileFilter(operand, entity, entityCode, parameters) + ")");
        }
        return String.join(separator, parts);
    }

    private String compileComparison(AnalyticalFilter filter, AnalyticalEntityDefinition entity,
                                     String entityCode, List<Object> parameters) {
        String fieldPath = filter.fieldPath();
        AnalyticalFieldDefinition field = requireDeclaredField(entity, entityCode, fieldPath, "筛选条件");
        requireFilterCapability(field, fieldPath, entityCode);

        String column = field.normalizedColumn();
        AnalyticalOperator operator = filter.operator();
        List<Object> values = filter.values();

        // 穷尽 switch 表达式、无 default：新增算子漏配 SQL 即编译失败。
        // 🔴 必须是 switch **表达式**：switch 语句即使穷尽枚举、无 default，
        // javac 也不承认它「一定返回」（明确返回分析不覆盖语句形式的穷尽性），
        // 会报「缺少返回语句」——而补 default 就把「漏配算子」从编译期错误降级成运行期错误。
        return switch (operator) {
            case IS_NULL -> column + " IS NULL";
            case IS_NOT_NULL -> column + " IS NOT NULL";
            case LIKE, NOT_LIKE -> {
                requireTextual(field, fieldPath, entityCode, operator);
                parameters.add(normalizer.toParameter(values.get(0), field.type(), fieldPath));
                yield column + (operator == AnalyticalOperator.LIKE ? " LIKE ?" : " NOT LIKE ?");
            }
            case IN, NOT_IN -> {
                StringBuilder placeholders = new StringBuilder();
                for (Object value : values) {
                    if (!placeholders.isEmpty()) {
                        placeholders.append(", ");
                    }
                    placeholders.append('?');
                    parameters.add(normalizer.toParameter(value, field.type(), fieldPath));
                }
                yield column
                        + (operator == AnalyticalOperator.IN ? " IN (" : " NOT IN (")
                        + placeholders + ")";
            }
            case EQ, NE, GT, GE, LT, LE -> {
                parameters.add(normalizer.toParameter(values.get(0), field.type(), fieldPath));
                yield column + " " + comparisonSymbol(operator) + " ?";
            }
        };
    }

    private String comparisonSymbol(AnalyticalOperator operator) {
        return switch (operator) {
            case EQ -> "=";
            case NE -> "<>";
            case GT -> ">";
            case GE -> ">=";
            case LT -> "<";
            case LE -> "<=";
            case IN, NOT_IN, LIKE, NOT_LIKE, IS_NULL, IS_NOT_NULL -> throw new IllegalStateException(
                    "算子 " + operator.getCode() + " 不是标量比较（调用方已分流）");
        };
    }

    private void requireTextual(AnalyticalFieldDefinition field, String fieldPath, String entityCode,
                                AnalyticalOperator operator) {
        if (field.type() != FieldDataType.STRING && field.type() != FieldDataType.ENUM) {
            throw undeclared(entityCode, fieldPath,
                    "算子 " + operator.getCode() + " 只适用于文本类字段（STRING/ENUM），"
                            + "而该字段声明为 " + field.type().getCode());
        }
    }

    // ------------------------------------------------------------------ 强制注入的两道过滤

    /**
     * 删除标记过滤式（由核验后的实际列形态决定写法）。
     *
     * <p>{@code none} 分支返回的是一句恒真条件而非空串：恒真条件让 {@code WHERE} 列表的
     * 拼接形状与参数位置保持稳定（不因为某实体没有这一列而改变 SQL 结构），
     * 同时它是<b>被核验证实过的</b>事实（该表确无删除标记列），不是「跳过过滤」。</p>
     */
    private String deletionCondition(DeletedColumnStyle style, AnalyticalEntityDefinition entity) {
        return switch (style) {
            case NONE_VERIFIED -> "1 = 1";
            case NUMERIC_COLUMN -> entity.deletedColumn() + " = 0";
            case BOOLEAN_COLUMN -> entity.deletedColumn() + " = FALSE";
        };
    }

    /**
     * 时点回溯过滤：业务时点落在半开区间 {@code [生效起, 生效止)} 内。
     *
     * <p>止列为 {@code NULL} 视作开放区间（不设终止日）——这不是"宽松"，是唯一不会把
     * 仍在有效的数据静默判为过期的写法。半开区间（起含、止不含）与目录版本表的区间口径一致
     * （本仓已有的先例），故相邻两段首尾相接而无重叠。</p>
     */
    private void appendTemporalCondition(AnalyticalQueryRequest request, AnalyticalEntityDefinition entity,
                                         List<String> conditions, List<Object> parameters) {
        if (!entity.isTemporal()) {
            return;
        }
        LocalDateTime businessTime = request.businessTime();
        String from = entity.effectiveFromColumn();
        String to = entity.effectiveToColumn();
        conditions.add(from + RANGE_START);
        parameters.add(businessTime);
        conditions.add("(" + to + " IS NULL OR " + to + " > ?)");
        parameters.add(businessTime);
    }

    // ------------------------------------------------------------------ 空值度量守卫

    /**
     * 为每个聚合度量列追加两个探测列：本组行数 {@code COUNT(*)} 与度量列非空计数 {@code COUNT(col)}。
     *
     * <p>两者不等即说明「有明细该字段取不到值」，此时 SQL 会静默把那些行从合计里剔除，
     * 而基准实现会失败——同一份数据在两个档位得出两个都"成功"返回的不同数值。
     * 故这里显式失败（{@code ANALYTICAL_QUERY_FAILED}），把差异收口到「要么两处都对，要么两处都失败」。</p>
     */
    private List<NullGuard> appendNullGuards(AnalyticalQueryRequest request, AnalyticalEntityDefinition entity,
                                             String entityCode, List<String> selectItems) {
        int rowCountIndex = selectItems.size();
        selectItems.add("COUNT(*)");
        List<NullGuard> guards = new ArrayList<>();
        for (AnalyticalFieldSelection selection : request.selections()) {
            if (!selection.isAggregating() || selection.aggregation() == AnalyticalAggregation.COUNT) {
                continue;
            }
            AnalyticalFieldDefinition field = requireDeclaredField(entity, entityCode,
                    selection.fieldCode(), "取数项");
            int nonNullIndex = selectItems.size();
            selectItems.add("COUNT(" + field.normalizedColumn() + ")");
            guards.add(new NullGuard(selection.fieldCode(), nonNullIndex, rowCountIndex));
        }
        return List.copyOf(guards);
    }

    private AnalyticalQueryException reject(String reason) {
        return new AnalyticalQueryException(FeatureCenterErrorCode.ANALYTICAL_REQUEST_REJECTED, reason);
    }
}
