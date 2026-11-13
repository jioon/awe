# AWE Registry：类型化模型生产、来源一致性与数据验证

Registry 承载 AWE 数仓产品的 Authoring 与首个受控只读 Runtime，使用真实 PostgreSQL 持久化。模型、契约和运行记录共同约束真实数据检查；服务不执行调用方任意 SQL、脚本或数仓写入，也不因模型已发布就宣称任务已经完成。

M1.1 新增独立消费入口：已发布 Function/Dataset/输出行 Schema 约束真实 PostgreSQL 取数，包含消费策略、显式字段/时间范围、预算与结果证据。配置、注册模型和接口见 [受控取数运行说明](CONSUMPTION.md)；它不代替下述三类质量检查，也不代表完整指标问数或企业平台接入已完成。

## 开发与启动

需要完整 JDK 21、Maven 3.9+、预先创建的 PostgreSQL 15+ 数据库（CI 使用 PostgreSQL 17）。配置以下环境变量，值由本地或部署环境提供，不把凭据写入仓库：

| 变量 | 用途 / 默认值 |
|---|---|
| `AWE_DB_URL` | JDBC PostgreSQL URL；默认 `jdbc:postgresql://127.0.0.1:5432/awe` |
| `AWE_DB_USER` / `AWE_DB_PASSWORD` | 数据库身份与密码 |
| `AWE_AUTHOR_TOKEN` | 本地开发作者服务 Token |
| `AWE_REVIEWER_TOKEN` | 不同且非空的审核服务 Token |
| `AWE_BIND_ADDRESS` / `AWE_PORT` | 默认 `127.0.0.1` / `8090` |

从仓库根目录构建和启动：

```sh
mvn -B -ntp spotless:check verify
java -jar awe-registry/target/awe-registry-0.1.0-SNAPSHOT.jar
```

工作台由注册服务的 `/` 提供，Token 只留在页面内存中。Java 子模块依赖由根目录 reactor 构建；不要在聚合 POM 上执行 `spring-boot:run`。代码修改后可运行 `mvn -B -ntp spotless:apply`，再执行格式检查与构建；Java 统一采用 Google Java Format AOSP 四空格样式。

## 模型文档与发布

新提交采用 `schemaVersion: 2`。`spec.model` 对应 `awe-kernel` 中完整 Java 记录，包含明确自身版本。支持 `ObjectType`、`LinkType`、`Dataset`、`Metric`、`Feature`、`FunctionDefinition`、`ActionDefinition`、`ContractDefinition`、`EvidenceAssertion`；三个语义分支合属 `SemanticModel` 家族。

例如作者创建对象草稿：

```json
{
  "domain": "video",
  "entityId": "entity.video.user",
  "entityType": "ObjectType",
  "spec": {
    "schemaVersion": 2,
    "name": "用户",
    "definition": "经领域负责人确认的注册用户身份",
    "ownerTeam": "video_dw",
    "model": {
      "ref": {"id": "entity.video.user", "modelVersion": "1.0.0"},
      "family": "BUSINESS_ENTITY",
      "description": "业务用户定义",
      "properties": [{"name": "user_id", "type": "STRING", "required": true, "sensitive": false}],
      "identityKeys": ["user_id"]
    }
  }
}
```

流程为创建/编辑 → 校验 → 作者提交 → 不同 Reviewer 批准 → Reviewer 发布。发布请求 `version` 必须等于文档自身版本。依赖是 `{id,modelVersion}`，必须存在该精确发布并符合目标模型家族；Function/Action 的断言字符串使用 `assertion.<domain>.<name>@<version>`。

未知字段、错误字段类型、缺少记录字段、重复集合和内核不变量失败会被拒绝。破坏性模型变更需要提高主版本；即使允许主版本变更，也必须检查下游影响。Hash 覆盖完整发布身份、类型、版本、规范文档，以及精确依赖与来源快照清单；清单记录依赖的 Release/Hash、来源快照 ID/版本/指纹。旧发布、Hash 和审计历史只读保留；旧草稿必须由作者明确补全 v2 文档，系统不猜测缺失身份、粒度或时间。

