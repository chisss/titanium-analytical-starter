package com.titanium.analytical.seed;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.jdbc.core.JdbcTemplate;

import com.titanium.analytical.config.AnalyticalEntityDefinition;
import com.titanium.analytical.config.AnalyticalEntityProperties;
import com.titanium.analytical.config.AnalyticalFieldCatalogDeclaration;
import com.titanium.analytical.config.AnalyticalFieldDefinition;
import com.titanium.analytical.config.SeedMode;
import com.titanium.analytical.query.AnalyticalSqlIdentifiers;
import com.titanium.analytical.query.AnalyticalTableSchemaGuard;

import lombok.extern.slf4j.Slf4j;

/**
 * 字段目录的 **INIT-ONLY** 种子导入（{@code seed-mode: init}）：把 YAML 的目录声明一次写进
 * {@code t_<域>_field_catalog}，**表非空即不碰**，此后表为权威。
 *
 * <h3>一个字段声明，两个消费面</h3>
 * <p>同一份 YAML 里 {@link AnalyticalFieldDefinition} 的执行面（列名/类型/能力位）供取数使用，
 * {@link AnalyticalFieldCatalogDeclaration} 的目录面供 G07 字段目录使用。本类把后者导入目录表，
 * 使「一份声明」不必写两遍——两份写法的漂移是静默的（G07 目录说可筛选、取数声明说不可筛选，
 * 两侧各自都"对"）。</p>
 *
 * <h3>🔴 三条结构性拒绝（都不是「防御性代码」，各有其对应的静默缺陷）</h3>
 * <ol>
 *   <li><b>目录表带 {@code catalog_version} 列 ⇒ 拒绝</b>：带版本列的目录是**已版本化**的
 *       （本仓先例：policy 的 g07-04 改造），其行集必须与版本头表
 *       {@code t_<域>_field_catalog_version} 的 {@code content_hash} 冻结值一致——
 *       而那个哈希由**属主域的发布流程**算出并冻结，starter 无从代算。
 *       若照常写入，症状是「表里多了一批没有版本头的行」：读取侧算出的哈希与冻结值不符，
 *       于是**整个目录被判定为已损坏**，而损坏是 starter 这次写入造成的。
 *       故此处不写、并明确指引走属主域的发布流程。</li>
 *   <li><b>目录表缺列 ⇒ 拒绝，不靠数据库报错</b>：列集是 YAML 与本类的硬契约
 *       （见 {@link #CATALOG_COLUMNS}）。缺列最常见的成因是「分析能力位那次迁移没跑」
 *       （本仓先例：{@code *_field_catalog_analysis_*} 那个 changeset），
 *       而直接 INSERT 得到的会是一句指向数据库的 SQL 错误——错误方向指向库，
 *       要改的却是「迁移没跑全」。</li>
 *   <li><b>{@code seed-mode: init} 却没有任何可导入目标 ⇒ 拒绝</b>：显式开了导入模式却一行没写，
 *       与「导入成功且本就无需写」在日志上同形。运营据此以为目录已就绪，
 *       而下游读到的是空目录。</li>
 * </ol>
 *
 * <h3>INIT-ONLY 的边界（改 YAML 不会回写已灌种子的库）</h3>
 * <p>表非空即整体跳过、<b>不比对、不更新</b>。这是刻意的：一旦允许「按 YAML 覆盖」，
 * 运营在库侧改过的目录（走 g07-04 的发布流程）会被下一次启动静默覆盖回去——
 * 而覆盖后表内容仍是一份**合法的**目录，没有任何异常能提示这次回退。
 * 需要变更目录时，走属主域的发布流程，不是改 YAML 重启。</p>
 *
 * <h3>并发</h3>
 * <p>本类不取锁：多个实例同时首次启动时会各写一遍，而目录表的主键
 * （{@code field_code}，或版本化后的 {@code (catalog_version, field_code)}）会让第二次写入
 * <b>显式失败</b>而不是静默重复——「启动报主键冲突」是可以处置的，
 * 「目录里有两份同字段的行」不是。</p>
 */
@Slf4j
public class AnalyticalFieldCatalogSeeder implements ApplicationListener<ApplicationReadyEvent> {

