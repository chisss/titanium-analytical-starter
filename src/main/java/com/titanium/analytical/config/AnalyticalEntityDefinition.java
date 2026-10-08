package com.titanium.analytical.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.titanium.analytical.AnalyticalConsistency;

/**
 * 一个**可取数实体**的完整声明（YAML 侧）。
 *
 * <p>声明的每一行都会被 starter 变成真实 SQL 的一部分，故这里的字段分两类：
 * <b>可信标识符</b>（{@code table}/{@code subject-column}/{@code deleted-column}/各字段列名）
 * ——它们只来自配置（开发资产，走代码评审），永不接受请求侧输入；以及
 * <b>能力位与语义</b>（{@code consistency}/{@code allow-cross-subject}/目录属性）。</p>
 *
 * <p>🔴 <b>三个值不给默认（缺即启动失败）</b>：{@code table}、{@code subject-column}、
 * {@code consistency}。前两者缺了会退化成「对着一张猜出来的表取数」，而第三个是
 * R-14 一致性对账的<b>声明侧输入</b>——给它一个默认值（比如 {@code EVENTUAL}）会让
 * 「属主域忘了声明」与「属主域声明了 EVENTUAL」在握手响应上完全同形，
 * 而这两件事的处理方式相反（一个是配置错误、一个是正确声明）。</p>
 *
 * <p>🔴 <b>{@code deleted-column} 的哨兵值不是「关掉过滤」的开关</b>：写
 * {@code none} 的含义是「这张表<b>本来就没有</b>删除标记列」（如 CQRS 读模型表），
 * starter 会先用 JDBC 元数据核验该表确实没有删除标记列，核验不过即拒绝服务
 * （见 {@code AnalyticalTableSchemaGuard}）。故「由 YAML 关闭租户/删除过滤」在结构上
 * 不成立——{@code none} 只能陈述事实，不能改变过滤行为（租户过滤恒开启、无开关）。</p>
 *
 * @param table             物理表名或分析视图名（必填；建议 {@code v_analytical_*} 专用视图）
 * @param subjectColumn     主体列（必填；{@code subjectId} 非空时按它等值过滤）
 * @param allowCrossSubject 是否允许跨主体聚合（{@code subjectId} 为空）；缺省 false
 * @param deletedColumn     删除标记列；缺省 {@value #DEFAULT_DELETED_COLUMN}，
 *                          写 {@value #NO_DELETED_COLUMN} 表示该表确无此列
 * @param consistency       本实体取数实际达到的一致性（必填，如实声明）
 * @param effectiveFromColumn 时点回溯的生效起列（可空；两者都给才具备 {@code temporal} 能力）
 * @param effectiveToColumn   时点回溯的生效止列（可空）
 * @param catalogTable      字段目录表名（可空；仅 {@code seed-mode=init} 需要）
 * @param fields            字段声明（必填且至少 1 项，字段码唯一）
 */