Contract 支持 `check.model.static` 和 `check.source.consistency` 的受支持建模/发布阶段检查，以及下述已注册的 EXECUTE 数据检查规则；未支持的阻断规则或阶段失败关闭。EXECUTE 契约发布只检查规则与适用性，不预先宣称数据运行通过。Function/Action 发布只是声明，不能触发查询、回补或任务重跑。Evidence 绑定真实目标发布 Hash 和已保存来源快照；来源范围可沿精确的 Dataset/subject/owner 实现链继承，不沿任意依赖边扩张。作者提供的 `PASSED` 不是服务器来源检查或业务真值认证。

`ObjectType` 的直接来源绑定检查属性存在性、兼容物理类型、可空性及身份键；Dataset 检查声明粒度与时间映射。Metric/Feature 即使没有直接绑定，也重新检查其精确版本 Dataset 的来源，并检查主体、时间、维度、字段及适用的聚合/可获得时点契约。声明元数据满足这些检查不等于真实数据行已满足契约。

## Hive / BI 来源接入

可扩展 `SourceAdapter` SPI 的首个实现读取现有平台的元数据 HTTP 网关。当前没有配置真实企业连接时，返回 `NOT_CONFIGURED`，不生成伪造模型或成功快照。这个实现不是 Hive Metastore/HiveServer2 或某个 BI 产品的原生驱动；已有平台需实现以下明确协议，其他接入方式可增加适配器。

| 变量 | 用途 |
|---|---|
| `AWE_HIVE_METADATA_URL` | Hive 元数据网关基地址 |
| `AWE_HIVE_METADATA_TOKEN` | 可选网关 Bearer Token |
| `AWE_BI_METADATA_URL` | BI 元数据网关基地址 |
| `AWE_BI_METADATA_TOKEN` | 可选网关 Bearer Token |

配置只能来自部署环境。生产网关要求 HTTPS；仅回环测试允许 HTTP。请求地址为 `GET <base>/assets/<UTF-8-percent-encoded-externalRef>`，不跟随重定向，连接/响应有超时和大小限制。远程连接遵循已有代理与 CA 信任；本云环境若使用完整 JDK，需保留系统代理 CA 信任库，例如 JVM 参数 `-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts`。不得关闭 TLS 验证。

网关返回 `application/json`，协议示例：

```json
{
  "externalRef": "hive.dwd_activity",
  "sourceVersion": "catalog-revision-42",
  "columns": [
    {"name": "event_id", "type": "string", "nullable": false},
    {"name": "event_time", "type": "timestamp", "nullable": false}
  ],
  "grainKeys": ["event_id"],
  "timeFields": ["event_time"]
}
```

顶层只允许上述字段和可选对象 `definition`。BI 语义绑定要求 `definition` 为对应候选 `model` 移除根 `ref` 后的完整内核记录（包括粒度、时间、聚合和依赖等适用字段），由网关对权威 BI 口径进行明确适配；缺失或不一致返回 `CONTRACT_MISMATCH` 并阻断发布。只匹配列或 SQL 文本不能代替此声明语义比较。Hive 元数据检查不宣称 BI 语义等价。列名唯一；粒度/时间字段必须引用存在的列；源版本必须由来源平台提供。服务端对规范化文档计算 SHA-256 并保存不可变快照，不信任作者自填指纹。

1. 作者调用导入，查看来源资产、指纹、版本、快照和验证限制。
2. 作者补全业务模型，将实际导入指纹写入来源绑定；导入不自动确认业务含义。
3. 语义模型的绑定还提供 `timeFields` 映射，将所有 `TemporalContract.roles` 明确映射到实际列，例如 `{"EVENT_TIME":"event_time"}`；声明粒度目前按同名物理列映射。
4. 校验和发布重新读取来源；预期指纹与新观测不符、模型字段/声明粒度/时间不一致或缺少必要来源均拒绝。来源指纹检查和候选模型契约检查各自持久化：来源可以是 `VERIFIED`，同时模型仍因 `CONTRACT_MISMATCH` 被拒绝；两者不能合并成一个成功标签。候选检查记录规范文档 Hash，与最终完整发布 Hash 有明确区别。
5. 发布后重新验证保留每次检查，确定漂移生成去重问题候选和依赖影响；恢复原绑定及契约后重新检查才可标记来源问题恢复，不能通过点击确认绕过。

