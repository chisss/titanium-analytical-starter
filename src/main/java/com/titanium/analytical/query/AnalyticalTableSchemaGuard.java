package com.titanium.analytical.query;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import com.titanium.analytical.AnalyticalQueryException;
import com.titanium.analytical.config.AnalyticalEntityDefinition;
import com.titanium.analytical.config.AnalyticalFieldDefinition;
import com.titanium.metadata.errorcode.FeatureCenterErrorCode;

/**
 * 取数声明的**运行期表结构守卫**：把 YAML 里写的每一个标识符拿到数据库元数据里逐一对账。
 *
 * <p>为什么必须有这一层（这是 {@code deleted-column: none} 这个哨兵值能成立的前提）：
 * 「该表没有删除标记列」在 YAML 里只能是一句<b>陈述</b>，而陈述可能是错的、也可能是过期的
 * （表后加了 {@code is_deleted} 而没人改配置）。若不核验，错的那一侧会静默生效成
 * 「不过滤已删除数据」——<b>取出来的数据"看起来正常"，只是把已删除的保单也算进了聚合</b>，
 * 这类缺陷不会报错、不会留痕，只会让核保/理赔依据一个偏大的值。</p>
 *
 * <p>🔴 <b>核验失败即拒绝服务</b>（{@code ANALYTICAL_QUERY_FAILED}），且**不降级**：
 * 不因为「列不存在」就把那个过滤条件去掉——少一个过滤条件会把「查这个主体的」变成
 * 「查所有主体的」，与串值缺陷同族。缺失即拒绝，让声明去改对。</p>
 *
 * <p>🔴 <b>核验是启动期一次性的</b>（由 {@code AnalyticalAutoConfiguration} 在应用就绪时调用，
 * 失败即不让服务进入可用状态）：声明与表结构的矛盾是<b>确定性的配置缺陷</b>，
 * 它不会因为时间和输入而变好。留到查询期暴露的话，症状是「某个请求报 SQL 错」，
 * 而错误方向指向数据库——排障的人会去查表，而真正要改的是 YAML。</p>
 *
 * <p>核验结果按实体码缓存（{@link #verify}），故查询期的调用只是查一次 Map。</p>
 */
public class AnalyticalTableSchemaGuard {

    /**
     * 租户过滤列名。
     *
     * <p>🔴 <b>刻意不可配置</b>：项目规约要求所有业务表必含 {@code tenant_id}（多租户隔离的
     * 结构前提），而「租户过滤恒开启」这条防线一旦开口成 YAML 开关，
     * 就等于把「关掉租户隔离」变成一次改配置。故它是常量，只能被核验、不能被替换。</p>
     */
    public static final String TENANT_COLUMN = "tenant_id";

    /** 声明「本表无删除标记列」时，用于反查该声明是否属实的候选列名（命中任一即声明不实）。 */
    private static final List<String> DELETION_CANDIDATES =
            List.of("is_deleted", "deleted", "deleted_at", "del_flag");

    /** 删除标记列的可接受类型（数值型，用 {@code = 0} 过滤）。 */
    private static final Set<Integer> NUMERIC_DELETION_TYPES =
            Set.of(Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT);

    /** 删除标记列的可接受类型（布尔型，用 {@code = FALSE} 过滤）。 */
    private static final Set<Integer> BOOLEAN_DELETION_TYPES = Set.of(Types.BOOLEAN, Types.BIT);

    private final DataSource dataSource;

    /** 实体码 → 已核验的删除标记列风格（{@link #verify} 的缓存）。 */
    private final Map<String, DeletedColumnStyle> verified = new ConcurrentHashMap<>();

