package com.titanium.analytical.config;

import java.util.List;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import com.titanium.analytical.query.AnalyticalEntitySource;
import com.titanium.analytical.query.AnalyticalQueryService;
import com.titanium.analytical.query.AnalyticalQueryTranslator;
import com.titanium.analytical.query.AnalyticalTableSchemaGuard;
import com.titanium.analytical.query.AnalyticalValueNormalizer;
import com.titanium.analytical.seed.AnalyticalFieldCatalogSeeder;
import com.titanium.analytical.web.AnalyticalQueryController;

/**
 * 取数能力的**自动装配**：各业务域在 pom 里加一行依赖、在 YAML 里填一份声明，
 * 即拥有 {@code /api/v1/analytical/query} 与 {@code /handshake} 两个端点（新增 Java 代码 = 0）。
 *
 * <h3>🔴 {@code afterName} 不是可选项，去掉会静默失效</h3>
 * <p>自动装配类的默认排序是<b>按类名字母序</b>，而 {@code com.titanium.*} 排在
 * {@code org.springframework.*} <b>之前</b>。若不显式声明 {@code afterName}，
 * 本类的 {@code @ConditionalOnBean(DataSource.class)} 会在
 * {@code DataSourceAutoConfiguration} <b>之前</b>被求值——那时 {@code DataSource} 的
 * Bean 定义尚未注册，条件判否，于是<b>整份装配静默不生效</b>：应用正常启动、
 * 日志无任何异常、接口 404。症状与「依赖没加进去」完全同形，故此处显式锚定。</p>
 *
 * <h3>条件与边界</h3>
 * <ul>
 *   <li>{@code @ConditionalOnWebApplication(SERVLET)}：端点是 MVC 端点，非 Web 应用里无处装配；
 *       注意宿主域的 {@code @SpringBootApplication} 扫的是 {@code com.titanium.<domain>}，
 *       <b>扫不到本包</b>——所以控制器必须在这里注册成 Bean（它刻意不带 {@code @Component}）。</li>
 *   <li>{@code @ConditionalOnBean(DataSource.class)}：取数要读库。JPA 域必然有数据源，
 *       没有数据源的应用根本起不来（Boot 会先报「Failed to configure a DataSource」），
 *       故这里不会掩盖真实问题。</li>
 *   <li>所有 Bean 都带 {@code @ConditionalOnMissingBean}：宿主域要替换任一环（如自定义翻译器）
 *       只需自己声明同类型 Bean，无需改本模块。</li>
 * </ul>
 *
 * <h3>为何自建 {@code JdbcTemplate} 而不是复用宿主的那个 Bean</h3>
 * <p>本模块的执行器只需要「在一个 DataSource 上跑参数化查询」这一能力，不需要与宿主域共享配置
 * （fetch size、query timeout 等应由本模块自己决定：分析型扫描与业务单行查询的调优方向相反）。
 * 复用宿主的同名 Bean 还会引出一次「谁覆盖谁」的推断——本模块<b>不注册成 Bean</b>，
 * 只是在服务里 {@code new} 一个私有实例，从结构上避开那场推断。</p>
 */
