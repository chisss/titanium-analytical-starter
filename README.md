# titanium-analytical-starter

分析型取数的 **provider 端通用执行器**：各业务域在 pom 里加一行依赖、在 `application.yml` 里填一份声明，
即拥有 `/api/v1/analytical/query` 与 `/api/v1/analytical/handshake` 两个端点，**新增 Java 代码 = 0**。

方向：`titanium-feature-center` 是**消费方**（按注册表向各属主域取数），本模块是**提供方**侧的通用实现。
本模块**不依赖** feature-center、**不依赖任何业务域**（只依赖 `titanium-metadata` 的中立契约），
由 `AnalyticalNoBusinessDomainDependencyTest` 在构建期守护——它被 17 个域依赖，反向依赖任一域都会造出依赖环，
而该环在编译期与全量构建里都不暴露。

---

## 一、接入（零 Java 代码）

### 1. 加依赖

```xml
<dependency>
    <groupId>com.titanium</groupId>
    <artifactId>titanium-analytical-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

前提：宿主域已有 `DataSource`（引入的前提就是它有数据源）且是 Servlet Web 应用。
不加数据源时本模块的自动装配整体不生效——应用能起，但两个端点 404。

### 2. 填声明

```yaml
titanium:
  analytical:
    seed-mode: none          # 默认值，不碰任何目录表
    entities:
      policy:
        table: v_analytical_policy          # 推荐用专用分析视图，见 §三
        subject-column: policy_id
        deleted-column: is_deleted          # 缺省即此值；写 none 声明「本表确无删除标记列」
        consistency: EVENTUAL               # 必填：读模型如实声明 EVENTUAL
        fields:
          - code: premium                   # 字段码（不含实体码前缀）
            column: premium
            type: DECIMAL
            analyzable: true                # 可被取数项选取
            aggregatable: true              # 可参与 SUM/COUNT/AVG/MIN/MAX
            filterable: true                # 可出现在筛选条件中
