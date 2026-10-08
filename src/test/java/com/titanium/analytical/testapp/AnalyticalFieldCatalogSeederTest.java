package com.titanium.analytical.testapp;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.titanium.analytical.AnalyticalConsistency;
import com.titanium.analytical.config.AnalyticalEntityDefinition;
import com.titanium.analytical.config.AnalyticalEntityProperties;
import com.titanium.analytical.config.AnalyticalFieldCatalogDeclaration;
import com.titanium.analytical.config.AnalyticalFieldDefinition;
import com.titanium.analytical.config.SeedMode;
import com.titanium.analytical.query.AnalyticalTableSchemaGuard;
import com.titanium.analytical.seed.AnalyticalFieldCatalogSeeder;
import com.titanium.metadata.enums.FieldDataType;

/**
 * 字段目录**种子导入器**的判据：三条结构性拒绝 + INIT-ONLY 语义 + 23 列写入的形状。
 *
 * <p>三条拒绝不是防御性代码，各自对应一个静默缺陷（见 {@link AnalyticalFieldCatalogSeeder} 类注释）：
 * 向已版本化的目录写入会造出「没有版本头的行」而被判整个目录损坏；
 * 缺列时直接 INSERT 得到的报错<b>方向指向数据库</b>而真正要改的是「迁移没跑全」；
 * 显式开了导入却一行没写，与「本就无需导入」在日志上同形。</p>
 *
 * <p>本类直接构造导入器（而非依赖 {@code seed-mode} 的全局配置）：两种模式各需一份配置，
 * 靠 Spring 上下文切换要为每条用例起一次应用，成本与收益不成比例。</p>
 */
class AnalyticalFieldCatalogSeederTest extends AnalyticalEndpointTestSupport {

    /** 测试用目录表：列齐全。 */
    private static final String CATALOG_OK = "t_probe_field_catalog";

    /** 测试用目录表：列齐全且已版本化（多一列 {@code catalog_version}）。 */
    private static final String CATALOG_VERSIONED = "t_probe_field_catalog_versioned";

    /** 测试用目录表：缺分析能力位那四列（模拟「迁移没跑全」）。 */
    private static final String CATALOG_INCOMPLETE = "t_probe_field_catalog_incomplete";

    @Autowired
    private AnalyticalTableSchemaGuard schemaGuard;

    @Test
    @DisplayName("目录表含 catalog_version 列 ⇒ 退让并指向属主域发布流程（代写会造出无版本头的行）")
    void versionedCatalogIsDeclined() {
        createCatalogTable(CATALOG_VERSIONED, true);

        IllegalStateException ex = assertThrows(IllegalStateException.class, this::seedProbeIntoVersionedCatalog);

        assertTrue(ex.getMessage().contains("已版本化") && ex.getMessage().contains("发布流程"),
                "拒绝消息须说明成因与正确路径：" + ex.getMessage());
        assertEquals(0L, countOf(CATALOG_VERSIONED), "退让意味着一行都不写");
    }

    @Test
    @DisplayName("目录表缺导入列 ⇒ 拒绝并指向「迁移未跑全」，而不是让数据库抛 SQL 错")
    void catalogMissingColumnsIsRejected() {
        createCatalogTable(CATALOG_INCOMPLETE, false);

        IllegalStateException ex = assertThrows(IllegalStateException.class, this::seedProbeIntoIncompleteCatalog);

        assertTrue(ex.getMessage().contains("缺少导入所需的列"), ex.getMessage());
        assertTrue(ex.getMessage().contains("迁移未跑全"),
                "错误方向必须指向迁移，而不是指向数据库：" + ex.getMessage());
    }

    @Test
    @DisplayName("seed-mode=init 但没有任何实体声明目录表 ⇒ 拒绝（一行未写却报「成功」与无需导入同形）")
    void initModeWithoutAnyCatalogTargetIsRejected() {
        AnalyticalFieldCatalogSeeder seeder = seeder(properties(entity("probe", null, List.of(catalogField()))));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> seeder.onApplicationEvent(null));

