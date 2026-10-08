package com.titanium.analytical.testapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 取数端点与通用执行器的端到端验收（真实 HTTP + 真实库）。
 *
 * <p>覆盖 G08/g08-06 的四组要求：</p>
 * <ol>
 *   <li><b>端点可用</b>（R-13）：真实容器里 {@code /query} 与 {@code /handshake} 可达 ——
 *       证明自动装配生效（见 {@link AnalyticalTestApplication}）；</li>
 *   <li><b>三条强制防线</b>（R-2）：租户与删除标记由 starter 强制注入、{@code none} 声明须经核验、
 *       专用分析视图可用；</li>
 *   <li><b>契约语义</b>：空集合（R-3）、明细零行逐别名 null、时序半开区间、出参线格式；</li>
 *   <li><b>与基准实现同口径</b>：NULL 度量显式失败（而非 SQL 的静默忽略）。</li>
 * </ol>
 */
class AnalyticalQueryEndpointIntegrationTest extends AnalyticalEndpointTestSupport {

    @BeforeEach
    void resetData() {
        clearTables();
    }

    // ------------------------------------------------------------------ 装配与握手

    @Test
    @DisplayName("握手端点如实回报声明的实体、一致性与执行来源（不要求租户头）")
    void handshakeReportsDeclaredCapabilitiesAsIs() {
        Map<String, Object> response = getHandshake();

        assertEquals(SUCCESS_CODE, codeOf(response), "握手端点不应要求租户头，也不应失败");
        List<Map<String, Object>> entities = entitiesOf(response);
        assertEquals(List.of("endorsement", "free", "policy"),
                entities.stream().map(entity -> entity.get("entityCode")).toList(),
                "握手清单须按实体码排序（与声明顺序无关，比对两侧时少一个干扰维度）");
        assertEquals(consistencyOf(entities, "policy"), "EVENTUAL",
                "读模型实体必须如实回报 EVENTUAL，而不是按愿望填 STRONG");
        assertEquals(consistencyOf(entities, "free"), "STRONG");
        assertTrue(entities.stream().allMatch(entity -> "YAML".equals(entity.get("source"))),
                "本测试无 Bean 逃生舱实现，来源应全为 YAML：" + entities);
    }

    @Test
    @DisplayName("缺少租户头即拒绝：不得退化成跨租户取数")
    void missingTenantHeaderIsRejected() {
        Map<String, Object> response = postQuery(null, aggregateBody("policy", "P-1", "premium", "SUM", "total"));

        assertEquals("67000010", codeOf(response), "缺租户须失败关闭，而不是查了但没有数据：" + response);
        assertNull(response.get("data"));
    }

    // ------------------------------------------------------------------ 两条强制注入的过滤

    @Test
    @DisplayName("租户过滤与删除标记过滤由 starter 强制注入（同保单号、跨租户、已删除各一条作对照）")
    void tenantAndDeletionFiltersAreForceInjected() {
        insertPolicy("T-001-P-1", TENANT, "P-1", "甲", new BigDecimal("100.00"), 10, "ACTIVE", "2026-01-01", 0);
        // 对照组 1：同保单号、另一个租户 —— 租户过滤失效时它会被算进来
        insertPolicy("T-002-P-1", OTHER_TENANT, "P-1", "乙", new BigDecimal("999.00"), 10, "ACTIVE", "2026-01-01", 0);
        // 对照组 2：同租户同保单、已逻辑删除 —— 删除过滤失效时它会被算进来
        insertPolicy("T-001-P-1-DEL", TENANT, "P-1", "丙", new BigDecimal("777.00"), 10, "ACTIVE", "2026-01-01", 1);

        Map<String, Object> response = postQuery(TENANT,
                aggregateBody("policy", "P-1", "premium", "SUM", "total"));

        assertEquals(SUCCESS_CODE, codeOf(response), response.toString());
        assertDecimal(valueOf(response, "total"), "100.00");
    }

    @Test
    @DisplayName("deleted-column: none 经元数据核验后走恒真条件，且允许跨主体聚合")
    void verifiedNoDeletionColumnStillUsesStableWhereShape() {
        insertFree("F-1", TENANT, "P-1", new BigDecimal("10.00"));
        insertFree("F-2", TENANT, "P-2", new BigDecimal("20.00"));
        insertFree("F-3", OTHER_TENANT, "P-1", new BigDecimal("500.00"));

        // subjectId 为空 + allow-cross-subject: true ⇒ 跨主体聚合；租户过滤仍然生效
        Map<String, Object> response = postQuery(TENANT, aggregateBody("free", null, "amount", "SUM", "total"));

        assertEquals(SUCCESS_CODE, codeOf(response), response.toString());
        assertDecimal(valueOf(response, "total"), "30.00");
    }

