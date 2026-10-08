package com.titanium.analytical.testapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.titanium.analytical.AnalyticalConsistency;
import com.titanium.analytical.AnalyticalQueryException;
import com.titanium.analytical.config.AnalyticalEntityProperties;
import com.titanium.analytical.query.AnalyticalEntitySource;
import com.titanium.analytical.query.AnalyticalQueryService;
import com.titanium.analytical.query.AnalyticalQueryTranslator;
import com.titanium.analytical.query.AnalyticalValueNormalizer;
import com.titanium.metadata.analytical.AnalyticalAggregation;
import com.titanium.metadata.analytical.AnalyticalFieldSelection;
import com.titanium.metadata.analytical.AnalyticalHandshakeResponse;
import com.titanium.metadata.analytical.AnalyticalQueryRequest;
import com.titanium.metadata.analytical.AnalyticalQueryResponse;
import com.titanium.metadata.analytical.AnalyticalValue;
import com.titanium.metadata.enums.FieldDataType;
import com.titanium.metadata.errorcode.FeatureCenterErrorCode;

/**
 * 取数逃生舱（R-2 第二道防线「走 Bean」）的判据：分派、握手来源标记、构造期冲突拒绝、
 * 以及 starter 在 Bean 路径上**唯一能机械校验**的两件事（响应回报的实体码与一致性）。
 *
 * <p>走 Bean 意味着实现者自行承担租户/删除标记/参数化三条义务——它们无法由 starter 校验
 * （见 {@link AnalyticalEntitySource}）。故本类不假装覆盖那三条，只覆盖 starter 承诺的那部分：
 * 把租户<b>显式传入</b>、把声明<b>如实回报</b>、把冲突<b>在启动期拒绝</b>。</p>
 */
class AnalyticalEntitySourceTest extends AnalyticalEndpointTestSupport {

    /** 只由 Bean 提供、YAML 未声明的实体码（用它才能证明分派确实走了 Bean 路径）。 */
    private static final String BEAN_ONLY = "beanonly";

    @Autowired
    private AnalyticalEntityProperties properties;

    @Autowired
    private AnalyticalQueryTranslator translator;

    @Autowired
    private AnalyticalValueNormalizer normalizer;

    @Test
    @DisplayName("Bean 接管自己的实体：YAML 未声明的实体码取数成功，且租户被显式传入实现")
    void beanSourceTakesOverItsEntityAndReceivesTenant() {
        AnalyticalQueryService service = service(new FixedValueSource(BEAN_ONLY, AnalyticalConsistency.STRONG, null));

        AnalyticalQueryResponse response = service.query(request(BEAN_ONLY), TENANT);

        assertEquals("T-" + TENANT, firstValue(response),
                "租户须作为入参交给实现（starter 在 Bean 路径上代劳不了过滤，只能保证实现「拿得到」它）："
                        + response);
        assertEquals(BEAN_ONLY, response.entityCode());
        assertEquals("STRONG", response.consistency());
    }

    @Test
    @DisplayName("握手端点把 Bean 来源的实体标为 BEAN，并如实回报其声明的一致性")
    void handshakeMarksBeanSourcedEntities() {
        assertEquals(3, service().handshake().entities().size(), "无 Bean 时只有 YAML 的 3 个实体");

        List<AnalyticalHandshakeResponse.EntityCapability> capabilities =
                service(new FixedValueSource(BEAN_ONLY, AnalyticalConsistency.EVENTUAL, null)).handshake().entities();

        assertEquals(List.of(BEAN_ONLY, "endorsement", "free", "policy"),
                capabilities.stream().map(AnalyticalHandshakeResponse.EntityCapability::entityCode).toList(),
                "两路来源合并后仍按实体码排序");
        AnalyticalHandshakeResponse.EntityCapability beanCapability = capabilities.stream()
                .filter(capability -> BEAN_ONLY.equals(capability.entityCode()))
                .findFirst().orElseThrow();
        assertEquals(AnalyticalHandshakeResponse.SOURCE_BEAN, beanCapability.source(),
                "消费者据 source 判断「该实体读的到底是专用视图还是自写 SQL」");
        assertEquals("EVENTUAL", beanCapability.consistency(),
                "一致性由实现声明，starter 不按任何规则推测（推测会让声明错误与声明正确同形）");
    }

    // ------------------------------------------------------------------ 构造期拒绝

