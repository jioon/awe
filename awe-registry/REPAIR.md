# 参考回归、控制结局与受审模型替代

设计与证明边界见 [ADR-020](../docs/architecture/adr-020-reviewed-repair-and-outcome-regressions.md)。V10 只追加观测、期望和迁移记录，既有 Release/Hash、caseHash 和失败历史不改写。工作台 `/knowledge.html` 的新增区域调用下面的真实接口。

## 观测一次受控数据请求

消费者：`POST /api/regressions/observations`

```json
{
  "request": {"protocolVersion": 2, "intent": "QUERY_METRIC", "proposedFunction": {"releaseId": "<UUID>", "contentHash": "<sha256>"}, "requirements": {"from": "2026-08-18T00:00:00Z", "to": "2026-08-19T00:00:00Z", "view": "PERIOD_COMPLETE", "verification": "OBSERVE"}, "consumer": {"purpose": "analysis"}, "constraints": {"maxRows": 100, "maxBytes": 65536}, "idempotencyKey": "original-request-key"},
  "idempotencyKey": "observation-key"
}
```

GET `/api/regressions/observations/{id}` 仅当前主体/政策可读。原请求须符合既有 v1/v2 DataTask 格式；缺身份、未知字段或语法错误仍直接 HTTP 拒绝，不生成伪造观测。观测使用新的服务端子操作键，实际走相同权限/模型/来源/预算，可能执行真实只读取数。

成功或业务拒绝都可保留观测。返回含 `id,status,probeStatus,request,resolved,outcome,actualOperationId?`；`outcome` 含 `status,httpStatus,decision,failureStage,noDataReturned`。四轴为 resolution/supply/authorization/readiness。前置拒绝不泄露隐藏资产元数据；阶段是发生位置，不等于已证明根因。

观测接口不复制行载荷，`payloadAvailable=false`、`resultPayloadScope=CHILD_OPERATION_ONLY`；原子操作独立提供受当前权限与 TTL 限制的结果。`noDataReturned` 描述被验数据能力的控制结局，不能因观测省略行载荷就认为成功查询被拒绝。

同主体同键/同载荷/同政策返回原观测，改变载荷或政策返回 409。网络失败应使用原载荷和键重试；确定终态后的新观测使用新键。RUNNING 没有终态证据，不能建立参考或当成拒绝成功。

## 新旧参考格式

旧 `POST /api/regressions/cases` 的 observationId、expectedState、expectedFingerprint、referenceBasis、referenceEvidenceRef 保持兼容。新格式用 `expectation` 替代 expectedState/expectedFingerprint，不能混用：

```json
{
  "observationId": "<真实观测或操作UUID>",
  "expectation": {
    "type": "DATA_OUTCOME", "status": "DENIED", "httpStatus": 403,
    "decision": {"resolution": "NOT_EVALUATED", "supply": "UNKNOWN", "authorization": "DENIED", "readiness": "UNKNOWN"},
    "failureStage": "AUTHORIZATION", "noDataReturned": true
  },
  "referenceBasis": "领域责任方确认该主体和用途应被拒绝",
  "referenceEvidenceRef": "reference:domain-reviewed-policy"
}
```

DATA_OUTCOME 的状态为 DENIED、NOT_DISCLOSED、REJECTED、BLOCKED、NOT_READY、NOT_CONFIGURED、UNAVAILABLE、INCONCLUSIVE 或 FAILED_VERIFICATION。必须明确四轴、阶段和 noDataReturned=true；缺配置和未就绪不能互换。期望由领域责任方审定，不能直接把一次错误响应复制成真值。

DATA_RESULT 使用 `{type:DATA_RESULT,state:RECORDS|NO_RECORD|ZERO|NULL|NOT_APPLICABLE,fingerprint:<sha256>}`；RESOLUTION 使用 `{type:RESOLUTION,state:RESOLVED|AMBIGUOUS|UNRESOLVED,fingerprint:<sha256>}`。新 GET case 包含 expectation/expectationFormat，旧 case 仅派生展示，不重新生成 caseHash。