    @Test
    @DisplayName("布尔型删除标记列走 = FALSE 分支（与数值型 = 0 分支的区别在真库上验证）")
    void booleanDeletionColumnIsFiltered() {
        insertEndorsement("E-1", TENANT, "P-1", new BigDecimal("50.00"),
                "2026-01-01T00:00:00", "2026-12-31T00:00:00", false);
        insertEndorsement("E-2", TENANT, "P-1", new BigDecimal("70.00"),
                "2026-01-01T00:00:00", "2026-12-31T00:00:00", true);

        Map<String, Object> response = postQuery(TENANT,
                temporalBody("P-1", "2026-06-01T00:00:00", "fee", "SUM", "total"));

        assertEquals(SUCCESS_CODE, codeOf(response), response.toString());
        assertDecimal(valueOf(response, "total"), "50.00");
    }

    // ------------------------------------------------------------------ 空集合语义（R-3）

    @Test
    @DisplayName("空集合：SUM/AVG/MIN/MAX 回报 null、COUNT 回报 0（不得把 null 折算成 0）")
    void emptySetReportsNullForMeasuresAndZeroForCount() {
        Map<String, Object> response = postQuery(TENANT, Map.of(
                "requestId", "req-empty",
                "entityCode", "policy",
                "maxRows", 1,
                "subjectId", "P-NONE",
                "selections", List.of(
                        selection("policy.premium", "SUM", "total"),
                        selection("policy.premium", "COUNT", "cnt"))));

        assertEquals(SUCCESS_CODE, codeOf(response), response.toString());
        assertNull(valueOf(response, "total"),
                "无匹配行时 SUM 必须是 null：「没有明细」与「明细之和为 0」是两件事");
        assertInteger(valueOf(response, "cnt"), 0L);
        assertEquals("DECIMAL", dataTypeOf(response, "total"), "值为 null 时 dataType 是区分「无明细」与「类型未知」的唯一依据");
    }

    @Test
    @DisplayName("非空且真和为 0 ⇒ SUM 回报 0（与上一条构成对照，证明 null 未被折算）")
    void nonEmptyZeroSumReportsZero() {
        insertPolicy("K-1", TENANT, "P-1", "甲", new BigDecimal("50.00"), 10, "ACTIVE", "2026-01-01", 0);
        insertPolicy("K-2", TENANT, "P-1", "乙", new BigDecimal("-50.00"), 10, "ACTIVE", "2026-01-01", 0);

        Map<String, Object> response = postQuery(TENANT, aggregateBody("policy", "P-1", "premium", "SUM", "total"));

        assertEquals(SUCCESS_CODE, codeOf(response));
        assertDecimal(valueOf(response, "total"), "0");
    }

    // ------------------------------------------------------------------ 明细模式

    @Test
    @DisplayName("明细零行：逐别名回报 null（别名必须齐全，否则下游把「没数据」判成「响应违约」）")
    void detailZeroRowsReportsNullPerAlias() {
        Map<String, Object> response = postQuery(TENANT, Map.of(
                "requestId", "req-detail-empty",
                "entityCode", "policy",
                "maxRows", 1,
                "subjectId", "P-NONE",
                "selections", List.of(
                        selection("policy.holderName", null, "holder"),
                        selection("policy.status", null, "status"))));

        assertEquals(SUCCESS_CODE, codeOf(response), response.toString());
        assertEquals(List.of("holder", "status"), aliasesOf(response),
                "零行也必须回报全部别名：消费侧按别名配对，缺失即抛 ANALYTICAL_RESPONSE_INVALID");
        assertNull(valueOf(response, "holder"));
        assertNull(valueOf(response, "status"));
    }

    @Test
    @DisplayName("日期字段出参为 ISO 字符串而非数组（线格式是隐式契约）")
    void dateValuesAreRenderedAsIsoStrings() {
        insertPolicy("D-1", TENANT, "P-1", "甲", new BigDecimal("1.00"), 10, "ACTIVE", "2026-03-05", 0);

        Map<String, Object> response = postQuery(TENANT, Map.of(
                "requestId", "req-date",
                "entityCode", "policy",
                "maxRows", 1,
                "subjectId", "P-1",
                "selections", List.of(selection("policy.issueDate", null, "issue"))));

        assertEquals(SUCCESS_CODE, codeOf(response), response.toString());
        Object issue = valueOf(response, "issue");
        assertTrue(issue instanceof String,
                "日期必须渲染成 ISO 字符串：Jackson 的 WRITE_DATES_AS_TIMESTAMPS 默认为 true，"
                        + "直接放 LocalDate 会落成数组 [2026,3,5]，而下游按字符串读 ⇒ 只有业务产物缺失。实际：" + issue);
        assertEquals("2026-03-05", issue);
    }

    // ------------------------------------------------------------------ 时序（半开区间）