    /**
     * 构造守卫。
     *
     * @param dataSource 本域数据源（用于读 JDBC 元数据）
     */
    public AnalyticalTableSchemaGuard(DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException(
                    "表结构守卫需要数据源(dataSource)——没有它就无法核验 deleted-column: none 这类声明");
        }
        this.dataSource = dataSource;
    }

    /**
     * 删除标记列的**实际**形态（核验后），决定 SQL 里那一行过滤怎么写。
     */
    public enum DeletedColumnStyle {
        /** 该表确无删除标记列（YAML 的 {@code none} 声明已由元数据证实）。 */
        NONE_VERIFIED,
        /** 数值型删除标记列，过滤式 {@code <column> = 0}。 */
        NUMERIC_COLUMN,
        /** 布尔型删除标记列，过滤式 {@code <column> = FALSE}。 */
        BOOLEAN_COLUMN
    }

    /**
     * 核验一个实体的全部声明标识符（首次调用读元数据，其后走缓存）。
     *
     * @param entityCode 实体码（缓存键与错误定位）
     * @param entity     实体声明
     * @return 该实体删除标记列的实际形态
     * @throws AnalyticalQueryException 声明与表结构不符（表不存在、列不存在、删除标记类型不可用、
     *                                  {@code none} 声明与事实相反）
     */
    public DeletedColumnStyle verify(String entityCode, AnalyticalEntityDefinition entity) {
        DeletedColumnStyle cached = verified.get(entityCode);
        if (cached != null) {
            return cached;
        }
        DeletedColumnStyle resolved = load(entityCode, entity);
        verified.put(entityCode, resolved);
        return resolved;
    }

    /**
     * 读取一张表的**全部列名**（小写），供本模块内其它元数据对账复用（如目录导入前的表结构确认）。
     *
     * <p>与 {@link #verify} 的区别：本方法不带缓存、不校验语义，只回答「这张表现在有哪些列」。
     * 表不存在即抛（不返回空集合——空集合会让调用方把「表没了」与「表是空的」混为一谈）。</p>
     *
     * @param table 表名（YAML 声明值）
     * @param owner 调用方标识（仅用于错误定位，如「实体 policy 的目录表」）
     * @return 列名集合（小写）
     * @throws AnalyticalQueryException 读元数据失败或该表不存在/当前连接不可见
     */
    public Set<String> columnNames(String table, String owner) {
        try (Connection connection = dataSource.getConnection()) {
            return Set.copyOf(columnsOf(connection, owner, table).keySet());
        } catch (SQLException ex) {
            throw new AnalyticalQueryException(FeatureCenterErrorCode.ANALYTICAL_QUERY_FAILED,
                    "读取表结构元数据失败（" + owner + "，表 " + table + "）：" + ex.getMessage());
        }
    }

    private DeletedColumnStyle load(String entityCode, AnalyticalEntityDefinition entity) {
        try (Connection connection = dataSource.getConnection()) {
            Map<String, Integer> columns = columnsOf(connection, "实体 " + entityCode, entity.table());
            requireColumn(entityCode, entity.table(), columns, TENANT_COLUMN,
                    "租户列（starter 强制注入的租户过滤所在列，不可关闭）");
            requireColumn(entityCode, entity.table(), columns, entity.subjectColumn(), "主体列(subject-column)");
            if (entity.isTemporal()) {
                requireColumn(entityCode, entity.table(), columns,
                        entity.effectiveFromColumn(), "时点起列(effective-from-column)");
                requireColumn(entityCode, entity.table(), columns,
                        entity.effectiveToColumn(), "时点止列(effective-to-column)");
            }
            for (AnalyticalFieldDefinition field : entity.fields()) {
                requireColumn(entityCode, entity.table(), columns, field.normalizedColumn(),
                        "字段 " + field.code() + " 的列(column)");
            }
            return resolveDeletion(entityCode, entity, columns);
        } catch (SQLException ex) {
            throw new AnalyticalQueryException(FeatureCenterErrorCode.ANALYTICAL_QUERY_FAILED,
                    "读取表结构元数据失败（实体 " + entityCode + "，表 " + entity.table() + "）："
                            + ex.getMessage()
                            + "——取数声明与表结构的对账无法完成，故不提供服务（不跳过核验继续跑）");
        }
    }

    private DeletedColumnStyle resolveDeletion(String entityCode, AnalyticalEntityDefinition entity,
                                               Map<String, Integer> columns) {
        if (entity.declaresNoDeletedColumn()) {
            for (String candidate : DELETION_CANDIDATES) {
                if (columns.containsKey(candidate)) {
                    throw new AnalyticalQueryException(FeatureCenterErrorCode.ANALYTICAL_QUERY_FAILED,
                            "实体 " + entityCode + " 声明 deleted-column: none（该表无删除标记列），"
                                    + "但表 " + entity.table() + " 实际存在列 " + candidate
                                    + "——声明与事实相反即拒绝服务：带了删除标记列却不施加过滤，"
                                    + "会把已删除的数据当成有效数据取出来（数值偏大且无人察觉）");
                }
            }
            return DeletedColumnStyle.NONE_VERIFIED;
        }

        int columnType = requireColumn(entityCode, entity.table(), columns, entity.deletedColumn(),
                "删除标记列(deleted-column)");
        if (NUMERIC_DELETION_TYPES.contains(columnType)) {
            return DeletedColumnStyle.NUMERIC_COLUMN;
        }
        if (BOOLEAN_DELETION_TYPES.contains(columnType)) {
            return DeletedColumnStyle.BOOLEAN_COLUMN;
        }
        throw new AnalyticalQueryException(FeatureCenterErrorCode.ANALYTICAL_QUERY_FAILED,
                "实体 " + entityCode + " 的删除标记列 " + entity.deletedColumn() + " 类型不可用（JDBC 类型码 "
                        + columnType + "）：只接受数值型（TINYINT/SMALLINT/INTEGER/BIGINT，按 = 0 过滤）"
                        + "或布尔型（BOOLEAN/BIT，按 = FALSE 过滤）——"
                        + "字符串型删除标记（如 'Y'/'N'）在库间比较口径不一，无法机械生成正确的过滤式");
    }

    /**
     * 读取表的所有列（列名小写 → JDBC 类型码）。
     *
     * <p>🔴 <b>大小写要试三种写法</b>：JDBC 的 {@code getColumns} 表名参数在各驱动上大小写敏感，
     * 而「未加引号的标识符落库后是大写还是小写」由数据库决定——MySQL 落小写、
     * H2 落大写。只按 YAML 里写的原样去查，会在其中一个库上得到「表不存在」的结论，
     * 而<b>那个结论是假的</b>（表在，只是名字被折叠了）。故按 原样 → 大写 → 小写 依次尝试，
     * 取第一个非空结果；三者皆空才判「表不存在」，且错误消息里说明已试过三种写法。</p>
     *
     * @param owner 调用方标识（错误消息用；调用方拼好「实体 X」这类前缀，本方法不假设它是实体码）
     */
    private Map<String, Integer> columnsOf(Connection connection, String owner, String table)
            throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        String catalog = connection.getCatalog();
        for (String candidate : List.of(table, table.toUpperCase(Locale.ROOT), table.toLowerCase(Locale.ROOT))) {
            Map<String, Integer> columns = new LinkedHashMap<>();
            try (ResultSet resultSet = metaData.getColumns(catalog, null, candidate, null)) {
                while (resultSet.next()) {
                    String name = resultSet.getString("COLUMN_NAME");
                    if (name != null) {
                        columns.put(name.toLowerCase(Locale.ROOT), resultSet.getInt("DATA_TYPE"));
                    }
                }
            }
            if (!columns.isEmpty()) {
                return columns;
            }
        }
        throw new AnalyticalQueryException(FeatureCenterErrorCode.ANALYTICAL_QUERY_FAILED,
                owner + " 声明的表 " + table + " 在数据源 "
                        + (catalog == null ? "(未知库)" : catalog)
                        + " 中不存在或当前连接不可见（已按原样、大写、小写三种写法各查一次元数据）");
    }

    private int requireColumn(String entityCode, String table, Map<String, Integer> columns,
                              String column, String role) {
        Integer columnType = columns.get(column.toLowerCase(Locale.ROOT));
        if (columnType == null) {
            throw new AnalyticalQueryException(FeatureCenterErrorCode.ANALYTICAL_QUERY_FAILED,
                    "实体 " + entityCode + " 声明的" + role + " " + column + " 在表 " + table
                            + " 中不存在（元数据实读到的列：" + columns.keySet() + "）——"
                            + "声明与表结构不符即拒绝服务，不从 SQL 里悄悄去掉这个条件："
                            + "少一个条件会把「查这个主体的」变成「查所有主体的」，结果看起来正常却是错的");
        }
        return columnType;
    }
}
