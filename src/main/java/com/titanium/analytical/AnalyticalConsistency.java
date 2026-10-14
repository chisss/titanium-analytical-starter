package com.titanium.analytical;

import com.titanium.metadata.enums.Reserved;

/**
 * 属主域取数**实际达到的一致性等级**（provider 端声明，G08/g08-06）。
 *
 * <p>取值与 {@code t_entity_registry.consistency} 以及 feature-center 的
 * {@code EntityConsistency} **逐字同口径**（{@code STRONG} / {@code EVENTUAL}）——
 * handshake 端点如实回报本枚举，供消费方与注册表声明做运行期对账（方案 §9 R-14）。</p>
 *
 * <p>🔴 <b>本枚举是 starter 自持的一份，不 import feature-center 的 {@code EntityConsistency}</b>：
 * 后者在 {@code titanium-feature-center-common}，而 starter 一旦依赖它，
 * 「属主域 → starter → 元数据中心」这条依赖方向就变成了「属主域 → feature-center」，
 * 把「单实例 Port、属主域零耦合」这个前提破掉（方案 §6.5 / I-2）。
 * 两份声明的一致性不靠 import 保证，靠 <b>字面量互校判据</b>保证
 * （验收脚本比对本枚举名 / {@code EntityConsistency} 常量名 / 注册表种子取值三处）。</p>
 *
 * <p>🔴 <b>声明与实现不符是本枚举存在的唯一理由</b>：某域在注册表里写 {@code STRONG}
 * 而实际读的是 CQRS 读模型（投影有延迟），静态代码查不出真假——只有 handshake 如实回报
 * 才能对账。故 YAML 里的取值必须由**实际读的那张表**决定，不得凭愿望填。</p>
 */
public enum AnalyticalConsistency {

    /** 强一致：读的是写模型当前状态（状态表 / 事件重建），不存在投影可见延迟。 */
    @Reserved("2026-10-14 g16-05 零引用登记（未见消费）：待业务核对——接入触发链路或删除；核毕摘标注+删清单行。见 docs/技术文档/枚举零引用清单.md")
    STRONG,

    /** 最终一致：读的是 CQRS 读模型 / 投影表，存在可见延迟（本仓绝大多数分析视图属此类）。 */
    EVENTUAL;

    /**
     * 按名称解析（大小写不敏感），用于 YAML 绑定时的失败关闭校验。
     *
     * <p>🔴 <b>绑定期就拒绝枚举外取值</b>：本枚举是<b>本域自己的声明</b>，
     * 拼错（如 {@code EVENTUALLY}）必须在启动时炸，而不是等到 handshake 才被消费方发现。
     * 注意与 {@code AnalyticalQueryResponse.consistency} 的分工——那一个是 String、
     * <b>有意不做枚举校验</b>（属主域回报枚举外取值这件事本身要能被发现，而不是变成反序列化失败）。</p>
     *
     * @param name 一致性名称（{@code STRONG} / {@code EVENTUAL}）
     * @return 匹配的常量
     * @throws IllegalArgumentException 取值为空或不在枚举内
     */
    public static AnalyticalConsistency fromName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("一致性等级(consistency)不能为空，取值须为 STRONG 或 EVENTUAL");
        }
        for (AnalyticalConsistency candidate : values()) {
            if (candidate.name().equalsIgnoreCase(name.trim())) {
                return candidate;
            }
        }
        throw new IllegalArgumentException(
                "一致性等级取值非法：" + name + "，取值须为 STRONG 或 EVENTUAL（与 t_entity_registry.consistency 同口径）");
    }
}
