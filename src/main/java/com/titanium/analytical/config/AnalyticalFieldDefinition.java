package com.titanium.analytical.config;

import com.titanium.metadata.enums.FieldDataType;

/**
 * 单个字段的**取数执行声明**（YAML 侧）。
 *
 * <p>三组信息：① 字段码 {@code code} 与物理落点 {@code column}；② 分析取值类型 {@code type}
 * （决定值规范化口径，是「按目标字段类型规范化」的唯一依据）；③ 四个能力位，与 G07 目录的
 * {@code FieldAnalysisCapability} 四项**逐项同名同义**——同一份 YAML 在
 * {@code seed-mode=init} 时被写进目录表的 {@code analyzable/aggregatable/groupable/filterable}
 * 四列，故此处不再另起一套命名。</p>
 *
 * <p>🔴 <b>为何字段用「列表 + 显式 {@code code}」而不是以字段码为键的 Map</b>：
 * 字段码含点（{@code holder.name}，与目录表 {@code field_code} 的中间段同形）。
 * Spring 绑定 {@code Map<String, T>} 时需要自行判断「路径的哪一段是键、哪一段是属性」，
 * 键里含点时该判断退化为启发式——猜错的症状是<b>静默少绑几个字段</b>
 * （列表变短、不报错），而「少一个字段」与「那段 YAML 本来没写」在运行期完全同形。
 * 列表 + 显式 {@code code} 把这个不确定性从源头去掉，重复码则由
 * {@link AnalyticalEntityDefinition} 在绑定期显式拒绝。</p>
 *
 * <p>🔴 <b>{@code catalog} 可空，且这不是疏漏</b>：目录面（保全可申报/可清空/敏感级别/掩码策略）
 * 与执行面（可聚合/可筛选）是**两个不同的问题**，字段集只是重合而非相等。
 * 若强行要求每个可取数列都写一份目录声明，配置者就必须为 {@code premium}/{@code issue_time}
 * 这类派生列<b>编造</b>保全语义（谁能申报它？变更类型码是什么？），而
 * {@code seed-mode=init} 会把这些编造值写进**权威**目录表——
 * 这正是 {@link AnalyticalFieldCatalogDeclaration} 反复申明要防的事。
 * 故：可空；带声明者必须写全（14 列 NOT NULL 逐项校验），不带声明者不参与目录导入。</p>
 *
 * <p>🔴 <b>刻意不在这里复制 G07 目录的既有规则（如「能分组必能过滤」）</b>：
 * 那是目录侧的权威不变量，复制一份就等于造出第二套实现，两边迟早不一致；
 * 本 record 只保证「声明自洽可读」，跨字段规则由目录侧与消费方守。</p>
 *
 * @param code       字段码（必填；<b>不含实体码前缀</b>，如 {@code holder.name}，
 *                   与实体码拼成 {@code policy.holder.name} = 目录表 {@code field_code}）
 * @param column     物理列名（必填；只作为标识符拼接进 SQL 的**白名单**部分，永不接受请求侧输入）
 * @param type       分析取值类型（必填；请求侧的值按此类型规范化后才绑定参数）
 * @param analyzable 可被取数项选取
 * @param aggregatable 可参与聚合（SUM/COUNT/AVG/MIN/MAX）
 * @param groupable  可作分组维度（当前契约拒绝 groupBy，保留该位以与 G07 目录对齐）
 * @param filterable 可出现在筛选条件中
 * @param catalog    目录属性（**可空**；给了就必须写全，见类注释）
 */
public record AnalyticalFieldDefinition(
        String code,
        String column,
        FieldDataType type,
        boolean analyzable,
        boolean aggregatable,
        boolean groupable,
        boolean filterable,
        AnalyticalFieldCatalogDeclaration catalog) {

    /**
     * 紧凑构造器：校验字段码、物理列与类型——它们是「参数化 SQL 的列名白名单」的构成元素。
     */
    public AnalyticalFieldDefinition {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("字段码(code)不能为空");
        }
        if (code.contains(".") == false) {
            // 不含点的字段码与实体码拼接后仍成立（如 policy.status），此处不拒绝；
            // 仅挡住会被拆错的前导/尾随点。
            if (code.startsWith(".") || code.endsWith(".")) {
                throw new IllegalArgumentException(
                        "字段码(code)不得以点开头或结尾：" + code
                                + "——字段引用按「第一个点」切成「实体码 + 字段码」，点在前/后会把字段码切成空段");
            }
        }
        if (column == null || column.isBlank()) {
            throw new IllegalArgumentException("字段 " + code + " 的物理列名(column)不能为空");
        }
        if (type == null) {
            throw new IllegalArgumentException(
                    "字段 " + code + " 的取值类型(type)不能为空——请求侧的值必须按它规范化后才绑定参数，"
                            + "缺失会让「按什么类型解析这个值」变成运行期猜");
        }
    }

    /**
     * 归一化列名（供 SQL 拼接与元数据校验共用）。
     *
     * @return 去空白后的列名
     */
    public String normalizedColumn() {
        return column.trim();
    }

    /**
     * 该字段是否携带目录声明（决定它是否参与 {@code seed-mode=init} 的目录导入）。
     *
     * @return 携带目录声明返回 true
     */
    public boolean hasCatalogDeclaration() {
        return catalog != null;
    }
}