    /**
     * 导入时写入的列集（顺序即 INSERT 语句的列序）。
     *
     * <p>这 23 列是本模块与目录表之间的**硬契约**：前 14 列对应
     * {@link AnalyticalFieldCatalogDeclaration} 的组件，中间 4 列是同一份 YAML 的四个能力位
     * （与 G07 的 {@code FieldAnalysisCapability} 逐项同名同义），后 5 列是平台元数据表的惯例列。
     * 表端多出别的列不影响（不写入即可），缺任一列即拒绝——见类注释第 2 条。</p>
     */
    public static final List<String> CATALOG_COLUMNS = List.of(
            "field_code", "object_type", "value_type", "label_key",
            "collection", "object_identity_field",
            "readable", "proposable", "clearable", "execution_supported", "requires_object_id",
            "change_type_code", "sensitivity", "masking_policy", "deprecated_at",
            "analyzable", "aggregatable", "groupable", "filterable",
            "tenant_id", "create_time", "update_time", "version");

    /**
     * 版本列名：目录表出现它即说明该目录已版本化，本导入器退让（见类注释第 1 条）。
     */
    public static final String VERSION_COLUMN = "catalog_version";

    private final AnalyticalEntityProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final AnalyticalTableSchemaGuard schemaGuard;

    /**
     * 构造导入器。
     *
     * @param properties  取数声明（含 {@code seed-mode} 与目录声明）
     * @param jdbcTemplate 本域数据源上的执行器
     * @param schemaGuard 表结构守卫（读元数据判「表是否已版本化」「列是否齐全」）
     */
    public AnalyticalFieldCatalogSeeder(AnalyticalEntityProperties properties,
                                        JdbcTemplate jdbcTemplate,
                                        AnalyticalTableSchemaGuard schemaGuard) {
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
        this.schemaGuard = schemaGuard;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (properties.seedMode() != SeedMode.INIT) {
            log.debug("[Analytical] 字段目录导入未开启（seed-mode={}），不触碰任何表",
                    properties.seedMode());
            return;
        }
        Map<String, String> targets = resolveTargets();
        List<String> problems = new ArrayList<>();
        int written = 0;
        int skipped = 0;
        for (Map.Entry<String, String> target : targets.entrySet()) {
            try {
                int rows = seedOne(target.getKey(), target.getValue());
                if (rows > 0) {
                    written += rows;
                } else {
                    skipped++;
                }
            } catch (RuntimeException ex) {
                problems.add("实体 " + target.getKey() + "（目录表 " + target.getValue() + "）："
                        + ex.getMessage());
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "字段目录导入失败（" + problems.size() + " / " + targets.size() + " 个目标）：\n  - "
                            + String.join("\n  - ", problems)
                            + "\n（seed-mode=init 是显式开启的写入动作，失败即不让服务进入可用状态——"
                            + "半个目录被当作完整目录使用，下游会按缺失的能力位拒绝本可申报的字段）");
        }
        log.info("[Analytical] 字段目录导入完成：写入 {} 行，跳过（表已有内容，INIT-ONLY）{} 个目标",
                written, skipped);
    }

    /**
     * 解析导入目标：实体码 → 目录表名。
     *
     * <p>只挑「声明了 {@code catalog-table}」的实体。声明了目录表却没有任何字段带目录声明，
     * 同样拒绝——那说明目录声明写漏了位置（写到了别的实体上），
     * 而按「跳过」处置会让运营以为导入完成。</p>
     */
    private Map<String, String> resolveTargets() {
        Map<String, String> targets = new LinkedHashMap<>();
        for (Map.Entry<String, AnalyticalEntityDefinition> entry : properties.entities().entrySet()) {
            AnalyticalEntityDefinition entity = entry.getValue();
            if (entity.catalogTable() == null) {
                continue;
            }
            if (entity.catalogBearingFieldCount() == 0) {
                throw new IllegalStateException(
                        "实体 " + entry.getKey() + " 声明了目录表 " + entity.catalogTable()
                                + "，但其 " + entity.fields().size() + " 个字段没有一个带目录声明(catalog)——"
                                + "没有可导入的行，故拒绝：显式开启的导入一行未写却报「成功」，"
                                + "与「本就无需导入」在日志上同形");
            }
            targets.put(entry.getKey(), entity.catalogTable());
        }
        if (targets.isEmpty()) {
            throw new IllegalStateException(
                    "seed-mode=init 但没有任何实体声明目录表(catalog-table)，导入无事可做——"
                            + "显式开启的导入模式一行未写即视为配置错误，"
                            + "否则「导入成功」与「配置写错位置」在运行期完全同形");
        }
        return targets;
    }

