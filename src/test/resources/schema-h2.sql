-- 分析型取数的测试表结构（H2）。
--
-- 🔴 这里刻意**用视图的命名习惯**（v_analytical_*）建表：方案 R-2 第三道防线推荐各域声明
--    专用分析视图，starter 对「表」与「视图」一视同仁（都只当标识符用）。
--    测试用表实现，是为了能在同一份 schema 里同时覆盖两种删除标记列形态。
--
-- 列名一律小写书写（H2 会折叠成大写存储，starter 的元数据核验按三种写法探测，两条路径都覆盖）。
--
-- 🔴 一律 IF NOT EXISTS：H2 是 `mem:analytical-it;DB_CLOSE_DELAY=-1`，同一个 JVM 里
--    多个 Spring 上下文**共用同一个库**，而本脚本挂在 `spring.sql.init.mode: always`
--    上（每个上下文都会执行一次）。不带 IF NOT EXISTS 时，第二个上下文启动即死于
--    「表已存在」——症状是**单跑该测试类全绿、全量跑却报上下文加载失败**，
--    而错误方向指向那个测试类（像是它自己配错了），真因却是两份上下文的初始化撞车。
--    数据面由各测试类的 clearTables() 各自负责，与本语句无关。

-- 主测试表：数值型删除标记列（TINYINT ⇒ starter 走 `<col> = 0` 分支）
CREATE TABLE IF NOT EXISTS v_analytical_policy (
    id           VARCHAR(32)  NOT NULL PRIMARY KEY,
    tenant_id    VARCHAR(32)  NOT NULL,
    policy_id    VARCHAR(32)  NOT NULL,
    holder_name  VARCHAR(64),
    premium      DECIMAL(18, 2),
    sum_insured  INTEGER,
    status       VARCHAR(16),
    issue_date   DATE,
    secret_note  VARCHAR(128),
    is_deleted   TINYINT      NOT NULL DEFAULT 0
);

-- 时效表：布尔型删除标记列（BOOLEAN ⇒ starter 走 `<col> = FALSE` 分支）
CREATE TABLE IF NOT EXISTS v_analytical_endorsement (
    id             VARCHAR(32) NOT NULL PRIMARY KEY,
    tenant_id      VARCHAR(32) NOT NULL,
    policy_id      VARCHAR(32) NOT NULL,
    fee            DECIMAL(18, 2),
    effective_from TIMESTAMP,
    effective_to   TIMESTAMP,
    is_deleted     BOOLEAN     NOT NULL DEFAULT FALSE
);

-- 无删除标记列的表（YAML 声明 deleted-column: none，由元数据核验证实）
CREATE TABLE IF NOT EXISTS v_analytical_free (
    id        VARCHAR(32) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(32) NOT NULL,
    policy_id VARCHAR(32) NOT NULL,
    amount    DECIMAL(18, 2)
);