```

### 3. 建专用分析视图（可选，但推荐）

见 §三 第三条防线。

**至此结束**：不需要写 Controller、Service、配置类。`@SpringBootApplication` 扫的是
`com.titanium.<domain>`，**扫不到本模块的包**——控制器刻意不带 `@Component`，由自动装配注册，
这是「starter 不污染宿主域」的一部分，不要试图把它扫进来。

> 🔴 **YAML 是开发资产，不是运营配置**（方案 R-3）。
> 它声明「哪个字段落在哪张表的哪一列」，**改它走代码评审 + 发版**。
> 运营配置的是**特征定义**（后台），不是取数映射——把两者混在一起，
> 会出现「后台改了取值口径，但没人知道取的是哪一列」。

### 声明字段全表

| 项 | 必填 | 缺省 | 说明 |
|---|:--:|---|---|
| `table` | ✅ | — | 物理表名或分析视图名。**不给默认**：缺了会退化成「对着一张猜出来的表取数」 |
| `subject-column` | ✅ | — | 主体列；`subjectId` 非空时按它等值过滤 |
| `consistency` | ✅ | — | `STRONG` / `EVENTUAL`。**不给默认**：给了默认会让「忘了声明」与「声明了 EVENTUAL」在握手响应上同形，而两者的处置相反 |
| `allow-cross-subject` | | `false` | 是否允许 `subjectId` 为空（跨主体聚合）。为 false 时传空即拒（67000015） |
| `deleted-column` | | `is_deleted` | 写 `none` 声明「本表确无删除标记列」——**这不是关闭过滤的开关**，starter 会先用 JDBC 元数据核验，核验不过即拒绝服务 |
| `effective-from-column` / `effective-to-column` | | — | 时点回溯的起止列。**两者必须同时给**，只给一半启动即失败 |
| `catalog-table` | | — | 字段目录表名，仅 `seed-mode: init` 需要 |
| `fields[].code` | ✅ | — | 字段码，**不含实体码前缀**。请求里写 `{entityCode}.{fieldCode}` |
| `fields[].column` | ✅ | — | 物理列名。它与表名是**唯一会进入 SQL 文本**的外部输入，故经标识符白名单 + 元数据核验双重把关 |
| `fields[].type` | ✅ | — | 请求值按它规范化后才绑定参数 |
| `fields[].analyzable` / `aggregatable` / `groupable` / `filterable` | | `false` | 能力位，与 G07 字段目录的四项同名同义 |
| `fields[].catalog` | | — | 目录属性（14 列，见 §六）。**可空**：执行面与目录面是两个问题 |

支持的值类型：`STRING`、`ENUM`、`INTEGER`、`DECIMAL`、`BOOLEAN`、`DATE`、`DATETIME`
（`JSON` 不可作为筛选值）。

---

## 二、端点

### `POST /api/v1/analytical/query`

- 请求头 `X-Tenant-Id` **必带**，缺失即拒（67000010）：无租户则无法施加隔离过滤，
  放行会退化成跨租户全表扫描，而结果「看起来正常、只是偏大」。
  ⚠️ 实际部署中宿主域会**先**拦一道：`/api/v1/**` 是契约入口，宿主域的 `TenantApiInterceptor`
  对该前缀整段要求此头（缺则 HTTP 400 `TENANT_HEADER_MISSING`）。两条校验方向一致，本层的
  意义在于**宿主域未装该拦截器时**（如本模块自己的测试上下文）取数仍被强制租户化。
- 请求体是结构化条件树（`AnalyticalFilter`：`COMPARISON` / `AND` / `OR`），
  **不存在字符串表达式字段**（方案 R-12），故「拼一个 where 串进来」在结构上不成立。
- 算子白名单 12 个：`EQ` `NE` `GT` `GE` `LT` `LE` `IN` `NOT_IN` `LIKE` `NOT_LIKE` `IS_NULL` `IS_NOT_NULL`。
  筛选条件的值一律编译成**参数化 SQL 的绑定参数**，绝不拼进 SQL 文本。
- `groupBy` 当前一律拒绝（67000021）：契约保留了该字段以便向前兼容，执行侧尚未开放。
- 失败一律以 **HTTP 200 + 8 位业务码**应答（§8.2）：HTTP 状态码只表达传输层语义，
  把业务失败塞进状态码会让「路径配错了」与「对端清醒地答了业务错误」在下游同形。

### `GET /api/v1/analytical/handshake`

如实回报每个实体的 `entityCode` / `consistency` / `source`（`YAML` 或 `BEAN`），按实体码排序。

🔴 **本模块自身不校验租户头**（响应是部署能力快照，与租户无关），但**实际部署中它仍要求
`X-Tenant-Id`**——原因不在本模块：`/api/v1/**` 是全仓契约入口，宿主域经 `TenantApiInterceptor`
对该前缀**整段**强制租户头（缺则 HTTP 400），本端点不在豁免清单内。starter 在此**刻意不再校验一次**：
那行代码在宿主域里永远到不了（外层先拦），只是死代码；而在未装拦截器的宿主里，
它会凭空造出一条「不消费租户的端点却要求租户」的约束。跨域调用方由
`TenantFeignRequestInterceptor` 自动补头，无需手工传；**人工 curl 核查时记得带上**。

> 🔴 **服务令牌同理**：宿主域经 `InternalAuthAutoConfiguration` 装配 `InternalTokenAuthFilter`，
> 对 `/api/v1/**` 校验请求头 `X-Internal-Token`（配置了 `titanium.internal.token` 时；未配置则
> 退化为「只告警不拦截」）。部署环境（docker-compose）已为全部服务注入该令牌，故**真机直连
> 这两个端点须带 `X-Internal-Token`**，否则得到 401；跨域 Feign 调用由 `InternalTokenFeignInterceptor`
> 自动补。本模块不依赖 `titanium-common`，以上两项均由**宿主域基础设施**提供，非本模块行为。

这是 R-14「声明与实现不符」唯一的机械发现手段：静态代码查不出某域在注册表里写了 `STRONG`
而实际读的是有投影延迟的读模型，只有让提供方自己回报才能对账。故本端点**如实转录声明**，
不做任何「按表名推断一致性」的猜测——猜测会让「声明错了」与「声明对了」在响应上同形，
恰好抹掉这个端点存在的意义。

> ⚠️ 截至本模块交付，**该端点尚无消费方**（feature-center 侧的对账尚未接入），登记为缺口。

---

## 三、三条强制防线（方案 R-2）

1. **租户 + 删除标记由 starter 强制注入，YAML 不可关闭。**
   `tenant_id` 是常量（`AnalyticalTableSchemaGuard.TENANT_COLUMN`），只能被核验、不能被替换；
   删除标记列由元数据核验其存在与类型（数值型走 `= 0`、布尔型走 `= FALSE`）。
   `deleted-column: none` 只能**陈述事实**，不能改变过滤行为。
2. **逃生舱：过滤逻辑表达不了的实体走 Bean。** 见 §五。
3. **各域可声明专用分析视图**（`v_analytical_*`）。推荐做法：为本域的取数建专用视图，
   在里面把口径固化（该并的并、该过滤的过滤），YAML 只引用视图名。
   这样 starter 的强制注入只作用在视图上，宿主域的表结构演进不会牵动取数声明。

---

## 四、契约语义（消费方依赖的线格式，改动即破坏兼容）

- **空集合（方案 R-3）**：无匹配行时 `SUM`/`AVG`/`MIN`/`MAX` 回报 `null`，`COUNT` 回报 `0`。
  **绝不把 `null` 折算成 `0`**：「没有明细」与「明细之和为 0」是两件事，折算后前者永久不可见。
  此时 `dataType` 仍如实回报，它是区分「无明细」与「类型未知」的唯一依据。
- **明细零行**：逐个取数项回报 `value = null`（**别名齐全**），不回报空列表。
  `alias` 是请求与响应的唯一配对键，消费侧对「响应里找不到某个别名」的处理是**抛契约违约**，
  故回报空列表会把每一次「该主体确实没有这份数据」变成一次「对端不守约」。
- **明细多行**：命中 ≥ 2 行即拒绝（67000021），不静默取第一行。响应是扁平 `values` 列表、没有行维度。
- **NULL 度量守卫**：SQL 的 `SUM`/`AVG`/`MIN`/`MAX` 会跳过 NULL 行，而基准实现
  （`FeatureAggregationService`）对 NULL **直接失败**——同一份数据会在两个档位得到两个
  「都成功返回、数值却不同」的结果。故本模块额外探测行数与每度量列的非空计数，
  一旦有明细取不到值即显式失败（67000012），把差异收口成「两处都对或两处都失败」。
- **日期与时间为 ISO 字符串**：Jackson 的 `WRITE_DATES_AS_TIMESTAMPS` 默认开启，
  直接放 `LocalDate` 会落成数组 `[2026,3,5]`，而下游按字符串读 ⇒ 消费位点正常推进、
  日志无 ERROR，唯一信号是业务产物缺失。故出参一律渲染成字符串。
- **别名绝不进 SQL**：查询里没有 `AS` 子句，结果按**列序号**读取，别名只存在于 Java 侧的取值计划。
  给出别名定字符规则必然会拒掉真实请求（feature-center 的别名即 featureCode，含点）。
- **时点回溯是半开区间 `[effective-from, effective-to)`**：起含、止不含，止列为 `NULL` 视为开放区间。
- **`maxRows` 在当前契约形状下恒被满足**：聚合查询恒返回 1 行、明细查询命中多行即拒，
  故每条查询最多 1 行，本模块**不读** `maxRows`。不要指望它替你截断结果。
- **`asOf` 对 YAML 来源实体恒为 `null`**：读模型没有逐行的数据时点，契约明确允许为空
  （编造一个时点比 `null` 更危险——下游会据此误判数据够新）。

---

## 五、逃生舱：属主域自写取数实现

某实体的过滤逻辑通用执行器表达不了（多表关联、按业务规则拼条件、需查另一张表才能定位列）时，
写一个实现 `AnalyticalEntitySource` 的 Bean 即可接管该实体的取数。

```java
@Component
public class PolicyRiskSource implements AnalyticalEntitySource {
    @Override public String entityCode() { return "policyRisk"; }
    @Override public AnalyticalConsistency consistency() { return AnalyticalConsistency.EVENTUAL; }
    @Override public AnalyticalQueryResponse query(AnalyticalQueryRequest request, String tenantId) {
        // tenantId 由 starter 显式传入，必须用它过滤
    }
}
```

starter 在 Bean 路径上能机械校验的只有三件事，其余全靠实现者自证：

| 校验点 | 行为 |
|---|---|
| 实体码与 YAML 冲突 | 启动期拒绝（否则谁生效取决于遍历顺序，换一次启动顺序即换一个取值口径） |
| 两个 Bean 撞同一实体码 | 启动期拒绝 |
| 响应回报的实体码 / 一致性 | 查询期校验：实体码不符即拒（67000012），一致性不符即拒（67000017） |

> 🔴 **走 Bean 意味着自行承担防线**（方案 R-2 的固有代价：离开通用执行器就不再受通用防线约束）：
> ① 必须按 `tenantId` 过滤；② 必须排除已删除数据；③ 必须自行保证 SQL 参数化。
> 这三条**无法由 starter 校验**——它拿到的是一个 SQL 由你写的世界。

---

## 六、字段目录导入（`seed-mode: init`）

同一份 YAML 有两个消费面：取数执行读 `fields[]` 的执行属性；带 `catalog` 的字段还能被写进
字段目录表 `t_<域>_field_catalog`（23 列，`AnalyticalFieldCatalogSeeder.CATALOG_COLUMNS`）。
**一份声明不写两遍**——两份写法的漂移是静默的（目录说可筛选、取数声明说不可筛选，两侧各自都「对」）。

- **默认 `none`**：不写这项配置就**不碰数据库**。导入是**一次性**动作，默认开启会让每次启动都写库，
  而「每次都写」与「幂等」在日志上不可区分，运维无从判断表到底是谁写的。
- **INIT-ONLY**：表非空即整体跳过、**不比对、不更新**——此后**表为权威**。
  改 YAML **不会**回写已灌种子的库。需要变更目录时走属主域的发布流程，不是改 YAML 重启。
- **三条结构性拒绝**：目录表带 `catalog_version` 列（已版本化，代写会造出没有版本头的行，
  读取侧随即判定整个目录损坏）⇒ 退让并指向发布流程；目录表缺导入列（多半是迁移没跑全）⇒ 拒绝，
  且错误方向指向迁移而不是数据库；显式开了 `init` 却一行没写 ⇒ 拒绝（否则「导入成功」与
  「配置写错位置」在运行期完全同形）。

---

## 七、启动与失败行为

| 时机 | 行为 |
|---|---|
| 启动就绪（`ApplicationReadyEvent`） | 逐实体核验：标识符白名单 + 表结构对账（表、租户列、主体列、时点列、每个字段列是否真的存在）。**任一不通过即抛异常、服务不予进入可用状态**；逐实体**一次报全**，不是遇到第一个就停 |
| 启动就绪，但未声明任何实体 | **不是失败**：端点已就绪，任何取数请求得到「实体未声明」的明确拒绝（67000019）。「还没配完」不该升级成「服务起不来」 |
| 查询期 | 声明与表结构矛盾是**确定性缺陷**，不会自己变好，故**不降级**（如「列不存在就把这个条件去掉」——少一个条件会把「查这个主体的」变成「查所有主体的」） |

核验挂在 `ApplicationReadyEvent` 而非 `@PostConstruct`：建表由 Liquibase 完成，
两者顺序没有约定，过早核验会把「表还没建」误报成「声明写错了」，而处置方向相反。

---

## 八、已知边界

- **握手端点无消费方**：R-14 的一致性对账在 feature-center 侧尚未接入，本端点的输出当前只有人工核查在用。
- **Bean 路径三条义务不可校验**（见 §五）：租户过滤、删除标记、参数化由实现者承担。
- **`AVG` 的精度口径**：基准实现用 `MathContext.DECIMAL128` 做除法，而本模块交给数据库的 `AVG`
  （MySQL 的 `DECIMAL(p, s+4)`）。两者的舍入可能不同——与「NULL 度量守卫」同族但尚未收口。
- **`groupBy` 未开放**：契约保留字段、执行侧一律拒绝（67000021）。
- **目录导入不覆盖已版本化的目录**（见 §六）：`catalog_version` 存在即退让。
- **`seed-mode: init` 不取锁**：多实例同时首次启动会各写一遍，靠目录表主键让第二次写入**显式失败**
  而不是静默重复——「启动报主键冲突」可以处置，「目录里有两份同字段的行」不能。

---

## 九、模块内部结构

```
com.titanium.analytical
├── AnalyticalConsistency          一致性等级（STRONG / EVENTUAL）
├── AnalyticalQueryException       携带 BaseErrorCode 的业务异常
├── config/
│   ├── AnalyticalAutoConfiguration   自动装配（7 个 Bean；afterName 锚定 DataSourceAutoConfiguration）
│   ├── AnalyticalEntityProperties    YAML 绑定入口（titanium.analytical.*）
│   ├── AnalyticalEntityDefinition    单实体声明
│   ├── AnalyticalFieldDefinition     单字段的执行面声明
│   ├── AnalyticalFieldCatalogDeclaration  单字段的目录面声明（14 列）
│   ├── AnalyticalReadinessVerifier   启动期核验（失败关闭）
│   └── SeedMode                      none / init
├── query/
│   ├── AnalyticalQueryTranslator     结构化条件树 → 参数化 SQL（核心）
│   ├── AnalyticalTableSchemaGuard    JDBC 元数据对账（含删除标记列形态判定）
│   ├── AnalyticalValueNormalizer     入参按声明类型解析、出参按线格式渲染
│   ├── AnalyticalSqlIdentifiers      标识符白名单
│   ├── AnalyticalEntitySource        逃生舱接口
│   ├── AnalyticalQueryService        分派 → 执行 → 组装
│   └── TranslatedQuery               SQL + 参数 + 取值计划 + 空值守卫（值不外泄）
├── seed/AnalyticalFieldCatalogSeeder 字段目录 INIT-ONLY 导入
└── web/AnalyticalQueryController     两个端点（控制器内 @ExceptionHandler，不设全局 advice）
```

🔴 `@AutoConfiguration` 的排序：默认按**类名字母序**排序，
而 `com.titanium.*` 排在 `org.springframework.*` **之前**。若不显式声明
`afterName = "...DataSourceAutoConfiguration"`，`@ConditionalOnBean(DataSource.class)`
会在数据源注册**之前**被求值，条件判否 ⇒ **整份装配静默不生效**：应用正常启动、日志无异常、接口 404，
症状与「依赖没加进去」完全同形。改动本模块时不要动这一行。

---

## 十、测试

`mvn -o test` 覆盖 61 个用例：

| 测试类 | 用例 | 覆盖 |
|---|:--:|---|
| `AnalyticalQueryEndpointIntegrationTest` | 14 | 真实容器 + 真实 HTTP：装配生效、三条防线、空集合语义、时序、NULL 守卫、参数化 |
| `AnalyticalQueryRejectionIntegrationTest` | 12 | 拒绝路径：未声明实体/字段、能力位、跨主体、分组、结构非法 |
| `AnalyticalQueryTranslatorTest` | 14 | 翻译器产物：SQL 文本无请求值、占位符与参数一一对应、别名不进 SQL |
| `AnalyticalEntitySourceTest` | 9 | 逃生舱：分派、构造期冲突拒绝、响应回报校验 |
| `AnalyticalFieldCatalogSeederTest` | 7 | 目录导入：三条结构性拒绝、INIT-ONLY、23 列写入形状 |
| `AnalyticalEntitySourceIntegrationTest` | 2 | 逃生舱在真实容器里被注入并接管 |
| `AnalyticalNoBusinessDomainDependencyTest` | 3 | 依赖纪律（ArchUnit） |

测试引导类 `AnalyticalTestApplication` **刻意放在独立子包 `testapp`**，使 `@ComponentScan`
扫不到 `AnalyticalQueryController`——于是「自动装配生效」成为可证伪判据：若装配静默失效，
全部端点 404、用例失败。若把它挪到父包，这条判据即刻作废。
