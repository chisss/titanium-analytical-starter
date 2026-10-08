package com.titanium.analytical.testapp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import com.titanium.analytical.AnalyticalConsistency;
import com.titanium.analytical.query.AnalyticalEntitySource;
import com.titanium.metadata.analytical.AnalyticalQueryRequest;
import com.titanium.metadata.analytical.AnalyticalQueryResponse;
import com.titanium.metadata.analytical.AnalyticalValue;
import com.titanium.metadata.enums.FieldDataType;

/**
 * 逃生舱在**真实容器**里的判据：属主域写一个实现类，取数即被它接管，无需改 starter、无需改 YAML。
 *
 * <p>为何这条不能只靠服务级单测（{@link AnalyticalEntitySourceTest}）：那里的实现是手工
 * {@code new} 后直接传参的，而「容器会不会把宿主域的 Bean 收集成 {@code List} 注入进
 * {@code analyticalQueryService}」是<b>参数解析</b>行为——它取决于
 * {@code @ConditionalOnMissingBean}、注入点签名与 Bean 的注册方式。手工传参的测试对这三者完全免疫。</p>
 *
 * <p>🔴 本类用 {@link TestConfiguration} + {@code @Import} 而不是 {@code @Component}：
 * 后者会被 {@link AnalyticalTestApplication} 的组件扫描拾取，从而让<b>所有</b>测试类的上下文里都多出
 * 一个 {@code beanonly} 实体——{@link AnalyticalQueryEndpointIntegrationTest} 的握手断言
 * （恰好 3 个实体）会跟着变红，而红的方向指向那份额外的 Bean，看不出它来自本类。</p>
 */
@Import(AnalyticalEntitySourceIntegrationTest.BeanSourceConfig.class)
class AnalyticalEntitySourceIntegrationTest extends AnalyticalEndpointTestSupport {

    /** 逃生舱实现：只在此测试的上下文里注册，不参与组件扫描。 */
    @TestConfiguration
    static class BeanSourceConfig {

        @Bean
        AnalyticalEntitySource beanOnlySource() {
            return new BeanOnlySource();
        }
    }

    @Test
    @DisplayName("宿主域的实现经容器注入后接管取数：端点返回实现给出的值，租户从请求头一路传到实现")
    void beanSourceIsInjectedAndServesItsEntity() {
        Map<String, Object> response = postQuery(TENANT, Map.of(
                "requestId", "req-bean-e2e",
                "entityCode", "beanonly",
                "maxRows", 1,
                "selections", List.of(Map.of(
                        "fieldCode", "free.amount", "alias", "total", "aggregation", "SUM"))));

        assertEquals(SUCCESS_CODE, codeOf(response), response.toString());
        assertEquals("T-" + TENANT, valueOf(response, "total"),
                "值只可能来自 Bean（YAML 未声明 beanonly），且其中的租户证明 starter 把请求头传到了实现");
    }

    @Test
    @DisplayName("握手端点如实把该实体标为 BEAN 来源（R-14 对账据此区分「专用视图」与「自写 SQL」）")
    void handshakeReportsBeanSourcedEntity() {
        List<Map<String, Object>> entities = entitiesOf(getHandshake());

        assertEquals(List.of("beanonly", "endorsement", "free", "policy"),
                entities.stream().map(entity -> entity.get("entityCode")).toList(),
                "Bean 是「追加一个实体」而非「替换执行路径」：YAML 的三个实体仍在册，两路来源合并后按实体码排序");
        Map<String, Object> beanEntity = entities.stream()
                .filter(entity -> "beanonly".equals(entity.get("entityCode")))
                .findFirst().orElseThrow();
        assertEquals("BEAN", beanEntity.get("source"));
        assertEquals("EVENTUAL", beanEntity.get("consistency"),
                "一致性取自实现的声明；starter 不按任何规则推测");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entitiesOf(Map<String, Object> response) {
        List<Map<String, Object>> entities = (List<Map<String, Object>>) dataOf(response).get("entities");
        if (entities == null || entities.isEmpty()) {
            throw new AssertionError("握手响应必须包含已声明的实体：" + response);
        }
        return entities;
    }

    /** 固定值实现：回报只有它能产出的值（含租户），使「Bean 被调用」与「租户被传入」各自可证伪。 */
    private static final class BeanOnlySource implements AnalyticalEntitySource {

        @Override
        public String entityCode() {
            return "beanonly";
        }

        @Override
        public AnalyticalConsistency consistency() {
            return AnalyticalConsistency.EVENTUAL;
        }

        @Override
        public AnalyticalQueryResponse query(AnalyticalQueryRequest request, String tenantId) {
            return new AnalyticalQueryResponse("beanonly", consistency().name(),
                    LocalDateTime.parse("2026-01-01T00:00:00"),
                    List.of(new AnalyticalValue("total", FieldDataType.STRING.getCode(), "T-" + tenantId)),
                    false, List.of());
        }
    }
}
