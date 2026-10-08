package com.titanium.analytical.config;

/**
 * 字段目录种子导入模式（{@code titanium.analytical.seed-mode}）。
 *
 * <p>🔴 <b>默认 {@link #NONE}</b>：不写这项配置就**不碰数据库**。
 * 导入是**一次性**动作（INIT-ONLY 语义），默认开启会让每次启动都写库，
 * 而「每次都写」与「幂等」在日志上不可区分，运维无从判断表到底是谁写的。</p>
 *
 * <p>🔴 <b>刻意不叫 {@code OFF}/{@code ON}</b>：{@code off} / {@code on} / {@code yes} / {@code no}
 * 是 YAML 1.1 的布尔字面量，某些解析路径会把它们变成 {@code Boolean} 而不是字符串，
 * 于是「配置写了但没生效」与「配置没写」在运行期同形。取值用 {@code none} / {@code init}
 * 两个不在布尔字面量表里的词，把这个不确定性从源头去掉（改配置的人不必知道解析器是哪一个）。</p>
 */
public enum SeedMode {

    /** 不导入：只声明取数能力，不写字段目录表（表由既有迁移或他人负责）。 */
    NONE,

    /**
     * 一次性导入：把 YAML 里的字段声明写入 {@code catalog-table} 指定的目录表。
     *
     * <p><b>INIT-ONLY 语义</b>：表非空即整体跳过、不比对、不更新——此后<b>表为权威</b>，
     * YAML 只是它的源头。故本模式<b>不是</b>「每次启动同步一次」，
     * 改 YAML 不会回写已灌种子的库（这正是要防的：让运营改过的目录被启动时静默覆盖）。</p>
     */
    INIT
}