public record AnalyticalEntityDefinition(
        String table,
        String subjectColumn,
        Boolean allowCrossSubject,
        String deletedColumn,
        AnalyticalConsistency consistency,
        String effectiveFromColumn,
        String effectiveToColumn,
        String catalogTable,
        List<AnalyticalFieldDefinition> fields) {

    /** 删除标记列的默认列名（状态存储表/专用分析视图的惯例）。 */
    public static final String DEFAULT_DELETED_COLUMN = "is_deleted";

    /** 「该表确无删除标记列」的哨兵值（须经 JDBC 元数据核验，核验不过即拒绝服务）。 */
    public static final String NO_DELETED_COLUMN = "none";

    /**
     * 紧凑构造器：校验必填项、规范化缺省值、把字段列表转成按字段码索引的视图。
     *
     * <p>逐项校验而非只报第一项：一条启动错误把要补的项一次说清，避免配置者来回启动 N 次。</p>
     */
    public AnalyticalEntityDefinition {
        List<String> missing = new ArrayList<>();
        if (table == null || table.isBlank()) {
            missing.add("table");
        }
        if (subjectColumn == null || subjectColumn.isBlank()) {
            missing.add("subject-column");
        }
        if (consistency == null) {
            missing.add("consistency");
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "取数实体声明缺少必填项：" + String.join("、", missing)
                            + "（三者都不给默认值：前两者缺了会对着猜出来的表取数，"
                            + "consistency 缺了会让「忘了声明」与「声明了 EVENTUAL」在握手响应上同形）");
        }
        if (fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException("取数实体 " + table + " 未声明任何字段(fields)");
        }

        Map<String, AnalyticalFieldDefinition> indexed = new LinkedHashMap<>();
        for (AnalyticalFieldDefinition field : fields) {
            if (field == null) {
                throw new IllegalArgumentException("取数实体 " + table + " 的字段声明不得含 null 元素");
            }
            if (indexed.putIfAbsent(field.code(), field) != null) {
                throw new IllegalArgumentException(
                        "取数实体 " + table + " 存在重复的字段码：" + field.code()
                                + "——重复码会让「取哪个列」退化为后者覆盖前者（不报错）");
            }
        }

        allowCrossSubject = allowCrossSubject != null && allowCrossSubject;
        deletedColumn = (deletedColumn == null || deletedColumn.isBlank())
                ? DEFAULT_DELETED_COLUMN
                : deletedColumn.trim();
        table = table.trim();
        subjectColumn = subjectColumn.trim();
        effectiveFromColumn = normalize(effectiveFromColumn);
        effectiveToColumn = normalize(effectiveToColumn);
        catalogTable = normalize(catalogTable);
        fields = List.copyOf(fields);
    }

    private static String normalize(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    /**
     * 字段码 → 字段声明的索引（保序）。
     *
     * <p>用 {@code unmodifiableMap} 而非 {@code Map.copyOf}：后者不保证迭代顺序，
     * 而「目录导入的插入顺序」「错误消息里字段的列举顺序」都依赖声明顺序稳定。</p>
     *
     * @return 字段码到声明的映射
     */
    public Map<String, AnalyticalFieldDefinition> fieldsByCode() {
        Map<String, AnalyticalFieldDefinition> indexed = new LinkedHashMap<>();
        for (AnalyticalFieldDefinition field : fields) {
            indexed.put(field.code(), field);
        }
        return Collections.unmodifiableMap(indexed);
    }

    /**
     * 该实体的取数是否**不注入删除标记过滤**。
     *
     * <p>注意：为 true 不等于「过滤被关掉」——它只表示「这张表没有可过滤的删除标记列」，
     * 是否可信由 {@code AnalyticalTableSchemaGuard} 用 JDBC 元数据核验。</p>
     *
     * @return 声明为无删除标记列时返回 true
     */
    public boolean declaresNoDeletedColumn() {
        return NO_DELETED_COLUMN.equalsIgnoreCase(deletedColumn);
    }

    /**
     * 是否具备时点回溯能力（{@code temporal}）。
     *
     * @return 起止列都给全时返回 true
     */
    public boolean isTemporal() {
        return effectiveFromColumn != null && effectiveToColumn != null;
    }

    /**
     * 声明为时点实体但起止列只给了一半时返回缺失说明，供启动期失败关闭。
     *
     * @return 缺失的列名（两者都给或都没给时返回 null）
     */
    public String describeTemporalGap() {
        if (effectiveFromColumn == null && effectiveToColumn == null) {
            return null;
        }
        if (effectiveFromColumn == null) {
            return "effective-from-column";
        }
        if (effectiveToColumn == null) {
            return "effective-to-column";
        }
        return null;
    }

    /**
     * 统计带目录声明的字段个数（{@code seed-mode=init} 的写入对象）。
     *
     * @return 带目录声明的字段数
     */
    public long catalogBearingFieldCount() {
        return fields.stream().filter(field -> field.catalog() != null).count();
    }
}
