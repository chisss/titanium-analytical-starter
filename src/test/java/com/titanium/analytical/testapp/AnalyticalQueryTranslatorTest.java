package com.titanium.analytical.testapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.titanium.analytical.AnalyticalConsistency;
import com.titanium.analytical.AnalyticalQueryException;
import com.titanium.analytical.config.AnalyticalEntityDefinition;
import com.titanium.analytical.config.AnalyticalEntityProperties;
import com.titanium.analytical.config.AnalyticalFieldDefinition;
import com.titanium.analytical.query.AnalyticalQueryTranslator;
import com.titanium.analytical.query.TranslatedQuery;
import com.titanium.metadata.analytical.AnalyticalAggregation;
import com.titanium.metadata.analytical.AnalyticalFieldSelection;
import com.titanium.metadata.analytical.AnalyticalFilter;
import com.titanium.metadata.analytical.AnalyticalOperator;
import com.titanium.metadata.analytical.AnalyticalQueryRequest;
import com.titanium.metadata.enums.FieldDataType;

/**
 * 翻译器的**单元级**判据：SQL 文本的形状与参数绑定，端到端测试断言不到的细节。
 *
 * <p>端到端测试用「注入串被当字面量」从<b>结果侧</b>反证参数化生效；本类从<b>产物侧</b>直接看
 * 翻译结果：SQL 文本里有没有请求侧的值、占位符与参数是否一一对应、别名有没有溜进 SQL。
 * 两条路径独立——只有一条在跑时，「翻译器把值拼进了文本」可能在端到端测试里恰好
 * 因为「拼进去的值不改变结果集」而看不出来（如把值拼进一个恒真的条件里）。</p>
 */
class AnalyticalQueryTranslatorTest extends AnalyticalEndpointTestSupport {

    @Autowired
    private AnalyticalQueryTranslator translator;

    @Autowired
    private AnalyticalEntityProperties properties;

    @Test
    @DisplayName("SQL 文本里没有任何请求侧的值；占位符个数与参数个数严格相等")
    void sqlTextCarriesNoRequestValuesAndPlaceholderCountMatches() {
        String probe = "PROBE'一; DROP-- 注入探针";
        AnalyticalQueryRequest request = request("policy", "P-1",
                List.of(selection("policy.holderName", null, "holder")),
                filter(AnalyticalOperator.EQ, "policy.holderName", List.of(probe)));

        TranslatedQuery translated = translate(request);

        assertFalse(translated.sql().contains(probe),
                "请求侧的值绝不得出现在 SQL 文本里：" + translated.sql());
        assertFalse(translated.sql().contains("DROP"), "注入串的任何片段都不得进 SQL 文本");
        assertTrue(translated.parameters().contains(probe),
                "该值须作为参数出现，而不是被丢弃：本用例的对照面是「值去哪了」");
        assertEquals(countPlaceholders(translated.sql()), translated.parameters().size(),
                "占位符与参数必须一一对应，否则绑定错位而 SQL 本身合法："
                        + translated.sql() + " / " + translated.parameters());
    }

    @Test
    @DisplayName("租户条件恒在 WHERE 首位且无开关（YAML 无任何字段能关掉它）")
    void tenantConditionIsAlwaysFirstAndUnconditional() {
        TranslatedQuery translated = translate(request("policy", "P-1",
                List.of(selection("policy.holderName", null, "holder")), null));

        assertTrue(translated.sql().contains("WHERE tenant_id = ?"),
                "租户过滤由 starter 强制注入，YAML 只能陈述事实、不能关闭：" + translated.sql());
        assertEquals(TENANT, translated.parameters().get(0), "第一个绑定值必须是租户");
    }

    @Test
    @DisplayName("别名绝不进 SQL（含点的别名照样可用）：无 AS 子句，别名只在 Java 侧按列序号携带")
    void aliasesNeverEnterSqlText() {
        String dottedAlias = "policy.premium.total";
        AnalyticalQueryRequest request = request("policy", "P-1",
                List.of(selection("policy.premium", "SUM", dottedAlias)), null);

        TranslatedQuery translated = translate(request);

        assertFalse(translated.sql().contains(dottedAlias),
                "给出别名定字符规则必然会拒掉真实请求（feature-center 的别名即 featureCode，含点），"
                        + "故别名在结构上不进 SQL：" + translated.sql());
        assertFalse(translated.sql().contains(" AS "), "不应存在 AS 子句：" + translated.sql());
        assertEquals(dottedAlias, translated.valuePlans().get(0).alias());
    }

