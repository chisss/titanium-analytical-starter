package com.titanium.analytical.testapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 各类**被拒绝**的请求：逐项显式失败，绝不静默截断/跳过。
 *
 * <p>这一组用例的共同命题是「失败的方向必须指向请求方」：错误码落在
 * {@code 67000019}（实体未声明）/ {@code 67000020}（字段不可用）/ {@code 67000015}（缺主体）/
 * {@code 67000021}（请求形状不被支持）四个「请求侧」码上，
 * 而不是落进 {@code 67000012}（执行失败——那会让排障的人去查数据库）。</p>
 */
class AnalyticalQueryRejectionIntegrationTest extends AnalyticalEndpointTestSupport {

    @BeforeEach
    void resetData() {
        clearTables();
    }

    @Test
    @DisplayName("未声明的实体 ⇒ 67000019（不按前缀猜实体）")
    void undeclaredEntityIsRejected() {
        Map<String, Object> response = postQuery(TENANT,
                body("claim", "C-1", List.of(selection("claim.amount", null, "amount"))));

        assertEquals("67000019", codeOf(response), response.toString());
        assertTrue(String.valueOf(response.get("message")).contains("policy"),
                "拒绝消息须列出已声明的实体，便于一轮补齐：" + response.get("message"));
    }

    @Test
    @DisplayName("未声明的字段码 ⇒ 67000020")
    void undeclaredFieldIsRejected() {
        Map<String, Object> response = postQuery(TENANT,
                body("policy", "P-1", List.of(selection("policy.noSuchField", null, "x"))));

        assertEquals("67000020", codeOf(response), response.toString());
    }

    @Test
    @DisplayName("字段引用的实体码与本请求实体不符 ⇒ 67000020（一次取数一个实体）")
    void foreignEntityPrefixInFieldReferenceIsRejected() {
        Map<String, Object> response = postQuery(TENANT,
                body("policy", "P-1", List.of(selection("endorsement.fee", null, "fee"))));

        assertEquals("67000020", codeOf(response), response.toString());
        assertTrue(String.valueOf(response.get("message")).contains("跨实体字段引用"),
                "须说明这是跨实体引用，而不是「字段没找到」：" + response.get("message"));
    }

    @Test
    @DisplayName("能力位未开放（analyzable=false）⇒ 67000020，而不是静默取到该列")
    void fieldWithoutAnalyzableCapabilityIsRejected() {
        Map<String, Object> response = postQuery(TENANT,
                body("policy", "P-1", List.of(selection("policy.secretNote", null, "note"))));

        assertEquals("67000020", codeOf(response), response.toString());
        assertTrue(String.valueOf(response.get("message")).contains("analyzable"),
                "须指出是哪个能力位没开：" + response.get("message"));
    }

    @Test
    @DisplayName("冻结/非分析字段在筛选条件中同样被拒 ⇒ 67000020")
    void fieldWithoutFilterCapabilityInFilterIsRejected() {
        Map<String, Object> response = postQuery(TENANT, Map.of(
                "requestId", "req-filter-capability",
                "entityCode", "policy",
                "maxRows", 1,
                "subjectId", "P-1",
                "selections", List.of(selection("policy.holderName", null, "holder")),
                "filterCondition", comparison("policy.secretNote", "EQ", List.of("x"))));

        assertEquals("67000020", codeOf(response), response.toString());
    }

    @Test
    @DisplayName("未开放跨主体聚合而请求又未带主体 ⇒ 67000015（失败关闭，不退化成全表）")
    void crossSubjectWithoutAllowanceIsRejected() {
        Map<String, Object> response = postQuery(TENANT,
                body("policy", null, List.of(selection("policy.premium", "SUM", "total"))));

        assertEquals("67000015", codeOf(response),
                "放行会得到「所有主体混在一起」的聚合值，而下游无从察觉取错了主体：" + response);
    }

