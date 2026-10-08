package com.titanium.analytical.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

import com.titanium.analytical.query.AnalyticalQueryTranslator;
import com.titanium.analytical.query.AnalyticalTableSchemaGuard;

import lombok.extern.slf4j.Slf4j;

/**
 * 启动期一次性核验：把每份取数声明拿到**真实世界**里对一遍，任一实体不通过即**服务不可用**。
 *
 * <h3>核验什么</h3>
 * <ol>
 *   <li><b>标识符白名单</b>（{@link AnalyticalQueryTranslator#validate}）：表名、主体列、
 *       删除标记列、时点起止列、每个字段的列名、目录表名——它们会被拼进 SQL 文本，
 *       故必须在服务可用之前逐个确认是纯标识符；</li>
 *   <li><b>表结构对账</b>（{@link AnalyticalTableSchemaGuard#verify}）：这些列在库里**真的存在**、
 *       类型可用，以及 {@code deleted-column: none} 这类声明**与事实相符**。</li>
 * </ol>
 *
 * <h3>🔴 为什么必须是启动期，而不是等查询期报错</h3>
 * <p>声明与表结构的矛盾是<b>确定性缺陷</b>：它不会因为时间或输入变好，只会等到某次真实取数
 * 才炸——而那时症状是「某个请求报 SQL 错」，错误方向指向数据库，排障的人会去查表，
 * 真正要改的却是 YAML。同理，<b>不能降级</b>（如「列不存在就把这个过滤条件去掉」）：
 * 少一个条件会把「查这个主体的」变成「查所有主体的」，结果看起来正常却是错的。</p>
 *
 * <h3>为什么挂 {@code ApplicationReadyEvent} 而不是 {@code @PostConstruct}</h3>
 * <p>建表由 Liquibase 完成，而 Liquibase 与自定义 Bean 的初始化顺序没有约定——
 * 在 {@code @PostConstruct} 里核验会把「表还没建好」误报成「声明写错了」，
 * 而这两者的处置方向完全相反（等迁移 vs 改配置）。{@code ApplicationReadyEvent}
 * 保证所有初始化（含 Liquibase）已完成。</p>
 *
 * <h3>逐实体收集、一次报全</h3>
 * <p>不在第一个失败处中断：一次性列出全部不通过的实体及原因，配置者一轮就能补齐。
 * 「逐个报错、来回启动 N 次」正是 {@link AnalyticalEntityDefinition} 的逐项校验要避免的事，
 * 这里沿用同一纪律。</p>
 *
 * <h3>空声明不是失败</h3>
 * <p>「引入了依赖但还没写声明」是正常中间态，此时查询请求会得到
 * {@code ANALYTICAL_ENTITY_NOT_DECLARED}（{@code 67000019}）的明确拒绝——
 * 让「还没配」变成「服务起不来」会把一次配置未完成升级成一次发布事故。</p>
 */
@Slf4j
public class AnalyticalReadinessVerifier implements ApplicationListener<ApplicationReadyEvent> {

    private final AnalyticalEntityProperties properties;
    private final AnalyticalQueryTranslator translator;
    private final AnalyticalTableSchemaGuard schemaGuard;

    /**
     * 构造核验器。
     *
     * @param properties  取数声明
     * @param translator  翻译器（标识符白名单校验）
     * @param schemaGuard 表结构守卫（元数据对账）
     */
    public AnalyticalReadinessVerifier(AnalyticalEntityProperties properties,
                                       AnalyticalQueryTranslator translator,
                                       AnalyticalTableSchemaGuard schemaGuard) {
        this.properties = properties;
        this.translator = translator;
        this.schemaGuard = schemaGuard;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (properties.isEmpty()) {
            log.info("[Analytical] 未声明任何取数实体（{}）：端点已就绪，任何取数请求将得到「实体未声明」的拒绝",
                    AnalyticalEntityProperties.PREFIX);
            return;
        }
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, AnalyticalEntityDefinition> entry : properties.entities().entrySet()) {
            verifyOne(entry.getKey(), entry.getValue(), problems);
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "取数声明核验未通过（" + problems.size() + " / " + properties.entities().size()
                            + " 个实体），服务不予进入可用状态：\n  - " + String.join("\n  - ", problems)
                            + "\n（声明与表结构/标识符的矛盾不会自己变好，故失败关闭；"
                            + "不降级为「跳过有问题的实体」，否则取数打到错的地方而无人察觉）");
        }
        log.info("[Analytical] 取数声明核验通过：{} 个实体 / {} 个字段，表结构对账全部成立",
                properties.entities().size(),
                properties.entities().values().stream().mapToInt(entity -> entity.fields().size()).sum());
    }

    private void verifyOne(String entityCode, AnalyticalEntityDefinition entity, List<String> problems) {
        try {
            translator.validate(entityCode, entity);
            schemaGuard.verify(entityCode, entity);
        } catch (RuntimeException ex) {
            problems.add("实体 " + entityCode + "：" + ex.getMessage());
        }
    }
}
