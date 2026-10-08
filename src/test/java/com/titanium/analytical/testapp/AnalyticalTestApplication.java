package com.titanium.analytical.testapp;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 集成测试的 Spring 引导类。
 *
 * <p>🔴 <b>本类刻意放在 {@code com.titanium.analytical.testapp} 这个独立子包，不是随便选的</b>：
 * {@code @SpringBootApplication} 的组件扫描以<b>本类所在包</b>为基准，故扫描范围只覆盖
 * {@code testapp} —— 主代码里的 {@code AnalyticalQueryController}（带 {@code @RestController}，
 * 位于 {@code com.titanium.analytical.web}）<b>扫不到</b>。它只能由
 * {@code AnalyticalAutoConfiguration} 注册成 Bean。</p>
 *
 * <p>这正是宿主域的真实形态（宿主域扫的是 {@code com.titanium.<domain>}，同样扫不到本模块）。
 * 于是「自动装配生效」成为一条<b>可证伪的判据</b>：若 {@code @AutoConfiguration} 的
 * {@code afterName} 锚定缺失（默认按类名字母序排在 {@code DataSourceAutoConfiguration} 之前，
 * 致 {@code @ConditionalOnBean(DataSource.class)} 判否、整份装配静默不生效），
 * 本测试类下的所有端点调用都会得到 <b>404</b> 而失败——而生产环境的症状与之逐字相同。
 * 若把引导类放在 {@code com.titanium.analytical}（父包），组件扫描会把 controller 直接注册，
 * 那时<b>装配失效也照样测试通过</b>，这条判据就废了。</p>
 */
@SpringBootApplication
public class AnalyticalTestApplication {
}
