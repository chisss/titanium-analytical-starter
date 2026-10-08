package com.titanium.analytical.web;

import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.titanium.analytical.AnalyticalQueryException;
import com.titanium.analytical.query.AnalyticalQueryService;
import com.titanium.metadata.analytical.AnalyticalContract;
import com.titanium.metadata.analytical.AnalyticalHandshakeResponse;
import com.titanium.metadata.analytical.AnalyticalQueryRequest;
import com.titanium.metadata.analytical.AnalyticalQueryResponse;
import com.titanium.metadata.errorcode.FeatureCenterErrorCode;
import com.titanium.metadata.response.ApiResponse;

import lombok.extern.slf4j.Slf4j;

/**
 * 取数端点的**入站适配器**：租户校验 → 交给 {@link AnalyticalQueryService} → 包成统一信封。
 *
 * <p>路径<b>逐字取自 {@link AnalyticalContract} 常量</b>，不写字面量：消费方
 * （feature-center 的 {@code AnalyticalDataAdapter}）按注册表 {@code analytical_path}
 * + {@code /query} 拼出站 URL，两侧各写一份字面量必然漂移，而漂移的后果是
 * <b>调用 404 但两侧代码各自看起来都对</b>。</p>
 *
 * <p>🔴 <b>本类不得被组件扫描发现，只能由自动装配注册为 Bean</b>：宿主域的
 * {@code @SpringBootApplication} 扫的是 {@code com.titanium.<domain>}，
 * 而本类在 {@code com.titanium.analytical} ——刻意不落在任何业务域的扫描范围内，
 * 是「starter 不污染宿主域」的一部分。注册点见 {@code AnalyticalAutoConfiguration}。</p>
 *
 * <h3>为何用控制器内 {@code @ExceptionHandler} 而不是 {@code @RestControllerAdvice}</h3>
 * <p>引入的 starter 若带一个全局 advice，会与宿主域自己的 advice 争夺同一异常的处理权，
 * 顺序取决于注册序（宿主域无法从自己的代码里看出这个竞争）。控制器内的处理器
 * <b>只作用于本控制器的两个端点</b>，边界清楚，不碰宿主域的异常处理版图。</p>
 *
 * <h3>失败一律以 HTTP 200 + 业务码应答（§8.2）</h3>
 * <p>消费方读的是 {@code ApiResponse} 信封里 8 位的业务码；HTTP 状态码只表达传输层语义
 * （路由不存在、服务不可达）。把业务失败塞进 HTTP 状态码，会让「路径配错了」与
 * 「对端清醒地答了业务错误」在下游同形——两者的处置方向相反。</p>
 */
@Slf4j
@RestController
@RequestMapping(AnalyticalContract.PATH)
public class AnalyticalQueryController {

    private final AnalyticalQueryService queryService;

    /**
     * 构造控制器。
     *
     * @param queryService 取数执行服务
     */
    public AnalyticalQueryController(AnalyticalQueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * 执行一次分析型取数。
     *
     * @param tenantId 租户标识（{@code X-Tenant-Id} 头；<b>缺失即拒绝</b>，
     *                 不放行成「查了但没有数据」——那与「该租户确实无数据」在响应上完全同形）
     * @param request  取数请求（结构化条件树，不存在字符串表达式字段，见 R-12）
     * @return 统一信封包裹的取数响应；失败时 {@code data} 为空、{@code code} 为业务错误码
     */
    @PostMapping(AnalyticalContract.QUERY_SUFFIX)
    public ApiResponse<AnalyticalQueryResponse> query(
            @RequestHeader(value = AnalyticalContract.TENANT_HEADER, required = false) String tenantId,
            @RequestBody AnalyticalQueryRequest request) {
        if (tenantId == null || tenantId.isBlank()) {
            // 失败关闭：缺租户时的取数会退化成跨租户全表扫描（结果偏大且无人察觉）
            return ApiResponse.error(FeatureCenterErrorCode.ANALYTICAL_TENANT_REQUIRED,
                    "取数请求缺少租户头 " + AnalyticalContract.TENANT_HEADER
                            + "：无租户则无法施加隔离过滤");
        }
        try {
            AnalyticalQueryResponse response = queryService.query(request, tenantId.trim());
            return ApiResponse.success(response);
        } catch (AnalyticalQueryException ex) {
            log.warn("[Analytical] 取数请求被拒: entityCode={}, requestId={}, code={}, reason={}",
                    request.entityCode(), request.requestId(), ex.errorCode().getCode(), ex.getMessage());
            return ApiResponse.error(ex.errorCode(), ex.getMessage());
        }
    }

    /**
     * 能力握手：如实回报本域提供取数的实体、各实体实际达到的一致性与执行来源。
     *
     * <p>供一致性对账（方案 §4.2 第三道校验 / R-14）与「该域到底接了哪些实体」的核查。
     * <b>不要求租户头</b>：本响应是部署能力快照（与租户无关），
     * 要求租户会把「对账」这件事绑死在租户上下文上，而核查方未必持有它。</p>
     *
     * @return 统一信封包裹的能力清单（无任何声明时为空列表，不是失败）
     */
    @GetMapping(AnalyticalContract.HANDSHAKE_SUFFIX)
    public ApiResponse<AnalyticalHandshakeResponse> handshake() {
        return ApiResponse.success(queryService.handshake());
    }

    /**
     * 请求体无法反序列化为契约类型：仍是**请求形状**问题，故与其它拒绝同走业务码。
     *
     * <p>常见成因是契约紧凑构造器拒绝了结构矛盾的载荷（如取数项别名为空、值个数与算子不符）——
     * 此时根因在调用方发出的报文上，而 Spring 默认的 400 + 框架错误体会让下游只看到
     * 「调用失败」，拿不到「哪个字段不对」。</p>
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ApiResponse<AnalyticalQueryResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        Throwable cause = ex.getMostSpecificCause();
        return ApiResponse.error(FeatureCenterErrorCode.ANALYTICAL_REQUEST_REJECTED,
                "取数请求体无法还原为契约类型：" + cause.getMessage()
                        + "——本契约的请求结构（取数项、条件树、算子白名单）在构造期即校验，"
                        + "故这属请求形状问题，不是服务端故障");
    }
}
