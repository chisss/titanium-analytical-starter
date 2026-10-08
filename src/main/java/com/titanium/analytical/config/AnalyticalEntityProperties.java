package com.titanium.analytical.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.titanium.analytical.AnalyticalQueryException;
import com.titanium.metadata.errorcode.FeatureCenterErrorCode;

/**
 * 取数声明的**配置入口**（{@code titanium.analytical.*}），YAML 绑定的唯一落点。
 *
 * <p>🔴 <b>YAML 是开发资产，不是运营配置</b>（架构方案 §9 R-3）：它声明「这个域的哪个字段
 * 落在哪张表的哪一列」，改它走**代码评审 + 发版**。运营配置的是**特征定义**（后台），
 * 不是取数映射——把两者混在一起，会出现「后台改了取值口径、但没人知道取的是哪一列」。
 * 这条边界同样写在模块 {@code README.md} 与各业务域的 YAML 头注释里。</p>
 *
 * <p>🔴 <b>整份配置为空是合法形态</b>（未引入 starter 的域与本域无关；
 * 引入了但尚未声明实体的域应得到「实体未声明」的明确拒绝，而不是启动失败——
 * 后者会把「还没配完」变成「服务起不来」）。故 {@code entities} 允许为空，
 * 由取数时的 {@link #requireEntity(String)} 承担失败关闭。</p>
 *
 * @param seedMode        字段目录导入模式；缺省 {@link SeedMode#NONE}（不碰数据库）
 * @param catalogTenantId 目录导入写入的租户标识；缺省 {@value #DEFAULT_CATALOG_TENANT_ID}
 *                        （字段目录是跨租户共享的平台元数据，G07/AC-10）
 * @param entities        实体码 → 实体声明（可空；实体码**不得含点**，见类注释）
 */
@ConfigurationProperties(prefix = AnalyticalEntityProperties.PREFIX)
public record AnalyticalEntityProperties(
        SeedMode seedMode,
        String catalogTenantId,
        Map<String, AnalyticalEntityDefinition> entities) {

    /** 配置前缀（各业务域的 YAML 根）。 */
    public static final String PREFIX = "titanium.analytical";

    /** 目录导入的默认租户标识（与 policy 目录种子的取值一致）。 */
    public static final String DEFAULT_CATALOG_TENANT_ID = "1";

    /**
     * 紧凑构造器：规范化缺省值并校验实体码。
     *
     * <p>🔴 <b>实体码不得含点</b>：字段引用 {@code {entityCode}.{fieldCode}} 按**第一个点**
     * 切分成「实体码 + 字段码」，实体码里再含点会让切分结果与声明者的意图不一致——
     * 而两边都「看起来合理」，症状是取数打到另一个实体的列上。</p>
     */
    public AnalyticalEntityProperties {
        seedMode = seedMode == null ? SeedMode.NONE : seedMode;
        catalogTenantId = (catalogTenantId == null || catalogTenantId.isBlank())
                ? DEFAULT_CATALOG_TENANT_ID
                : catalogTenantId.trim();

        Map<String, AnalyticalEntityDefinition> declared = new LinkedHashMap<>();
        if (entities != null) {
            for (Map.Entry<String, AnalyticalEntityDefinition> entry : entities.entrySet()) {
                String entityCode = entry.getKey() == null ? null : entry.getKey().trim();
                if (entityCode == null || entityCode.isEmpty()) {
                    throw new IllegalArgumentException("取数实体码不能为空（" + PREFIX + ".entities 下的键）");
                }
                if (entityCode.contains(".")) {
                    throw new IllegalArgumentException(
                            "取数实体码不得含点：" + entityCode
                                    + "——字段引用按「第一个点」切成「实体码 + 字段码」，实体码含点会把字段码切错");
                }
                if (entry.getValue() == null) {
                    throw new IllegalArgumentException("取数实体 " + entityCode + " 的声明不能为空");
                }
                declared.put(entityCode, entry.getValue());
            }
        }
        entities = Collections.unmodifiableMap(declared);
    }

    /**
     * 取指定实体的声明，未声明即失败关闭。
     *
     * <p>🔴 <b>不做「按前缀猜实体」</b>：请求里的实体码要么在这份声明里、要么拒绝。
     * 猜测（如「取第一个声明的实体」「按域名前缀匹配」）会让「实体码打错」退化成
     * 「查了另一个实体的数」——后者返回的是**看起来正常的值**，比报错危险得多。</p>
     *
     * @param entityCode 实体码（请求传入）
     * @return 实体声明
     * @throws AnalyticalQueryException 该实体未在 YAML 中声明
     */
    public AnalyticalEntityDefinition requireEntity(String entityCode) {
        AnalyticalEntityDefinition declared = entityCode == null ? null : entities.get(entityCode.trim());
        if (declared == null) {
            throw new AnalyticalQueryException(FeatureCenterErrorCode.ANALYTICAL_ENTITY_NOT_DECLARED,
                    "实体 " + entityCode + " 未在本域的取数声明（" + PREFIX + ".entities）中登记，已声明："
                            + entities.keySet());
        }
        return declared;
    }

    /**
     * 已声明的实体码清单（保序）。
     *
     * @return 实体码列表
     */
    public List<String> entityCodes() {
        return List.copyOf(entities.keySet());
    }

    /**
     * 是否未声明任何实体。
     *
     * @return 无声明返回 true
     */
    public boolean isEmpty() {
        return entities.isEmpty();
    }
}