来源观测区分 `IMPORTED`、`VERIFIED`、`DRIFTED`、`NOT_CONFIGURED`、`NOT_FOUND`、`UNAVAILABLE` 和 `INVALID_RESPONSE`；模型检查还可能返回 `CONTRACT_MISMATCH`。`NOT_FOUND` 仅指配置的网关未返回资产，不证明企业资产不存在。来源检查的 `VERIFIED` 只表示本次来源指纹相符；模型检查的 `VERIFIED` 还包含相应候选物理契约检查。两者都不证明行级唯一性、数据质量、分区就绪、BI 数值等价或业务真值。

来源观测和候选物理契约检查通过独立事务追加，因此发布拒绝回滚后仍能查询失败证明。发布 manifest、绑定和当时的验证状态保持不可变；`POST .../verify` 返回的当前观测独立于旧发布中的历史 `VERIFIED`。同一发布的重检查串行执行，确定的漂移/契约不符才创建去重缺口；未配置和不可达不会自动制造数仓改造工单。

## 真实只读数据检查与修复回放

运行固定已发布 Dataset 与 Contract 的精确 ID/Hash；Contract 的 subject 必须是该 Dataset 的精确版本。首先重读元数据并通过来源/物理契约 Gate，然后用运维配置的 PostgreSQL 关系执行注册规则。当前支持：

| EXECUTE 规则 | 检查 |
|---|---|
| `check.grain.unique` | Dataset 粒度键的重复 |
| `check.grain.not_null` | Dataset 粒度键空值 |
| `check.time.available_after_event` | 绑定的可获得时间不早于事件时间 |

规则由服务端实现，调用方不传 SQL、表名、数据库地址或脚本。首个运行能力要求 Dataset 恰有一个直接来源绑定；多绑定联合执行尚未支持。Function/Action 仍是可发布声明；本轮数据检查接口不执行 Action、回补或任务重跑。

配置通过环境或安全的部署配置提供，源端身份须由运维另行授予只读权限：

| 变量 | 用途 / 范围 |
|---|---|
| `AWE_WAREHOUSE_JDBC_URL` | 实际验证 PostgreSQL 的 JDBC URL；缺少配置返回 `NOT_CONFIGURED` |
| `AWE_WAREHOUSE_JDBC_USER` / `AWE_WAREHOUSE_JDBC_PASSWORD` | 只读数据源身份；与 Registry 持久化身份分开配置 |
| `AWE_WAREHOUSE_ASSET_BINDINGS` | 来源资产到允许的 `schema.table` 的 JSON 映射，例如 `{"hive:runtime.events":"public.awe_test_events"}` |
| `AWE_WAREHOUSE_MAX_ROWS` | 默认 10000，上限 100000；不由运行请求放大 |

映射表示本次读取证据的关系。把 `hive` 资产映射到 PostgreSQL 并不表示实现了 Hive 原生数据读取；原生 Hive/Spark/BI 运行适配仍待开发。远程 PostgreSQL 要求 `sslmode=verify-full`，保留 CA 与主机名校验；本地回环测试可用普通 TCP。