    @Test
    @DisplayName("groupBy 非空 ⇒ 67000021（扁平 values 装不下 N 个分组）")
    void groupByIsRejected() {
        Map<String, Object> request = body("policy", "P-1",
                List.of(selection("policy.premium", "SUM", "total")));
        request.put("groupBy", List.of("policy.status"));

        Map<String, Object> response = postQuery(TENANT, request);

        assertEquals("67000021", codeOf(response), response.toString());
        assertTrue(String.valueOf(response.get("message")).contains("分组"),
                "须说明分组为何装不进去：" + response.get("message"));
    }

    @Test
    @DisplayName("时效实体缺 businessTime ⇒ 67000021（无时点则区间不可判定）")
    void temporalEntityWithoutBusinessTimeIsRejected() {
        Map<String, Object> response = postQuery(TENANT,
                body("endorsement", "P-1", List.of(selection("endorsement.fee", "SUM", "total"))));

        assertEquals("67000021", codeOf(response), response.toString());
        assertTrue(String.valueOf(response.get("message")).contains("businessTime"),
                "须点名缺的是哪个入参：" + response.get("message"));
    }

    @Test
    @DisplayName("聚合项与明细项混用 ⇒ 67000021（每列的行语义不确定）")
    void mixingAggregateAndDetailSelectionsIsRejected() {
        Map<String, Object> response = postQuery(TENANT, body("policy", "P-1", List.of(
                selection("policy.premium", "SUM", "total"),
                selection("policy.holderName", null, "holder"))));

        assertEquals("67000021", codeOf(response), response.toString());
    }

    @Test
    @DisplayName("明细命中多行 ⇒ 67000021（不静默取第一行）")
    void detailMultipleRowsIsRejected() {
        insertPolicy("M-1", TENANT, "P-1", "甲", new BigDecimal("1.00"), 1, "ACTIVE", "2026-01-01", 0);
        insertPolicy("M-2", TENANT, "P-1", "乙", new BigDecimal("2.00"), 2, "ACTIVE", "2026-01-01", 0);

        Map<String, Object> response = postQuery(TENANT,
                body("policy", "P-1", List.of(selection("policy.holderName", null, "holder"))));

        assertEquals("67000021", codeOf(response), response.toString());
        assertTrue(String.valueOf(response.get("message")).contains("多行"),
                "须说明命中多行且响应没有行维度：" + response.get("message"));
    }

    @Test
    @DisplayName("请求体结构矛盾（取数项为空）⇒ 67000021，而不是框架的 400 默认体")
    void structurallyInvalidRequestBodyIsReportedAsBusinessCode() {
        Map<String, Object> response = postQuery(TENANT, Map.of(
                "requestId", "req-empty-selections",
                "entityCode", "policy",
                "maxRows", 1,
                "selections", List.of()));

        assertEquals("67000021", codeOf(response),
                "契约紧凑构造器拒绝了结构矛盾的载荷 ⇒ 根因在报文上，不能只给下游一个「调用失败」：" + response);
    }

    @Test
    @DisplayName("筛选值按声明类型解析：DECIMAL 字段收到非数值文本 ⇒ 67000021（属请求形状）")
    void unparsableFilterValueIsReportedAsRequestShapeProblem() {
        Map<String, Object> response = postQuery(TENANT, Map.of(
                "requestId", "req-bad-decimal",
                "entityCode", "policy",
                "maxRows", 1,
                "subjectId", "P-1",
                "selections", List.of(selection("policy.holderName", null, "holder")),
                "filterCondition", comparison("policy.premium", "EQ", List.of("not-a-number"))));

        assertEquals("67000021", codeOf(response),
                "值无法按声明类型解析属请求形状问题，不得报成执行失败（那会让人去查库）：" + response);
    }

    // ------------------------------------------------------------------ 数据准备

    private static Map<String, Object> body(String entityCode, String subjectId,
                                            List<Map<String, Object>> selections) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", "req-" + entityCode);
        body.put("entityCode", entityCode);
        body.put("maxRows", 1);
        if (subjectId != null) {
            body.put("subjectId", subjectId);
        }
        body.put("selections", selections);
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
}
