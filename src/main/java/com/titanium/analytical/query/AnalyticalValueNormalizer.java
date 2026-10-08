package com.titanium.analytical.query;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

import com.titanium.analytical.AnalyticalQueryException;
import com.titanium.metadata.enums.FieldDataType;
import com.titanium.metadata.errorcode.FeatureCenterErrorCode;

/**
 * 取值**双向**规范化：入参（请求值 → JDBC 绑定值）与出参（JDBC 值 → 线格式安全值）。
 *
 * <p>两个方向共用一个类型开关，是因为它们回答的是同一个问题：「{@link FieldDataType} 说这个字段是
 * 这个类型，那么它的值该长什么样」。分开两处实现会让「入参按 A 口径解析、出参按 B 口径渲染」
 * 这种不对称长期无人发现。</p>
 *
 * <p>🔴 <b>入参规范化是必需而非可选</b>：请求值经 JSON 往返后运行时类型会漂移
 * （{@code AnalyticalFilter#values()} 的契约已显式声明：数字可能变 {@code Integer}/{@code Double}、
 * 嵌套结构可能变 {@code Map}）。若直接把运行时类型丢给 JDBC，
 * 症状是「{@code LocalDate} 与 {@code String} 的比较在 MySQL 上按字符串比」这类
 * <b>能跑出结果、结果不对</b>的缺陷。</p>
 *
 * <p>🔴 <b>出参的日期一律渲染成 ISO 字符串</b>：本仓跨序列化库的线格式是隐式契约，
 * 而 Jackson 的 {@code WRITE_DATES_AS_TIMESTAMPS} <b>默认为 true</b> ⇒
 * {@code LocalDate}/{@code LocalDateTime} 会落成<b>数组</b>（如 {@code [2026,1,1]}）。
 * 消费侧解析器一旦按对象/字符串读，症状是「消费位点照常推进、无异常、只有业务产物缺失」。
 * 出参用字符串把这一条堵死在产生侧：下游无论用什么库解析，
 * {@code LocalDate.parse} 与 {@code LocalDateTime.parse} 都能吃下。</p>
 *
 * <p>🔴 <b>两个方向的失败归属刻意不同</b>：入参解析失败是<b>请求形状</b>问题
 * （值个数与类型不符，错误码 {@code ANALYTICAL_REQUEST_REJECTED}），由本类直接抛出；
 * 出参转换失败是<b>执行</b>问题（结果集不可映射，如聚合结果溢出），本类抛原始异常，
 * 由 {@code AnalyticalQueryService} 统一包装为 {@code ANALYTICAL_QUERY_FAILED}。
 * 混为一谈会让运维在被告知「请求非法」时去查客户端，而实际是数据库里有脏数据。</p>
 */
public class AnalyticalValueNormalizer {

    /**
     * 请求值 → JDBC 绑定值（按字段声明类型解析）。
     *
     * @param raw      请求侧的值（**不得为 null**：SQL 里 {@code = NULL} 恒不成立，
     *                 会导致「查了但没有数据」，与「真的没数据」同形；空值判定请用
     *                 {@code IS_NULL}/{@code IS_NOT_NULL} 算子）
     * @param type     字段声明的取值类型
     * @param fieldRef 字段引用（仅用于错误消息定位）
     * @return 可直接绑定的值
     * @throws AnalyticalQueryException 值为空、无法按声明类型解析，或该类型不可用于筛选
     */
    public Object toParameter(Object raw, FieldDataType type, String fieldRef) {
        if (raw == null) {
            throw reject(fieldRef, type, "null",
                    "SQL 中 \"= NULL\" 恒不成立，绑定 null 会得到「零行」——"
                            + "与「该条件下确实没有数据」在响应上完全同形。空值判定请用 IS_NULL / IS_NOT_NULL 算子");
        }
        try {
            return switch (type) {
                case STRING, ENUM -> asText(raw);
                case INTEGER -> asInteger(raw);
                case DECIMAL -> asDecimal(raw);
                case BOOLEAN -> asBoolean(raw);
                case DATE -> asDate(raw);
                case DATETIME -> asDateTime(raw);
                case JSON -> throw reject(fieldRef, type, raw,
                        "JSON 类型不参与筛选：对 JSON 列做等值/范围比较在数据库间口径不一"
                                + "（文本比较 vs 结构化比较），同一个条件换一个域会得到不同结果");
            };
        } catch (NumberFormatException | ArithmeticException | DateTimeParseException ex) {
            throw reject(fieldRef, type, raw, ex.getMessage());
        }
    }

