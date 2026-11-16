# M1：正式数据能力、知识发现与修复回归

架构边界见 [ADR-018](../docs/architecture/adr-018-executable-capabilities-and-production-admission.md)。[运行说明](README.md) 中的 PostgreSQL Registry、作者/审核者、来源绑定及 v2 EXTRACT 继续有效。迁移 V6/V7 由 Flyway 执行；不要改写既有发布或历史证据。

## v3 模型与执行

七类模型不变。信封 `schemaVersion: 3` 在 `model` 之外增加契约 facet，严格拒绝未知键。仅需要新执行能力的模型迁移 v3，已有 v2 Release/Hash 保持有效。

| facet | 必要语义 | Java 契约 |
|---|---|---|
| `datasetContract` | `rowSchema` 精确版本；`mode` 为 OBSERVED_SOURCE/LOGICAL_TARGET；`targetBindingRef`；`readMode` EVENT_WINDOW/STATIC_SNAPSHOT；`rangeRole` | [DatasetContract](../awe-kernel/src/main/java/ai/awe/kernel/contract/DatasetContract.java) |
| `metricContract` | 统计对象、身份/空值、单位、时间角色/时区、输入、IR、输出/粒度、空集/除零、可选 DIRECT 实现 | [MetricContract](../awe-kernel/src/main/java/ai/awe/kernel/contract/MetricContract.java) |
| `pipelineContract` | 输入、逻辑目标/行 Schema/转换 IR、必需 Contract、MODEL_COMPILED/REGISTERED_EXTERNAL | [生产准入](PRODUCTION.md) |

Dataset 的读能力：事件窗口使用 `dataset.scan.v1`，必需 from/to TIMESTAMP；静态快照使用 `dataset.snapshot.v1`，参数为空。执行器分别为 `postgres.dataset.scan.v1`/`postgres.dataset.snapshot.v1`，或对应 `warehouse.*` 供外部平台使用。输出为显式行 Schema 的 `id@modelVersion`。静态快照使用 OBSERVED_AT，不能伪造事件时间列。

指标读能力为 `metric.query.v1`，owner 为 v3 Metric，必需 from/to TIMESTAMP；`resultSchemaRef` 为 Metric 自身 `id@modelVersion`。执行器为 `postgres.metric.query.v1` 或 `bi.metric.query.v1`；BI 要求完整 DIRECT 绑定，不自动回退。

IR 只含类型化 Scan、Filter、Project、Aggregate、Join、集合运算；表达式含字段、类型化常量、业务日和算术。JSON 使用 `kind` 标签；Scan 窗口偏移按模型时区的日历日计算；Join/集合/算术分别使用 `joinKind`/`operation`。执行前检查字段类型/可空性、字段来源、Link 和预算，并校验实际关联唯一性与输出粒度。单位是发布与版本绑定的显式声明，当前不自动推导任意表达式的物理量纲或单位换算；需领域审核和独立参考。NULL 身份 IGNORE/REJECT 在已授权输入总体上实施。空输入的 ZERO 与有记录但聚合值为 NULL 不混同。

完整有效模型样本及独立 SQL 断言见 [MetricQueryIntegrationTest](src/test/java/ai/awe/registry/MetricQueryIntegrationTest.java)、[共享模型构造](src/test/java/ai/awe/registry/MetricQueryTestSupport.java)；严格编码和 v2/v3 兼容反例见 [ExecutableFacetCodecTest](src/test/java/ai/awe/registry/ExecutableFacetCodecTest.java)。

## REST 与 MCP

消费凭据独立于作者/审核者，OIDC 和行列规则见 [IDENTITY.md](IDENTITY.md)。以下消费接口共用同一内核：

| 接口 | 行为 |
|---|---|
| `GET /api/capabilities` | 当前身份可消费的精确 Function/Hash、类型、用途、限制；发现不保证当前数据到齐 |
| `POST /api/data-tasks` | v1 EXTRACT 或 v2 QUERY_METRIC；服务端固定模型与强制行范围 |
| `GET /api/data-tasks[/{id}]` | 主体与当前政策内证据；结果有独立 TTL，历史证据不因此删除 |
| `POST /api/semantic-plans/validate` | 校验同一正式指标请求；不接受自由 SQL 或调用方替换 IR |
| `POST /api/knowledge/resolve`、`assess` | 正式能力与审定别名的解析/评估，保存不可变观测 |
| `GET /api/knowledge/observations/{id}`、`coverage` | 当前权限内观测和审定覆盖 |
| `POST /api/mcp` | JSON-RPC initialize、tools/list、tools/call，工具复用上列服务 |

v2 请求（Release/Hash 必须来自发布接口）：

```json
{
  "protocolVersion": 2,
  "intent": "QUERY_METRIC",
  "proposedFunction": {"releaseId": "<UUID>", "contentHash": "<sha256>"},
  "requirements": {
    "from": "2026-08-18T00:00:00Z", "to": "2026-08-19T00:00:00Z",
    "view": "OBSERVED_SNAPSHOT", "verification": "OBSERVE"
  },
  "consumer": {"purpose": "analysis"},
  "constraints": {"maxRows": 100, "maxBytes": 65536},
  "idempotencyKey": "request-unique-key"
}
```

