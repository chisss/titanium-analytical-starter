package com.titanium.analytical.testapp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import com.titanium.metadata.analytical.AnalyticalContract;

/**
 * 端点级集成测试的公共装置：真实 servlet 容器 + 真实 HTTP + 真实 H2。
 *
 * <p>用 {@code RANDOM_PORT} 而不是 MockMvc 的 standalone 模式，是为了让「自动装配是否生效」
 * 这一条成为**真实可证伪**的判据（见 {@link AnalyticalTestApplication}）：
 * standalone 模式手工 new 控制器，装配整条链路（条件求值、Bean 注册、路径映射）全被绕过，
 * 于是「引入 starter 就能用」这件事在测试里从未被真正验证过。</p>
 */
@SpringBootTest(classes = AnalyticalTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class AnalyticalEndpointTestSupport {

    /** 取数端点路径（逐字取自契约常量，与本模块控制器同一来源）。 */
    protected static final String QUERY_PATH = AnalyticalContract.PATH + AnalyticalContract.QUERY_SUFFIX;

    /** 握手端点路径。 */
    protected static final String HANDSHAKE_PATH = AnalyticalContract.PATH + AnalyticalContract.HANDSHAKE_SUFFIX;

    /** 默认租户（数据准备与请求共用，避免两处各写一个字符串）。 */
    protected static final String TENANT = "T-001";

    /** 另一个租户：用于证明「租户过滤真的在生效」，而不是「数据恰好都属于本租户」。 */
    protected static final String OTHER_TENANT = "T-002";

    @LocalServerPort
    private int port;

    @Autowired
    private DataSource dataSource;

    /** 直接建在宿主数据源上，用于准备/清理测试数据与绕过端点做交叉核对。 */
    protected JdbcTemplate jdbc;

    private RestClient client;

    @BeforeEach
    void setUpEndpointDevices() {
        jdbc = new JdbcTemplate(dataSource);
        client = RestClient.create("http://localhost:" + port);
    }

    /**
     * 发一次取数请求。
     *
     * @param tenantId 租户头取值；传 {@code null} 表示**故意不带**该头
     * @param body     请求体（会被序列化成 JSON）
     * @return 解析后的 {@code ApiResponse} 信封
     */
    protected Map<String, Object> postQuery(String tenantId, Object body) {
        return client.post().uri(QUERY_PATH)
                .headers(headers -> applyTenant(headers, tenantId))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(new ParameterizedTypeReference<>() { });
    }

    /**
     * 发一次握手请求（不带租户头——该端点有意不要求租户）。
     *
     * @return 解析后的 {@code ApiResponse} 信封
     */
    protected Map<String, Object> getHandshake() {
        return client.get().uri(HANDSHAKE_PATH)
                .retrieve()
                .body(new ParameterizedTypeReference<>() { });
    }

    private void applyTenant(HttpHeaders headers, String tenantId) {
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (tenantId != null) {
            headers.set(AnalyticalContract.TENANT_HEADER, tenantId);
        }
    }

    // ------------------------------------------------------------------ 响应断言辅助

    /**
     * 响应信封里的业务码。
     *
     * <p>🔴 一律用**业务码**判定成败，不看 HTTP 状态码：本契约的失败是 HTTP 200 + 业务码
     * （§8.2），若某条用例改用 HTTP 状态码断言，会在「真实失败」与「路由 404」之间失去区分力。</p>
     */
    protected static String codeOf(Map<String, Object> response) {
        return (String) response.get("code");
    }

    /** 成功码（与 {@code ApiResponse.SUCCESS_CODE} 同值；此处独立写出，避免断言依赖被测常量）。 */
    protected static final String SUCCESS_CODE = "00000000";

    @SuppressWarnings("unchecked")
    protected static Map<String, Object> dataOf(Map<String, Object> response) {
        Object data = response.get("data");
        if (data == null) {
            throw new AssertionError("响应信封里没有 data：code=" + codeOf(response)
                    + " message=" + response.get("message"));
        }
        return (Map<String, Object>) data;
    }

    /** 响应里的全部别名（顺序即请求中取数项的顺序）。 */
    @SuppressWarnings("unchecked")
    protected static List<String> aliasesOf(Map<String, Object> response) {
        List<Map<String, Object>> values = (List<Map<String, Object>>) dataOf(response).get("values");
        if (values == null) {
            throw new AssertionError("响应 data 里没有 values 列表：" + dataOf(response));
        }
        return values.stream().map(value -> (String) value.get("alias")).toList();
    }

    /**
     * 取某个别名的**整个条目**（而非仅 {@code value}）。
     *
     * <p>🔴 刻意不提供「按别名直接取 value」的便利方法：那会有一个静默的错误形态——
     * {@code stream().map(v -> v.get("value")).findFirst()} 在值本身就是 {@code null} 时
     * 抛 NPE（{@code Optional} 不接受 null），于是「别名存在、值为 null」（这正是空集合语义要表达的事）
     * 与「别名缺失」（这正是响应违约）在测试里**同形**。取条目再判断，两条路径可分辨。</p>
     *
     * @throws AssertionError 该别名不存在于响应中
     */
    @SuppressWarnings("unchecked")
    protected static Map<String, Object> valueEntry(Map<String, Object> response, String alias) {
        List<Map<String, Object>> values = (List<Map<String, Object>>) dataOf(response).get("values");
        if (values == null) {
            throw new AssertionError("响应 data 里没有 values 列表：" + dataOf(response));
        }
        for (Map<String, Object> value : values) {
            if (alias.equals(value.get("alias"))) {
                return value;
            }
        }
        throw new AssertionError("响应里没有别名 " + alias + "（别名齐全是消费侧配对的前提）：" + values);
    }

    /** 取某别名的值（别名缺失即断言失败；值本身为 null 是合法形态）。 */
    protected static Object valueOf(Map<String, Object> response, String alias) {
        return valueEntry(response, alias).get("value");
    }

    /** 取某别名回报的数据类型。 */
    protected static String dataTypeOf(Map<String, Object> response, String alias) {
        return (String) valueEntry(response, alias).get("dataType");
    }

    /**
     * 断言某个别名回报的数值（按 {@code BigDecimal} 比较，规避线格式的精度表示差异）。
     *
     * <p>🔴 用 {@code compareTo} 而非 {@code equals}：JSON 里的 {@code 100.00} 经
     * Jackson 反序列化进 {@code Map<String,Object>} 后是 {@code Double 100.0}
     * （{@code USE_BIG_DECIMAL_FOR_FLOATS} 默认关闭），而 {@code BigDecimal.equals}
     * 把标度算进相等性 ⇒ {@code 100.00} 与 {@code 100.0} 会被判不等，红的方向指向被测代码。</p>
     */
    protected static void assertDecimal(Object actual, String expected) {
        if (actual == null) {
            throw new AssertionError("期望数值 " + expected + "，实际为 null"
                    + "（null 是空集合语义的合法取值，但与本断言要表达的事不同）");
        }
        assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(String.valueOf(actual))),
                "期望数值 " + expected + "，实际 " + actual);
    }

    /** 断言某个别名回报的整数。 */
    protected static void assertInteger(Object actual, long expected) {
        if (!(actual instanceof Number number)) {
            throw new AssertionError("期望整数 " + expected + "，实际 " + actual
                    + "（类型 " + (actual == null ? "null" : actual.getClass().getName()) + "）");
        }
        assertEquals(expected, number.longValue());
    }

    // ------------------------------------------------------------------ 数据准备

    /** 清空全部测试表（每个用例自带数据形态，不依赖执行顺序）。 */
    protected void clearTables() {
        jdbc.execute("DELETE FROM v_analytical_policy");
        jdbc.execute("DELETE FROM v_analytical_endorsement");
        jdbc.execute("DELETE FROM v_analytical_free");
    }
}