    @Test
    @DisplayName("聚合查询的探测列不污染取值计划（多出的列会让下游错配别名）")
    void probeColumnsDoNotEnterValuePlans() {
        AnalyticalQueryRequest request = request("policy", "P-1", List.of(
                selection("policy.premium", "SUM", "total"),
                selection("policy.sumInsured", "MAX", "peak")), null);

        TranslatedQuery translated = translate(request);

        assertEquals(List.of("total", "peak"),
                translated.valuePlans().stream().map(TranslatedQuery.ValuePlan::alias).toList(),
                "取值计划只对应请求的取数项，探测列不得混入");
        assertEquals(2, translated.nullGuards().size(), "SUM 与 MAX 各需要一个度量列守卫");
        for (TranslatedQuery.NullGuard guard : translated.nullGuards()) {
            assertTrue(guard.rowCountIndex() >= 2,
                    "行数探测列必须排在全部取值列之后：" + guard);
            assertTrue(guard.nonNullCountIndex() > guard.rowCountIndex(),
                    "非空计数列紧跟行数列之后：" + guard);
        }
        // 探测列确实出现在 SQL 里（否则守卫无数据可判，恒真）
        assertTrue(translated.sql().contains("COUNT(*)"), translated.sql());
        assertTrue(translated.sql().contains("COUNT(premium)"), translated.sql());
        assertTrue(translated.sql().contains("COUNT(sum_insured)"), translated.sql());
    }

    @Test
    @DisplayName("COUNT 数的是明细条数（COUNT(*)），与基准实现的 values.size() 同口径")
    void countCountsRowsNotNonNullValues() {
        AnalyticalQueryRequest request = request("policy", "P-1",
                List.of(selection("policy.premium", "COUNT", "cnt")), null);

        TranslatedQuery translated = translate(request);

        assertTrue(translated.sql().contains("COUNT(*)"), translated.sql());
        assertFalse(translated.sql().contains("COUNT(premium)"),
                "COUNT 数明细条数而非「该字段有值的条数」，故不应出现 COUNT(度量列)：" + translated.sql());
        assertEquals(FieldDataType.INTEGER, translated.valuePlans().get(0).type());
        assertTrue(translated.nullGuards().isEmpty(), "COUNT 没有「NULL 被静默剔除」的问题，故不配守卫");
    }

    @Test
    @DisplayName("聚合结果的类型口径：SUM/AVG → DECIMAL，MIN/MAX → 字段类型")
    void aggregateResultTypesFollowMeasureSemantics() {
        assertEquals(FieldDataType.DECIMAL, translate(request("policy", "P-1",
                List.of(selection("policy.premium", "SUM", "x")), null)).valuePlans().get(0).type());
        assertEquals(FieldDataType.DECIMAL, translate(request("policy", "P-1",
                List.of(selection("policy.premium", "AVG", "x")), null)).valuePlans().get(0).type());
        assertEquals(FieldDataType.INTEGER, translate(request("policy", "P-1",
                List.of(selection("policy.sumInsured", "MAX", "x")), null)).valuePlans().get(0).type());
    }

    @Test
    @DisplayName("明细模式追加探测上限（把「多行」变成可观测而不是静默取第一行）")
    void detailQueryProbesAtMostTwoRows() {
        TranslatedQuery translated = translate(request("policy", "P-1",
                List.of(selection("policy.holderName", null, "holder")), null));

        assertTrue(translated.detailMode());
        assertTrue(translated.sql().endsWith("LIMIT 2"), translated.sql());
        assertTrue(translated.nullGuards().isEmpty(), "明细模式没有度量列，故无守卫");
    }

    @Test
    @DisplayName("时效实体：起止两列都进条件，止列 NULL 视为开放区间")
    void temporalConditionCoversOpenEndedRange() {
        AnalyticalQueryRequest request = new AnalyticalQueryRequest("req", "endorsement", "P-1",
                List.of(selection("endorsement.fee", "SUM", "total")), null, List.of(),
                LocalDateTime.parse("2026-03-01T00:00:00"), 1);

        TranslatedQuery translated = translate(request);

        assertTrue(translated.sql().contains("effective_from <= ?"), translated.sql());
        assertTrue(translated.sql().contains("effective_to IS NULL OR effective_to > ?"),
                "止列为 NULL 必须视作开放区间——按单边过滤会把仍在有效的数据静默判为过期："
                        + translated.sql());
        assertEquals(2, translated.parameters().stream()
                .filter(LocalDateTime.class::isInstance).count(), "起止各一个绑定值");
    }

    // ------------------------------------------------------------------ 声明校验（手工构造，不依赖 YAML）

    @Test
    @DisplayName("对非数值列求和 ⇒ 67000020（与基准实现同口径拒绝，而不是让数据库自己报错）")
    void summingNonNumericColumnIsRejected() {
        // 手工把文本列声明成可聚合：YAML 里没有这种字段，而这条守卫必须独立于配置内容被测到
        AnalyticalEntityDefinition entity = new AnalyticalEntityDefinition(
                "v_analytical_policy", "policy_id", false, "is_deleted",
                AnalyticalConsistency.EVENTUAL, null, null, null,
                List.of(new AnalyticalFieldDefinition("holderName", "holder_name", FieldDataType.STRING,
                        true, true, false, true, null)));
        AnalyticalQueryRequest request = request("policy", "P-1",
                List.of(selection("policy.holderName", "SUM", "total")), null);

        AnalyticalQueryException ex = assertThrows(AnalyticalQueryException.class,
                () -> translator.translate(request, entity, "policy", TENANT));

        assertEquals("67000020", ex.errorCode().getCode());
        assertTrue(ex.getMessage().contains("数值型"),
                "须说明度量列要求数值型，而不是让数据库抛类型错：" + ex.getMessage());
    }

