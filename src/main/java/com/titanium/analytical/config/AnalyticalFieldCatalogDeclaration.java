package com.titanium.analytical.config;

import java.time.LocalDate;

/**
 * 字段的**目录属性**（YAML 侧声明，对应 {@code t_<域>_field_catalog} 的目录列）。
 *
 * <p>这是「同一份 YAML 的两个消费面」中的第二个：取数执行读 {@link AnalyticalFieldDefinition}
 * 的执行属性（{@code column} / {@code type} / 四个能力位），而 {@code seed-mode=init} 时
 * 本 record 被写入目录表，供 G07 侧的字段目录消费。**两个面不写两份**（方案 §8.1 的减法）。</p>
 *
 * <p>🔴 <b>除两个可空列外全部必填</b>：目录表的这些列在建表 DDL 里是 {@code NOT NULL}，
 * 若在绑定层给默认值，缺字段就会静默产生一行「看起来合理」的目录数据
 * （{@code readable=false} / {@code sensitivity=null}），而它此后是**权威**——
 * 编造出来的目录比没有目录更危险（消费方会按它放行或拒绝）。故此处一律用包装类型 +
 * 绑定期校验：缺失即启动失败，报出「哪个实体、哪个字段、缺哪一项」。</p>
 *
 * <p>可空的两列与 DDL 一致：{@code change-type-code}、{@code deprecated-at}
 * （前者只有参与保全变更的字段才有，后者只在弃用后填）。</p>
 *
 * @param objectType       字段所属对象类型（域专属枚举的 code，如 {@code POLICY_HOLDER}）
 * @param valueType        字段值类型（域专属枚举的 code，如 {@code TEXT}/{@code DECIMAL}）
 * @param labelKey         国际化标签键（如 {@code policy.field.holder.name}）
 * @param collection       是否为集合字段
 * @param objectIdentityField 集合字段的元素标识列（非集合字段留空）
 * @param readable         是否可读
 * @param proposable       是否可申报
 * @param clearable        是否可清空
 * @param executionSupported 是否支持执行
 * @param requiresObjectId 是否必须携带对象标识
 * @param changeTypeCode   变更类型码（可空）
 * @param sensitivity      敏感级别（域专属枚举的 code，如 {@code SENSITIVE}）
 * @param maskingPolicy    掩码策略（域专属枚举的 code，如 {@code NAME}）
 * @param deprecatedAt     弃用日期（可空）
 */
public record AnalyticalFieldCatalogDeclaration(
        String objectType,
        String valueType,
        String labelKey,
        Boolean collection,
        String objectIdentityField,
        Boolean readable,
        Boolean proposable,
        Boolean clearable,
        Boolean executionSupported,
        Boolean requiresObjectId,
        String changeTypeCode,
        String sensitivity,
        String maskingPolicy,
        LocalDate deprecatedAt) {

    /**
     * 紧凑构造器：把目录表的 {@code NOT NULL} 列在**绑定期**逐项校验掉。
     *
     * <p>逐项检查而非只报第一项，是为了让一条启动错误就能把要补的字段一次说清——
     * 逐个报错会让配置者来回启动 N 次。</p>
     */
    public AnalyticalFieldCatalogDeclaration {
        StringBuilder missing = new StringBuilder();
        appendIfBlank(missing, objectType, "object-type");
        appendIfBlank(missing, valueType, "value-type");
        appendIfBlank(missing, labelKey, "label-key");
        appendIfNull(missing, collection, "collection");
        appendIfNull(missing, readable, "readable");
        appendIfNull(missing, proposable, "proposable");
        appendIfNull(missing, clearable, "clearable");
        appendIfNull(missing, executionSupported, "execution-supported");
        appendIfNull(missing, requiresObjectId, "requires-object-id");
        appendIfBlank(missing, sensitivity, "sensitivity");
        appendIfBlank(missing, maskingPolicy, "masking-policy");
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "字段目录声明缺少必填项：" + missing
                            + "（目录表对应列均为 NOT NULL；此处不设默认值——编造出来的目录会被当作权威）");
        }
    }

    private static void appendIfBlank(StringBuilder target, String value, String key) {
        if (value == null || value.isBlank()) {
            target.append(target.isEmpty() ? "" : "、").append(key);
        }
    }

    private static void appendIfNull(StringBuilder target, Boolean value, String key) {
        if (value == null) {
            target.append(target.isEmpty() ? "" : "、").append(key);
        }
    }
}