@AutoConfiguration(afterName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBean(DataSource.class)
@EnableConfigurationProperties(AnalyticalEntityProperties.class)
public class AnalyticalAutoConfiguration {

    /**
     * 值规范化器：请求值按声明类型解析、结果值按声明类型渲染（日期一律 ISO 字符串）。
     *
     * @return 规范化器
     */
    @Bean
    @ConditionalOnMissingBean
    public AnalyticalValueNormalizer analyticalValueNormalizer() {
        return new AnalyticalValueNormalizer();
    }

    /**
     * 表结构守卫：把 YAML 里的标识符拿到 JDBC 元数据里逐一对账。
     *
     * @param dataSource 宿主域数据源
     * @return 守卫
     */
    @Bean
    @ConditionalOnMissingBean
    public AnalyticalTableSchemaGuard analyticalTableSchemaGuard(DataSource dataSource) {
        return new AnalyticalTableSchemaGuard(dataSource);
    }

    /**
     * 翻译器：结构化条件树 → 参数化 SQL。
     *
     * @param normalizer  值规范化器
     * @param schemaGuard 表结构守卫（提供删除标记列的实际形态）
     * @return 翻译器
     */
    @Bean
    @ConditionalOnMissingBean
    public AnalyticalQueryTranslator analyticalQueryTranslator(AnalyticalValueNormalizer normalizer,
                                                             AnalyticalTableSchemaGuard schemaGuard) {
        return new AnalyticalQueryTranslator(normalizer, schemaGuard);
    }

    /**
     * 取数执行服务。
     *
     * @param properties   取数声明
     * @param translator   翻译器
     * @param normalizer   值规范化器
     * @param dataSource   宿主域数据源（本模块自建只读执行器，不占用宿主的 JdbcTemplate Bean）
     * @param beanSources  属主域自写的取数实现（逃生舱；无实现时为空列表）
     * @return 取数服务
     */
    @Bean
    @ConditionalOnMissingBean
    public AnalyticalQueryService analyticalQueryService(AnalyticalEntityProperties properties,
                                                        AnalyticalQueryTranslator translator,
                                                        AnalyticalValueNormalizer normalizer,
                                                        DataSource dataSource,
                                                        List<AnalyticalEntitySource> beanSources) {
        return new AnalyticalQueryService(properties, translator, normalizer,
                new JdbcTemplate(dataSource), beanSources);
    }

    /**
     * 取数端点（两个：{@code POST /query} 与 {@code GET /handshake}）。
     *
     * @param queryService 取数服务
     * @return 控制器
     */
    @Bean
    @ConditionalOnMissingBean
    public AnalyticalQueryController analyticalQueryController(AnalyticalQueryService queryService) {
        return new AnalyticalQueryController(queryService);
    }

    /**
     * 启动期核验：声明标识符白名单 + 表结构对账，失败即不让服务进入可用状态。
     *
     * <p>放在 {@code ApplicationReadyEvent}（而非 {@code @PostConstruct}）是为了排在 Liquibase
     * 之后——建表由 Liquibase 完成，过早核验会把「表还没建」误报成「声明写错了」，
     * 而两者的处置方向完全相反。</p>
     *
     * @param properties 取数声明
     * @param translator 翻译器（标识符校验）
     * @param schemaGuard 表结构守卫（元数据对账）
     * @return 核验监听器
     */
    @Bean
    public AnalyticalReadinessVerifier analyticalReadinessVerifier(AnalyticalEntityProperties properties,
                                                                 AnalyticalQueryTranslator translator,
                                                                 AnalyticalTableSchemaGuard schemaGuard) {
        return new AnalyticalReadinessVerifier(properties, translator, schemaGuard);
    }

    /**
     * 字段目录种子导入（{@code seed-mode: init} 时的 INIT-ONLY 写入；{@code none} 时空转）。
     *
     * <p>与 {@link AnalyticalReadinessVerifier} 同监听 {@code ApplicationReadyEvent}，
     * 两者顺序不需要约定：核验针对**取数表**、导入针对**目录表**，互不读对方的结果。</p>
     *
     * @param properties 取数声明
     * @param dataSource 宿主域数据源
     * @param schemaGuard 表结构守卫（判「目录表是否已版本化」「导入所需列是否齐全」）
     * @return 种子导入器
     */
    @Bean
    public AnalyticalFieldCatalogSeeder analyticalFieldCatalogSeeder(AnalyticalEntityProperties properties,
                                                                    DataSource dataSource,
                                                                    AnalyticalTableSchemaGuard schemaGuard) {
        return new AnalyticalFieldCatalogSeeder(properties, new JdbcTemplate(dataSource), schemaGuard);
    }
}