    @Test
    @DisplayName("非法标识符在启动期校验时即被拒（表名/列名是全链路唯一进入 SQL 文本的外部输入）")
    void illegalIdentifiersAreRejectedAtStartupValidation() {
        AnalyticalEntityDefinition entity = new AnalyticalEntityDefinition(
                "v_analytical_policy; DROP TABLE x", "policy_id", false, "is_deleted",
                AnalyticalConsistency.EVENTUAL, null, null, null,
                List.of(new AnalyticalFieldDefinition("policyId", "policy_id", FieldDataType.STRING,
                        true, false, false, true, null)));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> translator.validate("policy", entity));

        assertTrue(ex.getMessage().contains("不是合法 SQL 标识符"), ex.getMessage());
    }

    @Test
    @DisplayName("时点列只给一半 ⇒ 启动期拒绝（按单边过滤会把区间外的数据也取进来）")
    void halfDeclaredTemporalColumnsAreRejectedAtStartupValidation() {
        AnalyticalEntityDefinition entity = new AnalyticalEntityDefinition(
                "v_analytical_policy", "policy_id", false, "is_deleted",
                AnalyticalConsistency.EVENTUAL, "issue_date", null, null,
                List.of(new AnalyticalFieldDefinition("policyId", "policy_id", FieldDataType.STRING,
                        true, false, false, true, null)));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> translator.validate("policy", entity));

        assertTrue(ex.getMessage().contains("effective-to-column"), ex.getMessage());
    }

    @Test
    @DisplayName("实体声明缺必填项、字段码重复 ⇒ 绑定期即拒绝（不做「后者覆盖前者」）")
    void malformedEntityDeclarationsAreRejectedAtBindingTime() {
        assertThrows(IllegalArgumentException.class, () -> new AnalyticalEntityDefinition(
                "t", null, false, null,
                AnalyticalConsistency.EVENTUAL, null, null, null,
                List.of(new AnalyticalFieldDefinition("a", "a", FieldDataType.STRING, true, false, false, true, null))));

        AnalyticalFieldDefinition duplicated = new AnalyticalFieldDefinition(
                "a", "a", FieldDataType.STRING, true, false, false, true, null);
        assertThrows(IllegalArgumentException.class, () -> new AnalyticalEntityDefinition(
                "t", "s", false, null,
                AnalyticalConsistency.EVENTUAL, null, null, null,
                List.of(duplicated, duplicated)));
    }

    @Test
    @DisplayName("实体码含点 ⇒ 拒绝（字段引用按第一个点切分，含点会把字段码切错）")
    void entityCodeWithDotIsRejected() {
        AnalyticalEntityDefinition entity = new AnalyticalEntityDefinition(
                "v_analytical_policy", "policy_id", false, null,
                AnalyticalConsistency.EVENTUAL, null, null, null,
                List.of(new AnalyticalFieldDefinition("a", "policy_id", FieldDataType.STRING,
                        true, false, false, true, null)));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new AnalyticalEntityProperties(null, null, Map.of("po.licy", entity)));

        assertTrue(ex.getMessage().contains("不得含点"), ex.getMessage());
    }

    @Test
    @DisplayName("YAML 声明的实体清单一如配置（保序、含 deleted-column: none 的实体）")
    void declaredEntitiesMatchConfiguration() {
        assertEquals(List.of("policy", "endorsement", "free"), properties.entityCodes());

        AnalyticalEntityDefinition free = properties.entities().get("free");
        assertTrue(free.declaresNoDeletedColumn());
        assertTrue(free.allowCrossSubject());
        assertEquals(AnalyticalConsistency.STRONG, free.consistency());
        assertEquals("v_analytical_free", free.table());
    }

    // ------------------------------------------------------------------ 装置

    private TranslatedQuery translate(AnalyticalQueryRequest request) {
        AnalyticalEntityDefinition entity = properties.requireEntity(request.entityCode());
        return translator.translate(request, entity, request.entityCode(), TENANT);
    }

    private static int countPlaceholders(String sql) {
        return (int) sql.chars().filter(character -> character == '?').count();
    }

    private static AnalyticalQueryRequest request(String entityCode, String subjectId,
                                                  List<AnalyticalFieldSelection> selections,
                                                  AnalyticalFilter filter) {
        return new AnalyticalQueryRequest("req-unit", entityCode, subjectId, selections, filter,
                List.of(), null, 1);
    }

    private static AnalyticalFieldSelection selection(
            String fieldCode, String aggregation, String alias) {
        return new AnalyticalFieldSelection(fieldCode,
                aggregation == null ? null : AnalyticalAggregation.valueOf(aggregation),
                alias);
    }

    private static AnalyticalFilter filter(AnalyticalOperator operator, String fieldPath, List<Object> values) {
        return new AnalyticalFilter(AnalyticalFilter.AnalyticalFilterKind.COMPARISON, fieldPath, operator, values, null);
    }
}
