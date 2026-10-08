package com.titanium.analytical.query;

import java.util.List;

import com.titanium.metadata.enums.FieldDataType;

/**
 * 结构化取数请求**编译**后的可执行形态：一条参数化 SQL + 结果列的取值计划。
 *
 * <p>它是「AST → SQL」这一步的<b>唯一产物</b>，也是「值不参与 SQL 文本」这条纪律的载体：
 * {@link #sql()} 里只有问号占位符与来自声明白名单的标识符，请求侧的值一律在
 * {@link #parameters()} 里按序号绑定。</p>
 *
 * <p>🔴 <b>{@link #valuePlans()} 承载「结果集第 i 列是什么」，且 {@link ValuePlan#alias()}
 * 有意**不**拼进 SQL</b>：SQL 里没有 {@code AS} 子句，结果按**列序号**读取。
 * 原因是别名是调用方自由文本（本仓先例：feature-center 的别名即 {@code featureCode}，
 * 而 featureCode 按 §5.3 的「主体实体码.特征名」口径<b>含点</b>），
 * 一旦拼进 SQL 就必须先回答「哪些字符算合法别名」——那是个会拒掉真实请求的猜谜。
 * 把别名留在 Java 侧按序号配对，让「别名」这一个自由度<b>在结构上不进入 SQL</b>，
 * 比给它定一套字符规则更彻底：没有规则需要猜，就没有规则会猜错。</p>
 *
 * @param sql         参数化查询文本（非空；仅含问号占位符与白名单标识符）
 * @param parameters  绑定值（按出现顺序；个数须与 {@link #sql()} 中的问号数一致）
 * @param valuePlans  结果列取值计划（非空；按 SELECT 列表的**列序号**一一对应）
 * @param nullGuards  空值度量守卫（聚合模式下非空；明细模式为空列表）——
 *                    SQL 的 {@code SUM}/{@code AVG}/{@code MIN}/{@code MAX} 会**忽略 NULL 行**，
 *                    而基准实现对该情形直接失败，两者对同一份数据会给出两个"成功"的不同数值。
 *                    故聚合查询额外带探测列，由执行侧比对后显式失败，见 {@link NullGuard}
 * @param detailMode  是否为明细模式（{@code true} 表示未聚合，SQL 已加两行探测上限，
 *                    用于把「多行」这件事变成可观测并显式拒绝，见 {@code AnalyticalQueryService}）
 */
public record TranslatedQuery(
        String sql,
        List<Object> parameters,
        List<ValuePlan> valuePlans,
        List<NullGuard> nullGuards,
        boolean detailMode) {

    /**
     * 紧凑构造器：三项结构性事实缺一即无法执行或无法归位。
     */
    public TranslatedQuery {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("翻译结果必须带查询文本(sql)");
        }
        if (valuePlans == null || valuePlans.isEmpty()) {
            throw new IllegalArgumentException(
                    "翻译结果必须带取值计划(valuePlans)——取数请求至少 1 个取数项，"
                            + "无计划则结果集无法归位");
        }
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
        valuePlans = List.copyOf(valuePlans);
        nullGuards = nullGuards == null ? List.of() : List.copyOf(nullGuards);
    }

    /**
     * 供日志使用的可读描述。
     *
     * <p>🔴 <b>只报参数个数、不报参数值</b>：筛选条件的取值可能承载证件号、账号等个人信息
     * （与 {@code AnalyticalFieldCatalogDeclaration} 里 {@code SENSITIVE} 级别同源），
     * 落进日志即扩散。需要定位具体值时，由调用方在自己的调试入口按需打印。</p>
     *
     * @return 形如 {@code SELECT ... WHERE a = ? [参数 1 个 / 明细模式]}
     */
    public String describe() {
        return sql + " [参数 " + parameters.size() + " 个 / " + (detailMode ? "明细" : "聚合") + "模式]";
    }

    /**
     * 结果列的取值计划：第 i 列叫什么名、该按什么类型规范化。
     *
     * @param alias 结果键名（非空；与请求侧 {@code AnalyticalFieldSelection.alias()} 逐字相等）
     * @param type  该列的声明取值类型（非空；决定出参规范化口径，如日期→ISO 字符串）
     */
    public record ValuePlan(String alias, FieldDataType type) {

        /**
         * 紧凑构造器：别名是请求与响应的配对键，类型是出参规范化的唯一依据。
         */
        public ValuePlan {
            if (alias == null || alias.isBlank()) {
                throw new IllegalArgumentException("结果列的别名(alias)不能为空");
            }
            if (type == null) {
                throw new IllegalArgumentException(
                        "结果列 " + alias + " 的取值类型(type)不能为空——出参须按它规范化，"
                                + "缺失会让「日期列序列化成数组还是字符串」变成运行期猜");
            }
        }
    }

    /**
     * 一个聚合度量列的**空值守卫**：SQL 静默忽略 NULL 行这件事的探测器。
     *
     * <p>为什么需要它（这是本模块唯一一处「比 SQL 更严」的地方，不是防御性冗余）：
     * SQL 的 {@code SUM}/{@code AVG}/{@code MIN}/{@code MAX} <b>跳过 NULL 行</b>，
     * 而 L3 侧的基准实现（{@code FeatureAggregationService}）遇到 NULL 直接失败。
     * 于是「该字段有明细取不到值」这个事实，在两个档位上得到的结果是
     * <b>两个都"成功"返回、数值却不同</b>——不会报错、不会留痕，
     * 下游只会看到一个偏小的合计。故 SQL 里额外 SELECT 两个计数列：
     * 本组行数 {@code COUNT(*)} 与度量列非空计数 {@code COUNT(<度量列>)}，
     * 二者不等即说明有 NULL 行被 SQL 抹掉，执行侧据此显式失败。</p>
     *
     * <p>🔴 {@code COUNT(*)} 的探测列只加一次、供本组所有度量列共用（行数与本组相同）；
     * {@code MIN}/{@code MAX} 同样需要守卫——它们虽不像 SUM 那样累加，
     * 但 NULL 会改变「最小值取到哪个值」的判定基准，同一份数据在两个档位同样会分叉。</p>
     *
     * @param fieldRef        度量字段引用（仅用于失败消息定位，形如 {@code policy.sumInsured}）
     * @param nonNullCountIndex 该度量列非空计数的**列序号**（0 基）
     * @param rowCountIndex   本组行数的**列序号**（0 基；同一查询内所有守卫共用同一列）
     */
    public record NullGuard(String fieldRef, int nonNullCountIndex, int rowCountIndex) {

        /**
         * 紧凑构造器：两个列序号是探测结果的取数依据，指错列即守卫恒真（永远查不出 NULL）。
         */
        public NullGuard {
            if (fieldRef == null || fieldRef.isBlank()) {
                throw new IllegalArgumentException("空值守卫的字段引用(fieldRef)不能为空——失败时须能定位是哪个度量列");
            }
            if (nonNullCountIndex < 0 || rowCountIndex < 0) {
                throw new IllegalArgumentException(
                        "空值守卫的列序号不能为负（非空计数 " + nonNullCountIndex
                                + " / 行数 " + rowCountIndex + "）：负序号取值会落到别的列上，"
                                + "守卫恒真即等于没有守卫");
            }
        }
    }
}