        assertTrue(ex.getMessage().contains("没有任何实体声明目录表"), ex.getMessage());
    }

    @Test
    @DisplayName("声明了目录表但字段全无目录声明 ⇒ 拒绝（多半是目录声明写到了别处）")
    void catalogTableWithoutCatalogBearingFieldsIsRejected() {
        createCatalogTable(CATALOG_OK, false);
        AnalyticalFieldCatalogSeeder seeder = seeder(properties(
                entity("probe", CATALOG_OK, List.of(executionOnlyField()))));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> seeder.onApplicationEvent(null));

        assertTrue(ex.getMessage().contains("没有一个带目录声明"), ex.getMessage());
        assertEquals(0L, countOf(CATALOG_OK), "拒绝发生在一行写入之前");
    }

    @Test
    @DisplayName("首次导入：只写带目录声明的字段，field_code 为「实体码.字段码」，能力位取自同一份声明")
    void firstImportWritesCatalogBearingFieldsOnly() {
        createCatalogTable(CATALOG_OK, false);
        AnalyticalFieldCatalogSeeder seeder = seeder(properties(
                entity("probe", CATALOG_OK, List.of(catalogField(), executionOnlyField()))));

        seeder.onApplicationEvent(null);

        assertEquals(1L, countOf(CATALOG_OK),
                "只有带目录声明的字段参与导入——执行面与目录面是两个问题，派生列不该被编造出保全语义");
        Map<String, Object> row = columnMapOf(CATALOG_OK);
        assertEquals("probe.holderName", row.get("field_code"),
                "目录表的 field_code 是「实体码.字段码」两段式，与取数侧的字段引用同形");
        assertEquals("POLICY_HOLDER", row.get("object_type"));
        assertEquals("TEXT", row.get("value_type"));
        assertEquals(1, ((Number) row.get("analyzable")).intValue(), "能力位取自同一份字段声明的执行面");
        assertEquals(1, ((Number) row.get("filterable")).intValue());
        assertEquals(0, ((Number) row.get("aggregatable")).intValue());
        assertEquals("1", row.get("tenant_id"), "目录是跨租户共享的平台元数据");
        assertEquals(0L, ((Number) row.get("version")).longValue(), "新写入行的版本列从 0 起");
    }

    @Test
    @DisplayName("INIT-ONLY：表已有内容即整体跳过、不比对不更新（表此后是权威）")
    void existingRowsMakeImportSkipEntirely() {
        createCatalogTable(CATALOG_OK, false);
        jdbc.update("INSERT INTO " + CATALOG_OK + " (field_code) VALUES ('probe.operatorEdited')");

        seeder(properties(entity("probe", CATALOG_OK, List.of(catalogField())))).onApplicationEvent(null);

        assertEquals(1L, countOf(CATALOG_OK), "不得追加，也不得覆盖运营在库侧改过的行");
        assertEquals("probe.operatorEdited",
                jdbc.queryForObject("SELECT field_code FROM " + CATALOG_OK, String.class));
    }

    @Test
    @DisplayName("seed-mode=none 时连元数据都不读（对照：同一份声明在 init 下必因表不存在而失败）")
    void noneModeTouchesNoTable() {
        // 判据取「目录表指向一张不存在的表」而不是「某张表没被写」：none 分支若读了一次元数据，
        // 就会在这张不存在的表上失败——「不碰表」因此成为可证伪的断言
        AnalyticalEntityDefinition probe = entity("probe", "t_probe_catalog_absent", List.of(catalogField()));
        AnalyticalEntityProperties noneMode =
                new AnalyticalEntityProperties(SeedMode.NONE, "1", Map.of("probe", probe));
        AnalyticalEntityProperties initMode =
                new AnalyticalEntityProperties(SeedMode.INIT, "1", Map.of("probe", probe));

        assertEquals(SeedMode.NONE, new AnalyticalEntityProperties(null, null, Map.of()).seedMode(),
                "缺省即 none：不写这项配置就不碰数据库");

        // 对照组：同一份声明在 init 模式下必然读元数据并因表不存在而失败。
        // 缺了这一组，「none 下不抛异常」与「导入器整个空转」在报告里完全同形。
        assertThrows(IllegalStateException.class,
                () -> new AnalyticalFieldCatalogSeeder(initMode, jdbc, schemaGuard).onApplicationEvent(null));
        assertDoesNotThrow(
                () -> new AnalyticalFieldCatalogSeeder(noneMode, jdbc, schemaGuard).onApplicationEvent(null));
    }

    // ------------------------------------------------------------------ 装置

    private void seedProbeIntoVersionedCatalog() {
        seeder(properties(entity("probe", CATALOG_VERSIONED, List.of(catalogField())))).onApplicationEvent(null);
    }

    private void seedProbeIntoIncompleteCatalog() {
        seeder(properties(entity("probe", CATALOG_INCOMPLETE, List.of(catalogField())))).onApplicationEvent(null);
    }

    private AnalyticalFieldCatalogSeeder seeder(AnalyticalEntityProperties properties) {
        // 导入器只读声明与数据源、不读事件（形参未被使用），故直接构造并传 null 事件即可，
        // 无须为「每条用例带自己的 seed-mode」而切换 Spring 上下文
        return new AnalyticalFieldCatalogSeeder(properties, jdbc, schemaGuard);
    }

    private static AnalyticalEntityProperties properties(AnalyticalEntityDefinition entity) {
        return new AnalyticalEntityProperties(SeedMode.INIT, "1", Map.of("probe", entity));
    }

    private static AnalyticalEntityDefinition entity(String entityCode, String catalogTable,
                                                    List<AnalyticalFieldDefinition> fields) {
        return new AnalyticalEntityDefinition("v_analytical_policy", "policy_id", false, "is_deleted",
                AnalyticalConsistency.EVENTUAL, null, null, catalogTable, fields);
    }

    /** 带目录声明的字段：目录面 14 列中除可空两列外全给。 */
    private static AnalyticalFieldDefinition catalogField() {
        return new AnalyticalFieldDefinition("holderName", "holder_name", FieldDataType.STRING,
                true, false, true, true, new AnalyticalFieldCatalogDeclaration(
                        "POLICY_HOLDER", "TEXT", "policy.field.holder.name",
                        false, null, true, true, true, true, false,
                        null, "SENSITIVE", "NAME", LocalDate.parse("2026-12-31")));
    }

    /** 只有执行面、无目录声明的字段（派生列形态：取数取得到，但无人能申报它）。 */
    private static AnalyticalFieldDefinition executionOnlyField() {
        return new AnalyticalFieldDefinition("premium", "premium", FieldDataType.DECIMAL,
                true, true, false, true, null);
    }

    /**
     * 建一张探测用目录表。
     *
     * @param withVersionColumn 是否加上 {@code catalog_version}（模拟已版本化的目录）
     *                          —— 表名等于 {@link #CATALOG_INCOMPLETE} 时另按「缺能力位列」建表
     */
    private void createCatalogTable(String table, boolean withVersionColumn) {
        boolean complete = !CATALOG_INCOMPLETE.equals(table);
        List<String> columns = AnalyticalFieldCatalogSeeder.CATALOG_COLUMNS.stream()
                .filter(column -> complete || !isAnalysisCapabilityColumn(column))
                .map(column -> column + " " + columnType(column))
                .collect(Collectors.toList());
        if (withVersionColumn) {
            columns.add(AnalyticalFieldCatalogSeeder.VERSION_COLUMN + " BIGINT");
        }
        jdbc.execute("DROP TABLE IF EXISTS " + table);
        jdbc.execute("CREATE TABLE " + table + " (" + String.join(", ", columns) + ")");
    }

    private static boolean isAnalysisCapabilityColumn(String column) {
        return "analyzable".equals(column) || "aggregatable".equals(column)
                || "groupable".equals(column) || "filterable".equals(column);
    }

    private static String columnType(String column) {
        return switch (column) {
            case "collection", "readable", "proposable", "clearable", "execution_supported",
                 "requires_object_id", "analyzable", "aggregatable", "groupable", "filterable" -> "TINYINT";
            case "create_time", "update_time" -> "TIMESTAMP";
            case "deprecated_at" -> "DATE";
            case "version" -> "BIGINT";
            default -> "VARCHAR(128)";
        };
    }

    /**
     * 读第一行为「小写列名 → 值」的映射。
     *
     * <p>不直接断言 {@code queryForMap} 的结果：未加引号的标识符在 H2 折成大写、在 MySQL 落小写，
     * 键的大小写随库而变——直接断言 {@code row.get("field_code")} 会得到 null，
     * 症状是<b>每条断言都报「期望 X 实得 null」</b>，方向指向被测代码（像「列没写进去」），
     * 而真因在读取侧的键大小写。</p>
     */
    private Map<String, Object> columnMapOf(String table) {
        Map<String, Object> raw = jdbc.queryForMap("SELECT * FROM " + table);
        Map<String, Object> normalized = new LinkedHashMap<>();
        raw.forEach((key, value) -> normalized.put(key.toLowerCase(Locale.ROOT), value));
        return normalized;
    }

    private long countOf(String table) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0L : count;
    }
}