    @Test
    @DisplayName("时效实体的半开区间 [起, 止)：换一个业务时点得到不同结果，边界日归后一段")
    void temporalRangeIsHalfOpenAndBusinessTimeChangesResult() {
        insertEndorsement("E-1", TENANT, "P-1", new BigDecimal("100.00"),
                "2026-01-01T00:00:00", "2026-06-01T00:00:00", false);
        insertEndorsement("E-2", TENANT, "P-1", new BigDecimal("200.00"),
                "2026-06-01T00:00:00", null, false);

        assertDecimal(valueOf(postQuery(TENANT, temporalBody("P-1", "2026-03-01T00:00:00", "fee", "SUM", "t")), "t"),
                "100.00");
        assertDecimal(valueOf(postQuery(TENANT, temporalBody("P-1", "2026-09-01T00:00:00", "fee", "SUM", "t")), "t"),
                "200.00");
        // 边界日 2026-06-01 归后一段（起含、止不含）——这是与目录版本表区间口径一致的两段相接写法
        assertDecimal(valueOf(postQuery(TENANT, temporalBody("P-1", "2026-06-01T00:00:00", "fee", "SUM", "t")), "t"),
                "200.00");
    }

    // ------------------------------------------------------------------ NULL 度量守卫

    @Test
    @DisplayName("度量列有 NULL 明细时显式失败：SQL 会静默剔除该行，两个档位由此分叉")
    void nullMeasureFailsInsteadOfBeingSilentlyDropped() {
        insertPolicy("N-1", TENANT, "P-1", "甲", new BigDecimal("100.00"), 10, "ACTIVE", "2026-01-01", 0);
        insertPolicy("N-2", TENANT, "P-1", "乙", null, 10, "ACTIVE", "2026-01-01", 0);

        Map<String, Object> response = postQuery(TENANT, aggregateBody("policy", "P-1", "premium", "SUM", "total"));

        assertEquals("67000012", codeOf(response),
                "SQL 会算出 100.00 并成功返回，而基准实现对 NULL 直接失败 ⇒ 必须显式失败收口，实际：" + response);
        assertTrue(String.valueOf(response.get("message")).contains("取不到值"),
                "失败消息须指向「有明细取不到值」这一事实：" + response.get("message"));
    }

    // ------------------------------------------------------------------ 参数化（端到端）

    @Test
    @DisplayName("筛选条件的值走占位符：SQL 注入串被当字面量比较，表结构不受影响")
    void filterValuesAreBoundAsParametersNotConcatenated() {
        insertPolicy("I-1", TENANT, "P-1", "甲", new BigDecimal("100.00"), 10, "ACTIVE", "2026-01-01", 0);
        String injection = "'; DROP TABLE v_analytical_policy; --";

        Map<String, Object> response = postQuery(TENANT, Map.of(
                "requestId", "req-injection",
                "entityCode", "policy",
                "maxRows", 1,
                "subjectId", "P-1",
                "selections", List.of(selection("policy.holderName", null, "holder")),
                "filterCondition", comparison("policy.holderName", "EQ", List.of(injection))));

        assertEquals(SUCCESS_CODE, codeOf(response), "注入串应被当普通字面量比较，而不是让请求失败：" + response);
        assertNull(valueOf(response, "holder"), "没有任何一行的 holder_name 等于该串 ⇒ 明细零行 ⇒ 逐别名 null");
        Long alive = jdbc.queryForObject("SELECT COUNT(*) FROM v_analytical_policy", Long.class);
        assertEquals(1L, alive, "参数化生效的证据：注入串没有改变数据库结构，原行仍在");
    }

    // ------------------------------------------------------------------ 筛选算子

    @Test
    @DisplayName("AND/OR 组合与 IN 算子的占位符个数、取值顺序与参数列表严格对应")
    void combinedFiltersBindParametersInOrder() {
        insertPolicy("C-1", TENANT, "P-1", "甲", new BigDecimal("10.00"), 1, "ACTIVE", "2026-01-01", 0);
        insertPolicy("C-2", TENANT, "P-1", "乙", new BigDecimal("20.00"), 2, "LAPSED", "2026-01-01", 0);
        insertPolicy("C-3", TENANT, "P-1", "丙", new BigDecimal("30.00"), 3, "ACTIVE", "2026-01-01", 0);

        Map<String, Object> response = postQuery(TENANT, Map.of(
                "requestId", "req-combined",
                "entityCode", "policy",
                "maxRows", 1,
                "subjectId", "P-1",
                "selections", List.of(selection("policy.premium", "SUM", "total")),
                "filterCondition", Map.of(
                        "kind", "AND",
                        "operands", List.of(
                                comparison("policy.status", "IN", List.of("ACTIVE", "LAPSED")),
                                comparison("policy.sumInsured", "GT", List.of(1))))));

        assertEquals(SUCCESS_CODE, codeOf(response), response.toString());
        // 逐行判定（三行数据见上方 insert）：
        //   C-1 status=ACTIVE 命中 IN，但 sumInsured=1 不满足 > 1 ⇒ 排除
        //   C-2 status=LAPSED 命中 IN，sumInsured=2 > 1 ⇒ 命中 20.00
        //   C-3 status=ACTIVE 命中 IN，sumInsured=3 > 1 ⇒ 命中 30.00
        assertDecimal(valueOf(response, "total"), "50.00");
    }