读取使用 read-only、repeatable-read 事务，连接/查询有超时，扫描 `LIMIT(maxRows+1)` 判断覆盖范围。空关系和超限均为 `INCONCLUSIVE`，不会把零行或部分行当成全量通过；缺少连接或关系映射为 `NOT_CONFIGURED`，不可达为 `UNAVAILABLE`。所配置关系启用 RLS、粒度列使用非确定性排序规则或检查超出支持的值/内存预算时返回 `INCONCLUSIVE`，不把权限子集或不同等价语义当成完整通过。真实义务失败为 `FAILED`，本次注册 EXECUTE 规则的完整检查通过才为 `PASSED`；其他契约阶段会列入 `limitations`，不冒充已运行。元数据 Gate 失败记录 `BLOCKED`，缺少来源配置仍为 `NOT_CONFIGURED`。

创建运行的请求：

```json
{
  "releaseId": "<Dataset release UUID>",
  "contentHash": "<Dataset release SHA-256>",
  "contractReleaseId": "<Contract release UUID>",
  "contractContentHash": "<Contract release SHA-256>",
  "idempotencyKey": "quality-check-2026-11-12-001"
}
```

使用 `POST /api/data-runs` 提交；身份由服务器的 Bearer Token 判定。Run 返回 `id`、`status`、`model_release_id` / `model_content_hash`、`contract_release_id` / `contract_content_hash`、元数据 `source_snapshot_id`、实际 `data_snapshot_ref`、`rows_checked`、`violations` 和 `limitations` 等。同一服务器身份下相同幂等请求复用已有记录，不能借同一个键替换模型或契约。`RUNNING` 表示尚未完成，应查询状态，不能解释为通过；当前同步运行机制不宣称跨进程任务自动恢复。

`data_snapshot_ref` 格式为 `postgresql:binding:<bindingHash>:tx_snapshot_sha256:<snapshotHash>`。前者是 `{jdbcURL,jdbcUser,relation,sourceSystem,externalRef}` 的 SHA-256，后者是实际 PostgreSQL 读取事务快照的 SHA-256；映射修订可观察，连接串与证书路径不暴露，大事务快照也不会被截断。事务结束后该标识不能用来恢复历史行，不是可恢复的数据版本。`PASSED` 只证明本次关系、规则、事务可见数据和扫描范围，不证明其他分区、未来数据、BI 数值等价或业务真值。

真实失败会形成运行缺口。领域团队或外部授权工具修改真实数据后，调用 `POST /api/data-runs/{id}/replays`，例如：

```json
{
  "idempotencyKey": "repair-replay-2026-11-12-001",
  "repairNote": "在现有数仓流程中修复重复事件后，按原契约重新检查"
}
```

回放重新读取当前真实数据，并固定原 Dataset、Contract 和规则。`replay_of` 关联原始运行，`repair_note` 记录修复说明；旧 Run 和证据不改写。运行缺口的 `failed_run_id` 指向首次得到真实失败的 Run，`original_run_id` 关联原始请求意图。只有同发布/契约检查真正通过，且 binding Hash 与该失败证据相同，才关闭对应缺口；事务快照 Hash 可随修复后的新数据变化。更换 JDBC 目标、源端配置身份或关系映射后，新关系的检查可以 `PASSED`，但原缺口保持 `OPEN` 并返回范围变化说明。说明文字、幂等重试、放宽契约、数据不可达或不完整扫描均不能代替修复验收。元数据问题列表与运行问题列表分开保留。

Agent 可在自己的仓库或流程平台保存 Skills，组合查发布、创建运行、查看缺口和修复回放等步骤；所有步骤继续受相同服务器 Gate 约束。AWE 不托管或执行 Skill 中的任意代码。完整边界见 [ADR-014](../docs/architecture/adr-014-agent-skills-and-data-verification-loop.md)。

## HTTP 接口

所有 `/api/` 请求需要服务 Bearer Token；查询允许两种身份，常规写入为 Author，批准/发布为 Reviewer。