    /**
     * JDBC 结果值 → 线格式安全值（按字段声明类型渲染）。
     *
     * @param jdbcValue JDBC 读出的值（**可为 null**：空集合语义下 {@code SUM}/{@code AVG}/
     *                  {@code MIN}/{@code MAX} 就是 null，本方法原样透传、绝不折算成 0）
     * @param type      字段声明的取值类型
     * @return 可直接放进响应体的值
     */
    public Object toResponse(Object jdbcValue, FieldDataType type) {
        if (jdbcValue == null) {
            // R-3：空集合语义靠 null 承载，此处不得"顺手"补 0 或空串
            return null;
        }
        return switch (type) {
            case DATE -> renderDate(jdbcValue);
            case DATETIME -> renderDateTime(jdbcValue);
            case INTEGER -> renderInteger(jdbcValue);
            case DECIMAL -> renderDecimal(jdbcValue);
            case BOOLEAN -> renderBoolean(jdbcValue);
            case STRING, ENUM -> asText(jdbcValue);
            case JSON -> asText(jdbcValue);
        };
    }

    // ------------------------------------------------------------------ 入参

    private Object asText(Object raw) {
        if (raw instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return String.valueOf(raw);
    }

    private Object asInteger(Object raw) {
        if (raw instanceof BigDecimal decimal) {
            return decimal.longValueExact();
        }
        if (raw instanceof Double || raw instanceof Float) {
            double value = ((Number) raw).doubleValue();
            if (value != Math.rint(value)) {
                throw new ArithmeticException("整数字段不接受小数取值：" + raw);
            }
            return (long) value;
        }
        if (raw instanceof Number number) {
            return number.longValue();
        }
        return new BigDecimal(String.valueOf(raw).trim()).longValueExact();
    }

    private Object asDecimal(Object raw) {
        if (raw instanceof BigDecimal decimal) {
            return decimal;
        }
        if (raw instanceof BigInteger integer) {
            return new BigDecimal(integer);
        }
        if (raw instanceof Double || raw instanceof Float) {
            // 用 valueOf(double) 而非 new BigDecimal(double)：后者会把 0.1 展开成 0.1000000000000000055…
            return BigDecimal.valueOf(((Number) raw).doubleValue());
        }
        if (raw instanceof Number number) {
            return BigDecimal.valueOf(number.longValue());
        }
        return new BigDecimal(String.valueOf(raw).trim());
    }

    private Object asBoolean(Object raw) {
        if (raw instanceof Boolean flag) {
            return flag;
        }
        if (raw instanceof Number number) {
            return number.doubleValue() != 0d;
        }
        String text = String.valueOf(raw).trim();
        if ("true".equalsIgnoreCase(text) || "1".equals(text)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(text) || "0".equals(text)) {
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException("布尔字段只接受 true/false/1/0，实际 " + text);
    }

    private Object asDate(Object raw) {
        if (raw instanceof LocalDate date) {
            return date;
        }
        if (raw instanceof LocalDateTime dateTime) {
            return dateTime.toLocalDate();
        }
        if (raw instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        String text = String.valueOf(raw).trim();
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException ignored) {
            // 允许用「日期时间」表达一个日期（取其日期部分）
            return LocalDateTime.parse(text).toLocalDate();
        }
    }

    private Object asDateTime(Object raw) {
        if (raw instanceof LocalDateTime dateTime) {
            return dateTime;
        }
        if (raw instanceof LocalDate date) {
            return date.atStartOfDay();
        }
        if (raw instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        String text = String.valueOf(raw).trim();
        try {
            return LocalDateTime.parse(text);
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(text).toLocalDateTime();
            } catch (DateTimeParseException ignoredToo) {
                return LocalDate.parse(text).atStartOfDay();
            }
        }
    }

    // ------------------------------------------------------------------ 出参

    private Object renderDate(Object value) {
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate().toString();
        }
        if (value instanceof LocalDate date) {
            return date.toString();
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.toLocalDate().toString();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().toLocalDate().toString();
        }
        return String.valueOf(value);
    }

    private Object renderDateTime(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().toString();
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.toString();
        }
        if (value instanceof LocalDate date) {
            return date.atStartOfDay().toString();
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate().atStartOfDay().toString();
        }
        return String.valueOf(value);
    }

    private Object renderInteger(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal.toBigIntegerExact().longValueExact();
        }
        if (value instanceof BigInteger integer) {
            return integer.longValueExact();
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalArgumentException("整型字段的结果值不是数值：" + value);
    }

    private Object renderDecimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof BigInteger integer) {
            return new BigDecimal(integer);
        }
        if (value instanceof Double || value instanceof Float) {
            return BigDecimal.valueOf(((Number) value).doubleValue());
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.longValue());
        }
        return new BigDecimal(String.valueOf(value).trim());
    }

    private Object renderBoolean(Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0d;
        }
        String text = String.valueOf(value).trim();
        if ("1".equals(text)) {
            return Boolean.TRUE;
        }
        if ("0".equals(text)) {
            return Boolean.FALSE;
        }
        return Boolean.valueOf(text);
    }

    private AnalyticalQueryException reject(String fieldRef, FieldDataType type, Object raw, String reason) {
        return new AnalyticalQueryException(FeatureCenterErrorCode.ANALYTICAL_REQUEST_REJECTED,
                "字段 " + fieldRef + "（声明类型 " + type.getCode() + "）的取值无法使用："
                        + describe(raw) + "——" + reason);
    }

    private String describe(Object raw) {
        return raw == null ? "null" : "\"" + raw + "\"";
    }
}