`PERIOD_COMPLETE` 要求所有实际输入的到齐证明；`REQUIRE_INDEPENDENT` 要求注册 checker 的独立参考比较。未配置不能自动降级为通过。结果中分别保存 decision、stages、resolved、snapshots、executionEvidence、readinessEvidence、verification、limitations。0、NULL、NO_RECORD、NOT_APPLICABLE 和 NOT_READY 含义不同；超预算或证明不充分不返回部分结果充当完整结果。

Resolver 请求可用 `{ "text": "设备日活", "domain": "activity", "intent": "QUERY_METRIC" }`，或正式 functionId / releaseId+contentHash。精确候选、模糊需确认、多候选需消歧分开；不使用全局“昨天/最新”默认值。它是有界正式能力检索，不是任意自然语言分析器。

作者 `POST /api/knowledge/coverage` 提交 domain/version/ownerTeam/validUntil/complete/operations/assetReleases/aliases/sourceAssets/limitations，审核者独立批准 `.../{id}/approve`。assetReleases 使用精确 Release/Hash；alias 指向正式 Function；sourceAssets 使用真实来源 snapshotId。完整覆盖不等于所有业务问题可回答；只有覆盖命名空间内正式能力的精确不存在能证明相应缺失。

## 真实适配与证明协议

网关 URL/Token 仅由管理员配置。HTTPS（测试回环可 HTTP），不跟随重定向；有响应字节和超时限制。调用方不能提供任意 URL、连接、SQL 或检查器。

| 环境变量 | 作用 |
|---|---|
| `AWE_BI_QUERY_URL`、`AWE_BI_QUERY_TOKEN` | 可信网关，POST `/query` |
| `AWE_BI_QUERY_BINDINGS` | JSON 对象，bindingRef → `{capabilityRef,implementationVersion,implementationHash,rowScopeMode?}` |
| `AWE_DATA_READINESS_URL`、`AWE_DATA_READINESS_TOKEN` | 原生计算的可信数据状态，POST `/readiness` |
| `AWE_METRIC_VERIFICATION_URL`、`AWE_METRIC_VERIFICATION_TOKEN` | 独立参考，POST `/verify` |
| `AWE_METRIC_VERIFICATION_CHECKERS` | JSON 对象，checkerRef → `{implementationVersion,implementationHash}` |
| `AWE_CONSUMPTION_RESULT_TTL_SECONDS` | 60—3600，默认 900；过期后仅保留证明和结果 Hash |

BI 请求固定 protocolVersion、operationId、metric Release/Hash、planHash、capability 实现、scope、consumer、budgets、rowScope、scanWindows、realizations 和 outputContractHash。响应须回显精确 requestHash/模型/计划/实现/scope，提供 readiness 和 result；不是回传一句成功。

readiness 包含 state、observedAt、snapshotRef、runRef、completeFrom/Through；展开窗口、多源和静态快照的 READY 还须提供 `scanCoverage: [{source,kind,snapshotRef,status,from,to}]`，覆盖所有实际依赖（静态 kind=STATIC_SNAPSHOT，状态 OBSERVED/COMPLETE，时间为空）。逻辑输入须精确回显 realizations。result 包含 state、rows；可附 snapshotRef/sourceSnapshots，不能与本次证明矛盾。存在强制行条件时绑定须声明 rowScopeMode=PREDICATE_V1。

原生就绪/独立对账请求同样绑定操作、规范 requestHash、精确快照及 scanWindows。就绪 `coverage` 按源和快照覆盖展开窗口；独立 checker 提供自己的 implementationVersion/Hash、referenceRows/referenceBasis、run/attempt、observedAt、complete 和 sameSnapshotVerified。AWE 比较实际类型/粒度/规范结果，不把被验 rows 发送给 checker 作参考。原生事务 Hash 不能恢复历史行；没有同范围参考能力时应 UNKNOWN/INCONCLUSIVE。

可执行协议参考是 [BiMetricAdapter](src/main/java/ai/awe/registry/consumption/query/BiMetricAdapter.java)、[MetricEvidenceVerifier](src/main/java/ai/awe/registry/consumption/query/MetricEvidenceVerifier.java) 与 [真实 SQL 网关测试](src/test/java/ai/awe/registry/SqlBackedMetricGateway.java)。该参考网关实际执行 SQL，独立查询使用同一受控事务；不代表企业 BI 接入已完成。

## 反馈、回归与工作台

`POST /api/feedback` 关联 observationId、issueCode、stage、description；作者通过 `/{id}/reviews` 记录 classification/ownerTeam/externalIssueRef/note。用户声明不自动成为根因。

`POST /api/regressions/cases` 关联 observationId、expectedState、expectedFingerprint、referenceBasis、referenceEvidenceRef；审核者 `/{id}/approve` 后，消费者 `/{id}/replays` 以新 idempotencyKey 真实重放。Reviewer `POST /api/feedback/{id}/closures` 必须引用匹配且成功的回归证据。模型、请求范围、政策、物理绑定、实现不一致不能关闭原问题。完整字段及反例见 [KnowledgeLoopIntegrationTest](src/test/java/ai/awe/registry/KnowledgeLoopIntegrationTest.java)。

工作台 `/consumption.html` 保留真实 EXTRACT，`/metric-consumption.html` 消费指标并查看阶段/证明，`/knowledge.html` 展示解析、覆盖和修复回归。页面不存储 Token，不默认猜日期，不内置模拟答案。领域问题、角色和来源仍须真实配置。