    @Test
    @DisplayName("IS_NULL 算子不产生占位符（无值算子的值列表为空）")
    void isNullOperatorCarriesNoPlaceholder() {
        insertPolicy("Z-1", TENANT, "P-1", "甲", null, 1, "ACTIVE", "2026-01-01", 0);
        insertPolicy("Z-2", TENANT, "P-1", "乙", new BigDecimal("5.00"), 2, "ACTIVE", "2026-01-01", 0);

        Map<String, Object> response = postQuery(TENANT, Map.of(
                "requestId", "req-isnull",
                "entityCode", "policy",
                "maxRows", 1,
                "subjectId", "P-1",
                "selections", List.of(selection("policy.sumInsured", "SUM", "total")),
                "filterCondition", comparison("policy.premium", "IS_NULL", List.of())));

        assertEquals(SUCCESS_CODE, codeOf(response), response.toString());
        assertDecimal(valueOf(response, "total"), "1");
    }

    // ------------------------------------------------------------------ 数据准备

    private static List<Map<String, Object>> entitiesOf(Map<String, Object> response) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entities = (List<Map<String, Object>>) dataOf(response).get("entities");
        assertFalse(entities == null || entities.isEmpty(), "握手响应必须包含已声明的实体：" + response);
        return entities;
    }

    private static Object consistencyOf(List<Map<String, Object>> entities, String entityCode) {
        return entities.stream()
                .filter(entity -> entityCode.equals(entity.get("entityCode")))
                .map(entity -> entity.get("consistency"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("握手响应里没有实体 " + entityCode + "：" + entities));
    }

    /** 聚合请求体（单个度量项 + 给定算子）。 */
    private static Map<String, Object> aggregateBody(String entityCode, String subjectId,
                                                     String fieldSuffix, String aggregation, String alias) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", "req-" + entityCode + "-" + aggregation);
        body.put("entityCode", entityCode);
        body.put("maxRows", 1);
        if (subjectId != null) {
            body.put("subjectId", subjectId);
        }
        body.put("selections", List.of(
                selection(entityCode + "." + fieldSuffix, aggregation, alias)));
        return body;
    }

    /** 时效实体的聚合请求体（必须携带 businessTime）。 */
    private static Map<String, Object> temporalBody(String subjectId, String businessTime,
                                                    String fieldSuffix, String aggregation, String alias) {
        Map<String, Object> body = aggregateBody("endorsement", subjectId, fieldSuffix, aggregation, alias);
        body.put("businessTime", businessTime);
        return body;
    }

    private static Map<String, Object> selection(String fieldCode, String aggregation, String alias) {
        Map<String, Object> selection = new LinkedHashMap<>();
        selection.put("fieldCode", fieldCode);
        selection.put("alias", alias);
        if (aggregation != null) {
            selection.put("aggregation", aggregation);
        }
        return selection;
    }

    private static Map<String, Object> comparison(String fieldPath, String operator, List<Object> values) {
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("kind", "COMPARISON");
        filter.put("fieldPath", fieldPath);
        filter.put("operator", operator);
        filter.put("values", values);
        return filter;
    }

    private void insertPolicy(String id, String tenant, String policyId, String holder, BigDecimal premium,
                              int sumInsured, String status, String issueDate, int deleted) {
        jdbc.update("INSERT INTO v_analytical_policy"
                        + " (id, tenant_id, policy_id, holder_name, premium, sum_insured, status, issue_date, is_deleted)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, tenant, policyId, holder, premium, sumInsured, status, LocalDate.parse(issueDate), deleted);
    }

    private void insertEndorsement(String id, String tenant, String policyId, BigDecimal fee,
                                   String from, String to, boolean deleted) {
        jdbc.update("INSERT INTO v_analytical_endorsement"
                        + " (id, tenant_id, policy_id, fee, effective_from, effective_to, is_deleted)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, tenant, policyId, fee,
                LocalDateTime.parse(from), to == null ? null : LocalDateTime.parse(to), deleted);
    }

    private void insertFree(String id, String tenant, String policyId, BigDecimal amount) {
        jdbc.update("INSERT INTO v_analytical_free (id, tenant_id, policy_id, amount) VALUES (?, ?, ?, ?)",
                id, tenant, policyId, amount);
    }
}