    /**
     * 导入单个实体的目录声明。
     *
     * @return 写入的行数（表已有内容时为 0）
     */
    private int seedOne(String entityCode, String catalogTable) {
        String owner = "实体 " + entityCode + " 的目录表";
        // 🔴 目录表名来自 YAML，但拼进 SQL 前仍须过白名单——本监听器与启动期核验器同挂
        //    ApplicationReadyEvent，两者的执行顺序没有约定，故不能假设对方已经校验过
        AnalyticalSqlIdentifiers.require(catalogTable, entityCode, "catalog-table");
        Set<String> columns = schemaGuard.columnNames(catalogTable, owner);
        if (columns.contains(VERSION_COLUMN)) {
            throw new IllegalStateException(
                    "目录表 " + catalogTable + " 含版本列 " + VERSION_COLUMN + "，说明它是**已版本化**的目录："
                            + "行集必须与版本头表冻结的内容哈希一致，而那个哈希由属主域的发布流程算出——"
                            + "starter 代写会造出一批没有版本头的行，读取侧随即判定整个目录已损坏。"
                            + "故本导入器对已版本化的目录退让：请走属主域的目录发布流程"
                            + "（新增字段 = 出新版本），并把该实体的 seed-mode 保持 none");
        }
        List<String> missing = CATALOG_COLUMNS.stream().filter(column -> !columns.contains(column)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "目录表 " + catalogTable + " 缺少导入所需的列：" + missing
                            + "（本次要写 " + CATALOG_COLUMNS.size() + " 列）——"
                            + "最常见的成因是分析能力位那次迁移未跑全，"
                            + "而非 YAML 写错；直接 INSERT 只会得到一句指向数据库的报错，方向是反的");
        }

        Long existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + catalogTable, Long.class);
        if (existing != null && existing > 0) {
            log.info("[Analytical] 目录表 {} 已有 {} 行，INIT-ONLY 跳过导入（此后表为权威，"
                    + "改 YAML 不会回写）", catalogTable, existing);
            return 0;
        }
        return insertFields(entityCode, catalogTable);
    }

    private int insertFields(String entityCode, String catalogTable) {
        List<AnalyticalFieldDefinition> declared = properties.entities().get(entityCode).fields();
        LocalDateTime now = LocalDateTime.now();
        String sql = "INSERT INTO " + catalogTable
                + " (" + String.join(", ", CATALOG_COLUMNS) + ") VALUES ("
                + String.join(", ", CATALOG_COLUMNS.stream().map(column -> "?").toList()) + ")";
        int written = 0;
        for (AnalyticalFieldDefinition field : declared) {
            if (!field.hasCatalogDeclaration()) {
                continue;
            }
            jdbcTemplate.update(sql, argumentsOf(entityCode, field, now));
            written++;
        }
        log.info("[Analytical] 目录表 {} 写入 {} 行（实体 {} 共 {} 个字段，其中 {} 个带目录声明）",
                catalogTable, written, entityCode, declared.size(), written);
        return written;
    }

    private Object[] argumentsOf(String entityCode, AnalyticalFieldDefinition field, LocalDateTime now) {
        AnalyticalFieldCatalogDeclaration catalog = field.catalog();
        List<Object> arguments = new ArrayList<>(CATALOG_COLUMNS.size());
        // 目录表的 field_code 是「实体码.字段码」两段式（与取数侧的字段引用同形）
        arguments.add(entityCode + "." + field.code());
        arguments.add(catalog.objectType());
        arguments.add(catalog.valueType());
        arguments.add(catalog.labelKey());
        arguments.add(flag(catalog.collection()));
        arguments.add(catalog.objectIdentityField());
        arguments.add(flag(catalog.readable()));
        arguments.add(flag(catalog.proposable()));
        arguments.add(flag(catalog.clearable()));
        arguments.add(flag(catalog.executionSupported()));
        arguments.add(flag(catalog.requiresObjectId()));
        arguments.add(catalog.changeTypeCode());
        arguments.add(catalog.sensitivity());
        arguments.add(catalog.maskingPolicy());
        arguments.add(catalog.deprecatedAt());
        arguments.add(flag(field.analyzable()));
        arguments.add(flag(field.aggregatable()));
        arguments.add(flag(field.groupable()));
        arguments.add(flag(field.filterable()));
        arguments.add(properties.catalogTenantId());
        arguments.add(now);
        arguments.add(now);
        arguments.add(0L);
        return arguments.toArray();
    }

    /**
     * 布尔 → {@code TINYINT(1)} 的显式取值。
     *
     * <p>不直接绑 {@code Boolean}：目录表的这几位在 MySQL 上是 {@code TINYINT(1)}，
     * 而「驱动是否把它转成 1/0」取决于驱动的转换策略与版本。绑 1/0 是两种库、两代驱动
     * 都接受的最小公约数，且与列的声明显式对应。</p>
     */
    private int flag(Boolean value) {
        return Boolean.TRUE.equals(value) ? 1 : 0;
    }
}