    @Test
    @DisplayName("实现未声明实体码 ⇒ 启动期拒绝（没有实体码就无法判断它接管了谁）")
    void beanWithoutEntityCodeIsRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service(new FixedValueSource("  ", AnalyticalConsistency.STRONG, null)));

        assertTrue(ex.getMessage().contains("未声明实体码"), ex.getMessage());
    }

    @Test
    @DisplayName("实现未声明一致性 ⇒ 启动期拒绝（握手端点靠它回报，缺失即 R-14 对账失去输入）")
    void beanWithoutConsistencyIsRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service(new FixedValueSource(BEAN_ONLY, null, null)));

        assertTrue(ex.getMessage().contains("未声明一致性"), ex.getMessage());
    }

    @Test
    @DisplayName("Bean 与 YAML 声明同一实体码 ⇒ 启动期拒绝（否则谁生效取决于遍历顺序）")
    void beanCollidingWithYamlEntityIsRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service(new FixedValueSource("policy", AnalyticalConsistency.EVENTUAL, null)));

        assertTrue(ex.getMessage().contains("同时被 YAML 声明"), ex.getMessage());
    }

    @Test
    @DisplayName("两个 Bean 声明同一实体码 ⇒ 启动期拒绝（不采用「后者覆盖前者」）")
    void twoBeansSharingEntityCodeAreRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service(
                        new FixedValueSource(BEAN_ONLY, AnalyticalConsistency.STRONG, null),
                        new FixedValueSource(BEAN_ONLY, AnalyticalConsistency.STRONG, null)));

        assertTrue(ex.getMessage().contains("被两个取数实现同时声明"), ex.getMessage());
    }

    // ------------------------------------------------------------------ 响应校验

    @Test
    @DisplayName("有 Bean 时 YAML 声明的实体仍走 YAML 路径（分派按实体码精确匹配，不是「有 Bean 就用 Bean」）")
    void yamlDeclaredEntitiesStillTakeYamlPathWhenBeanPresent() {
        clearTables();
        AnalyticalQueryService service = service(new FixedValueSource(BEAN_ONLY, AnalyticalConsistency.STRONG, null));

        AnalyticalQueryResponse response = service.query(request("free"), TENANT);

        assertEquals("free", response.entityCode());
        assertEquals("STRONG", response.consistency());
        // Bean 路径的响应恒为字符串 "T-<tenant>"，故此处的 null 只有「走的不是 Bean」一种解释
        assertNull(firstValue(response), "空表上的 SUM 是 null（R-3），而不是 Bean 给的固定值");
    }

    @Test
    @DisplayName("实现回报的实体码被篡改 ⇒ 拒绝（响应会被归到一个错误的实体上）")
    void mismatchedEchoedEntityCodeIsRejected() {
        AnalyticalQueryService service = service(
                new FixedValueSource(BEAN_ONLY, AnalyticalConsistency.STRONG, "endorsement"));

        AnalyticalQueryException ex = assertThrows(AnalyticalQueryException.class,
                () -> service.query(request(BEAN_ONLY), TENANT));

        assertEquals(FeatureCenterErrorCode.ANALYTICAL_QUERY_FAILED, ex.errorCode());
        assertTrue(ex.getMessage().contains("却在响应里回报实体码"), ex.getMessage());
    }

    @Test
    @DisplayName("实现回报的一致性与声明不符 ⇒ 67000017（这条不一致恰是 R-14 对账要抓的违约）")
    void mismatchedDeclaredConsistencyIsRejected() {
        // 声明 EVENTUAL、响应报 STRONG：把有投影延迟的读模型说成强一致，正是对账要发现的错报
        AnalyticalQueryService service = service(new FixedValueSource(
                BEAN_ONLY, AnalyticalConsistency.EVENTUAL, null, "STRONG"));

        AnalyticalQueryException ex = assertThrows(AnalyticalQueryException.class,
                () -> service.query(request(BEAN_ONLY), TENANT));

        assertEquals(FeatureCenterErrorCode.ANALYTICAL_CONSISTENCY_MISMATCH, ex.errorCode());
        assertTrue(ex.getMessage().contains("握手端点按声明回报"), ex.getMessage());
    }

    // ------------------------------------------------------------------ 装置

    private AnalyticalQueryService service(AnalyticalEntitySource... sources) {
        return new AnalyticalQueryService(properties, translator, normalizer, jdbc, List.of(sources));
    }

    private static AnalyticalQueryRequest request(String entityCode) {
        return new AnalyticalQueryRequest("req-bean", entityCode, null,
                List.of(new AnalyticalFieldSelection("free.amount", AnalyticalAggregation.SUM, "total")),
                null, List.of(), null, 1);
    }

    private static Object firstValue(AnalyticalQueryResponse response) {
        assertEquals(1, response.values().size(), "实现回报了几个值就应原样透传几个：" + response);
        return response.values().get(0).value();
    }

    /**
     * 固定值取数实现：回报一个只有它能产出的值（{@code "T-" + tenantId}），
     * 使「走了 Bean 路径」与「租户被传入」各自可证伪。
     */
    private static final class FixedValueSource implements AnalyticalEntitySource {

        private final String entityCode;
        private final AnalyticalConsistency declared;
        private final String echoedEntityCode;
        private final String echoedConsistency;

        private FixedValueSource(String entityCode, AnalyticalConsistency declared, String echoedEntityCode) {
            this(entityCode, declared, echoedEntityCode, null);
        }

        private FixedValueSource(String entityCode, AnalyticalConsistency declared,
                                 String echoedEntityCode, String echoedConsistency) {
            this.entityCode = entityCode;
            this.declared = declared;
            this.echoedEntityCode = echoedEntityCode;
            this.echoedConsistency = echoedConsistency;
        }

        @Override
        public String entityCode() {
            return entityCode;
        }

        @Override
        public AnalyticalConsistency consistency() {
            return declared;
        }

        @Override
        public AnalyticalQueryResponse query(AnalyticalQueryRequest request, String tenantId) {
            return new AnalyticalQueryResponse(
                    echoedEntityCode == null ? entityCode : echoedEntityCode,
                    echoedConsistency == null ? declared.name() : echoedConsistency,
                    LocalDateTime.parse("2026-01-01T00:00:00"),
                    List.of(new AnalyticalValue("total", FieldDataType.STRING.getCode(), "T-" + tenantId)),
                    false, List.of());
        }
    }
}