批准与重放仍是 Reviewer `POST /api/regressions/cases/{id}/approve` 和消费者 `POST .../{id}/replays`（idempotencyKey）。DATA_OUTCOME 重放使用受控观测捕获实际拒绝；其范围绑定规范请求、政策和固定模型，变化的运行/快照句柄不充当新的业务要求。回放真实事实与期望逐项比较。

DATA_OUTCOME 通过标记为 `REVIEWED_EXPECTED_OUTCOME`，**不能作为数据反馈技术闭合或模型替代验收凭据**。数据值参考仍是 `REVIEWED_REFERENCE_REGRESSION`，并非自动独立对账或业务真值认证。重复运行应结合数据状态/参考适用条件审查，不能把参考不匹配自动认定为某个根因。

## 旧问题到修订模型的受审迁移

前提：旧反馈已由作者归因为 MODEL_GAP；旧、新 DATA_RESULT 参考分别批准。两者期望状态/Fingerprint 一致，主体、政策、业务请求和输出语义不变；目标使用同 Function 实体更高发布版本。

QUERY_METRIC v3 的模型修订还要求同一 Metric 实体的更高版本及不同发布。服务端核对原/新请求的 metric Release/Hash 与各自执行时固定的模型一致，并重读不可变发布证明实体和版本，然后才允许比较业务要求时忽略 metric/candidateFunction 版本指针。所有 requirements（包括时间、度量、维度、筛选、主体、单位、时区和时间角色）、用途及预算保持不变。较高 Function 版本不能借此更换 Metric 或降低其版本；v2/v3 之间不作为同一替代协议迁移。

| 接口 | 身份与内容 |
|---|---|
| `POST /api/feedback/{fid}/migrations` | 消费者：fromCaseId、toCaseId、reason、referenceEvidenceRef、idempotencyKey |
| `GET /api/feedback/{fid}/migrations[/{mid}]` | 当前消费者查看自己可见的迁移 |
| `GET .../{mid}/review` | 作者/Reviewer 查看服务端计算的原/新 scope、模型、执行绑定、物理投影差异和签名；不含行载荷 |
| `POST .../{mid}/approve` | Reviewer：migrationHash，精确审定所见提案 |
| `POST /api/regressions/cases/{newCaseId}/replays` | 消费者：批准迁移后发起真实新回放 |
| `POST .../{mid}/resolve` | Reviewer：replayId、note；检查新 caseHash/scope、通过事实和复发时点 |

审批不修改原 case、政策或执行权。新参考替代后，反馈为 RESOLVED_BY_REPLACEMENT，原失败仍在，旧固定版本没有被认证修复。出现新复发会重新 OPEN，迁移显示 REOPENED；响应的 feedbackStatus/resolutionCurrent 区分历史生命周期与当前有效性。旧的同范围 `/closures` 仍不能借新 scope 通过。

扩大政策、换 Function、改问题/日期/字段/用途/预算、改单位/粒度/身份签名或原参考答案会拒绝，不因拥有 Reviewer Token 自动成为同一义务。停用错误旧版本及迁移所有消费者是独立治理工作，不由单次任务替代验收自动完成。

## BI 的稳定来源投影

`AWE_BI_QUERY_BINDINGS` 的每个已注册绑定可选 `sourceProjectionRef`（管理员维护的稳定投影标识，最长 500 字符）。请求会携带它；网关响应 `readiness.sourceProjectionRef` 必须准确证明同一标识。未注册时不得自行回传以扩大证明范围，错值/缺失拒绝。

该值经验证后进入 executionEvidence，数值参考 scope 可在同一注册投影内比较不同读取快照；模型、政策、计划、实现版本/Hash 与网关配置仍参与范围。未提供时保持旧 opaque snapshot 的保守行为，不猜句柄中的稳定部分。它不是快照恢复能力，同快照独立数值对账仍需另外满足 checker 协议。

可执行验收见 [RepairRegressionIntegrationTest](src/test/java/ai/awe/registry/RepairRegressionIntegrationTest.java)，参考分类与兼容反例见 [RegressionExpectationsTest](src/test/java/ai/awe/registry/knowledge/RegressionExpectationsTest.java)。
