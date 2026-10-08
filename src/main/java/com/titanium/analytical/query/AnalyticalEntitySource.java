package com.titanium.analytical.query;

import com.titanium.analytical.AnalyticalConsistency;
import com.titanium.metadata.analytical.AnalyticalQueryRequest;
import com.titanium.metadata.analytical.AnalyticalQueryResponse;

/**
 * 取数**逃生舱**：属主域自写的取数实现（架构方案 R-2 第二道防线「走 Bean」）。
 *
 * <p>适用场景：某实体的过滤逻辑通用执行器表达不了（多表关联、按业务规则拼条件、
 * 需要查另一张表才能定位列）。属主域写一个 {@code @Component} 实现本接口即可接管该实体的取数，
 * <b>不必</b>把这类特例塞进通用执行器的 YAML 里。</p>
 *
 * <p>🔴 <b>走 Bean 意味着自行承担防线</b>：通用执行器强制注入的两道过滤
 * （{@code tenant_id} 等值 + 删除标记）在 Bean 路径上<b>无法由 starter 代劳</b>——
 * 它拿到的是一个 SQL 由自己写的世界。故本接口把租户作为<b>显式入参</b>交给实现者，
 * 并对实现者提出与通用执行器同等的义务：</p>
 * <ul>
 *   <li><b>必须</b>按 {@code tenantId} 过滤，不得返回其它租户的数据；</li>
 *   <li><b>必须</b>排除已删除数据（该表有删除标记列时）；</li>
 *   <li><b>必须</b>自行保证 SQL 参数化，不得把请求侧任何值拼进 SQL 文本。</li>
 * </ul>
 * <p>这三条无法由 starter 机械校验（正是「逃生舱」的固有代价：离开通用执行器就不再受通用防线约束），
 * 故登记在模块 {@code README} 的「已知边界」一节，作为引入方必须自证的部分。</p>
 *
 * <p>🔴 <b>本接口只在 starter 内定义、由使用时由属主域实现</b>：接口本身不依赖任何业务域类型
 * （只用 metadata 的中立契约），故不破坏「starter 不得依赖任何业务域」这条纪律。</p>
 */
public interface AnalyticalEntitySource {

    /**
     * 本实现负责的实体码。
     *
     * <p>与 YAML 声明的实体码<b>不得重复</b>：重复即启动失败（两种执行路径都声称负责同一实体，
     * 谁生效取决于遍历顺序 ⇒ 换一次启动顺序就换一个取值口径，且两边都"看起来正常"）。</p>
     *
     * @return 实体码（非空）
     */
    String entityCode();

    /**
     * 本实现**实际**达到的一致性（如实声明，供握手对账）。
     *
     * @return 一致性等级
     */
    AnalyticalConsistency consistency();

    /**
     * 执行一次取数。
     *
     * @param request  取数请求（非空；已通过契约构造期的结构校验）
     * @param tenantId 租户标识（非空；由 starter 从请求头取到并强制非空后传入）
     * @return 取数响应（非空；{@code entityCode} 须与请求一致）
     */
    AnalyticalQueryResponse query(AnalyticalQueryRequest request, String tenantId);
}
