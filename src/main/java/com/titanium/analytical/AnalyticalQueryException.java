package com.titanium.analytical;

import com.titanium.metadata.errorcode.BaseErrorCode;

/**
 * 取数链路的**失败关闭异常**（provider 端）。
 *
 * <p>携带 {@link BaseErrorCode} 而非裸字符串码（根规约红线 19）：调用方（feature-center）
 * 按码分流，文案由错误码枚举承载。</p>
 *
 * <p>🔴 <b>为何 starter 不直接用 {@code BusinessException}</b>：后者在 {@code titanium-common}，
 * 而本模块的依赖白名单只有 {@code spring-*} 与 {@code titanium-metadata}——把 {@code titanium-common}
 * 拉进来会让 starter 反向携带 JPA/安全/数据源等一整条重依赖链给每个引入它的业务域，
 * 且「不依赖任何业务域/内核」这条依赖纪律（架构方案 §6.5 / I-2）会从第一行代码起就失守。
 * 于是本模块自持一个异常类型，由 {@code AnalyticalQueryController} 就地转成
 * {@code ApiResponse.error(code, message)}（HTTP 200 + 业务码，与全仓跨域契约同形）。</p>
 *
 * <p>🔴 <b>本异常只用于「拒绝请求」，不用于「取数执行失败」</b>：执行失败（SQL 报错、超时）
 * 是基础设施故障，语义不同——它由 {@code AnalyticalQueryService} 包装为
 * {@code ANALYTICAL_QUERY_FAILED}（67000012）后抛出，两者的区别是「这请求不该被受理」
 * 与「本该受理但环境坏了」，混为一谈会让运维在被告知「请求非法」时去查客户端。</p>
 */
public class AnalyticalQueryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 错误码（非空）。 */
    private final transient BaseErrorCode errorCode;

    /**
     * 构造异常。
     *
     * @param errorCode 错误码（非空）
     * @param message   补充说明（可空；调用方展示以错误码文案为准）
     */
    public AnalyticalQueryException(BaseErrorCode errorCode, String message) {
        super(message);
        if (errorCode == null) {
            throw new IllegalArgumentException("取数异常必须携带错误码（禁止裸字符串码）");
        }
        this.errorCode = errorCode;
    }

    /**
     * 取得错误码。
     *
     * @return 错误码
     */
    public BaseErrorCode errorCode() {
        return errorCode;
    }
}
