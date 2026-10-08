package com.titanium.analytical.query;

import java.util.regex.Pattern;

/**
 * SQL 标识符白名单：表名 / 列名的**唯一**合法形态。
 *
 * <p>本类存在的理由是一个结构事实：请求侧的值全部走参数占位符绑定，而<b>表名与列名不可能参数化</b>
 * ——它们只能作为文本拼进 SQL。于是「配置里的标识符」成了整条链路上唯一进入 SQL 文本的外部输入。
 * YAML 是开发资产（走代码评审），但「我们信任评审者不会写错」不是一个可断言的属性；
 * 白名单把这条通道收成「字母、数字、下划线，且不以数字开头」，
 * 使<b>引号、空格、分号、注释符在结构上不可能出现</b>，而不是靠人去发现。</p>
 *
 * <p>🔴 <b>校验时机是启动期（配置绑定后、服务可用前）而非每次查询</b>：非法标识符是**声明缺陷**，
 * 它不会因为时间或输入而变好；放到查询期校验，症状会退化成「某个特定请求报 SQL 语法错」，
 * 而错误方向指向数据库。故由 {@code AnalyticalQueryTranslator#validate} 在启动时一次性验完
 * （见 {@code AnalyticalAutoConfiguration}），失败即服务不进入可用状态。</p>
 */
public final class AnalyticalSqlIdentifiers {

    /** 合法标识符：首字符为字母或下划线，其后为字母、数字或下划线。 */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private AnalyticalSqlIdentifiers() {
    }

    /**
     * 判断是否为合法标识符。
     *
     * @param identifier 待判定的标识符（可空）
     * @return 合法返回 true
     */
    public static boolean isValid(String identifier) {
        return identifier != null && IDENTIFIER.matcher(identifier).matches();
    }

    /**
     * 断言标识符合法，否则抛出**启动期**异常。
     *
     * @param identifier 标识符（可空）
     * @param owner      声明归属（如实体码或实体声明），用于把错误定位到具体那一行 YAML
     * @param role       该标识符在声明中的角色（如 {@code table}、字段码）
     * @return 原样返回（便于链式使用）
     * @throws IllegalStateException 标识符非法（取数声明缺陷，服务不应进入可用状态）
     */
    public static String require(String identifier, String owner, String role) {
        if (!isValid(identifier)) {
            throw new IllegalStateException(
                    "取数声明不合法：" + owner + " 的 " + role + " = " + describe(identifier)
                            + " 不是合法 SQL 标识符（只允许 [A-Za-z_][A-Za-z0-9_]*，且不得以数字开头）。"
                            + "🔴 表名与列名不可能参数化、只能拼进 SQL 文本，故它们是全链路唯一"
                            + "进入 SQL 文本的外部输入——引号/空格/分号/注释符一旦放进来即注入面");
        }
        return identifier;
    }

    private static String describe(String identifier) {
        return identifier == null ? "null" : "\"" + identifier + "\"";
    }
}