| 接口 | 用途 |
|---|---|
| `GET/POST /api/domains` | 领域查询与创建 |
| `GET/POST /api/drafts`；`GET/PUT /api/drafts/{id}` | 草稿创建、查询与编辑 |
| `POST /api/drafts/{id}/validate` | 类型、依赖、兼容性及来源检查 |
| `POST /api/drafts/{id}/submit` | 作者提交审核 |
| `POST /api/drafts/{id}/approve` | Reviewer 审批 |
| `POST /api/drafts/{id}/publish` | Reviewer 发布，正文 `{"version":"1.0.0"}` |
| `GET /api/releases`；`GET /api/releases/{id}` | 不可变发布、版本依赖、绑定与 Hash |
| `POST /api/releases/{id}/verify` | 新来源观测及漂移检查 |
| `GET /api/impact?entityId=...` | 下游依赖影响 |
| `GET /api/audit?entityId=...` | 模型审核与发布事件 |
| `GET /api/validation-checks?entityId=...` | 候选物理契约检查与失败证明，包含候选 Hash |
| `GET /api/gaps` | 元数据/模型来源问题候选及状态 |
| `POST /api/data-runs`；`GET /api/data-runs` | 创建与查询受控实际数据检查运行 |
| `GET /api/data-runs/{id}` | 运行版本、检查范围、违规计数与证据 |
| `POST /api/data-runs/{id}/replays` | 原版本、原契约的修复后重新检查 |
| `GET /api/data-gaps` | 实际数据义务失败及修复回放状态 |
| `GET /api/sources/configuration` | 仅返回配置状态，不返回地址或凭据 |
| `POST /api/sources/{system}/imports` | 正文 `{"externalRef":"..."}`；保存来源观测 |
| `GET /api/sources/{system}/snapshots?externalRef=...` | 历史来源快照索引 |
| `GET /api/sources/snapshots/{id}` | 来源快照文档 |
| `GET /api/sources/{system}/checks?externalRef=...` | 追加的来源检查记录 |

编辑草稿的 PUT 正文须包含 `{"spec":{...},"expectedRevision":1}`。过期版本返回 409，避免两个编辑页面静默覆盖。提交、审批与发布均在数据库行锁下检查状态；不同草稿发布同一实体版本也会串行检查。

## PostgreSQL 与 HTTP 集成测试

CI 启动真实 PostgreSQL，设置 `AWE_TEST_POSTGRES=true`，运行格式检查与完整 `mvn -B -ntp spotless:check verify`，并确认集成测试数量非零且没有跳过。没有此变量时，数据库集成测试跳过，不能据此宣称已验证 Registry。

```sh
# 先在独立测试库中配置 AWE_DB_URL/USER/PASSWORD 及两个不同 Token
AWE_TEST_POSTGRES=true mvn -B -ntp spotless:check verify
```

使用可丢弃的测试库，避免同应用/生产数据混用。测试覆盖类型、序列化、版本兼容性、错误模型、Flyway、真实 HTTP 鉴权、来源导入、发布重读、物理契约不符的持久化证明和漂移拒绝；运行集成测试在真实 PostgreSQL 中放入错误行，调用真实接口获得失败，再在 AWE 外修复数据并按相同契约回放。空关系、超限、未配置、幂等与错误版本必须覆盖。HTTP 网关 fixture 和测试关系证明本增量的协议/运行路径，不冒充真实企业 Hive/BI 连接。

## 生产门槛与下一纵切面

目前两个 Bootstrap 服务 Token 不代表真实个人身份，也没有领域级 RBAC；默认只监听回环。正式接入前仍需 OIDC/SSO、个人审核追溯、源端二次授权、HTTPS、行列/领域策略、资源预算和审计治理。

本轮已把精确版本模型/契约接入真实只读数据验证和外部修复后的同契约回放。生产目标至少覆盖 F3，但 F1 企业来源接入与领域验收、F2 Resolver/Semantic IR/完整语义查询、生产 DDL/SQL/作业发布集成、原生引擎适配、完整质量/数值复算和下游回归仍未完成。不能把这个先行检查链写成所有生产消费任务的闭环已经完成。详见 [产品路线](../docs/roadmap.md)、[ADR-013](../docs/architecture/adr-013-typed-registry-and-source-gates.md) 和 [ADR-014](../docs/architecture/adr-014-agent-skills-and-data-verification-loop.md)。
