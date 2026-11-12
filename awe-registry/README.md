# AWE Registry：类型化模型生产与来源一致性

Registry 是 AWE 数仓产品的 Authoring 边界，使用真实 PostgreSQL 持久化。它不执行任意 SQL、不运行独立 Agent，也不因模型已发布就宣称真实数据任务已经完成。

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
mvn -B -ntp verify
java -jar awe-registry/target/awe-registry-0.1.0-SNAPSHOT.jar
```

工作台由注册服务的 `/` 提供，Token 只留在页面内存中。Java 子模块依赖由根目录 reactor 构建；不要在聚合 POM 上执行 `spring-boot:run`。

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

Contract 当前支持 `check.model.static` 和 `check.source.consistency` 的受支持建模/发布阶段检查；未知阻断规则或未来执行阶段失败关闭。Function/Action 发布只是声明，不能触发查询、回补或任务重跑。Evidence 绑定真实目标发布 Hash 和已保存来源快照；来源范围可沿精确的 Dataset/subject/owner 实现链继承，不沿任意依赖边扩张。作者提供的 `PASSED` 不是服务器来源检查或业务真值认证。

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
| `GET /api/gaps` | 持久化问题候选及状态 |
| `GET /api/sources/configuration` | 仅返回配置状态，不返回地址或凭据 |
| `POST /api/sources/{system}/imports` | 正文 `{"externalRef":"..."}`；保存来源观测 |
| `GET /api/sources/{system}/snapshots?externalRef=...` | 历史来源快照索引 |
| `GET /api/sources/snapshots/{id}` | 来源快照文档 |
| `GET /api/sources/{system}/checks?externalRef=...` | 追加的来源检查记录 |

编辑草稿的 PUT 正文须包含 `{"spec":{...},"expectedRevision":1}`。过期版本返回 409，避免两个编辑页面静默覆盖。提交、审批与发布均在数据库行锁下检查状态；不同草稿发布同一实体版本也会串行检查。

## PostgreSQL 与 HTTP 集成测试

CI 启动真实 PostgreSQL，设置 `AWE_TEST_POSTGRES=true`，运行完整 `mvn -B -ntp verify`，并确认集成测试数量非零且没有跳过。没有此变量时，数据库集成测试跳过，不能据此宣称已验证 Registry。

```sh
# 先在独立测试库中配置 AWE_DB_URL/USER/PASSWORD 及两个不同 Token
AWE_TEST_POSTGRES=true mvn -B -ntp verify
```

使用可丢弃的测试库，避免同应用/生产数据混用。测试覆盖类型、序列化、版本兼容性、错误模型、Flyway、真实 HTTP 鉴权、来源导入、发布重读、物理契约不符的持久化证明和漂移拒绝。本地 HTTP 网关 fixture 是可控协议测试来源，不冒充真实 Hive/BI 生产连接。

## 生产门槛与下一纵切面

目前两个 Bootstrap 服务 Token 不代表真实个人身份，也没有领域级 RBAC；默认只监听回环。正式接入前仍需 OIDC/SSO、个人审核追溯、源端二次授权、HTTPS、行列/领域策略、资源预算和审计治理。

当前没有真实任务执行器、生产 DDL/SQL 发布集成、行级质量/数值复算或任务修复回放。下一纵切面必须使真实数据任务消费精确 Release/Hash 与来源快照，执行契约并返回范围明确的证据；发现错误后推动真实数仓更改，并以原任务和回归验收关闭。详见 [产品路线](../docs/roadmap.md) 和 [ADR-013](../docs/architecture/adr-013-typed-registry-and-source-gates.md)。
