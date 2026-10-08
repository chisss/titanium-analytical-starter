package com.titanium.analytical.testapp;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

/**
 * 依赖纪律的**构建期**守护：starter 只能依赖 {@code titanium-metadata}，不得依赖任何业务域。
 *
 * <p>为什么这条纪律值得一个独立测试（而不是靠代码评审）：本模块是<b>本仓第一个跨域 Starter</b>
 * （R-13），它会被 17 个业务域依赖。一旦它反向依赖了某个业务域，依赖图里就出现
 * 「policy → starter → feature-center」这样的环，而症状<b>不是编译错误</b>——
 * 是某个域单独构建时莫名其妙拉进一串它从未声明过的传递依赖，
 * 且这类环在「全量构建」里从不暴露（全量构建本来就把所有域都编了）。</p>
 *
 * <p>白名单只有两项：自己（{@code com.titanium.analytical..}）与中立契约
 * （{@code com.titanium.metadata..}）。{@code titanium-metadata} 是轻量共享库
 * （只带 lombok），把它引给各业务域不会污染任何调用方——这也正是契约下沉到那里的原因。</p>
 */
class AnalyticalNoBusinessDomainDependencyTest {

    private static JavaClasses starterClasses;

    @BeforeAll
    static void importStarterClasses() {
        // 🔴 DO_NOT_INCLUDE_TESTS：测试类会依赖 junit/spring-test，与「主代码的依赖纪律」无关；
        //    把两者混在一个断言里会得到一条「测试代码导致的红」，红的方向指向错的地方。
        starterClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.titanium.analytical");
    }

    @Test
    @DisplayName("starter 的全部依赖除自身外只允许落在 titanium-metadata（不得依赖任何业务域）")
    void starterDependsOnNothingButItselfAndMetadata() {
        ArchRule rule = classes()
                .that().resideInAPackage("com.titanium.analytical..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "com.titanium.analytical..",
                        "com.titanium.metadata..",
                        "java..",
                        "javax..",
                        "org.springframework..",
                        "org.slf4j..",
                        "lombok..")
                .because("starter 被 17 个业务域依赖，反向依赖任一业务域都会造出依赖环，"
                        + "而该环在编译期与全量构建里都不暴露");

        rule.check(starterClasses);
    }

    @Test
    @DisplayName("🔴 不得依赖 feature-center（消费方）——这条纪律是「单实例 Port、属主域零耦合」的前提")
    void mustNotDependOnFeatureCenter() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.titanium.analytical..")
                .should().dependOnClassesThat().resideInAPackage("com.titanium.featurecenter..")
                .because("starter 是 provider 端组件，feature-center 是它的消费方；"
                        + "provider 依赖 consumer 会把「各域独立引入」变成「各域必须一起发版」");

        rule.check(starterClasses);
    }

    @Test
    @DisplayName("🔴 不得依赖任何其它业务域（逐域点名，便于日后的放宽讨论看得见红线在哪）")
    void mustNotDependOnAnyOtherBusinessDomain() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.titanium.analytical..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.titanium.common..",
                        "com.titanium.policy..",
                        "com.titanium.customer..",
                        "com.titanium.underwriting..",
                        "com.titanium.clause..",
                        "com.titanium.billing..",
                        "com.titanium.claim..",
                        "com.titanium.maintenance..",
                        "com.titanium.regulatory..",
                        "com.titanium.payment..",
                        "com.titanium.product..",
                        "com.titanium.reinsurance..",
                        "com.titanium.channel..",
                        "com.titanium.document..",
                        "com.titanium.notification..",
                        "com.titanium.ruleengine..",
                        "com.titanium.admin..")
                .because("跨域 Starter 的依赖白名单只有 metadata；"
                        + "titanium-common 虽名为「通用」，但它是重量级内核"
                        + "（JPA/安全/数据源），引它会给每个宿主域多带一串重依赖");

        rule.check(starterClasses);
    }
}
