# 结构化指标需求与服务端规划

[ADR-021](../docs/architecture/adr-021-semantic-requirements-planning.md) 对应的 QUERY_METRIC v3 以正式 Metric 和业务要求为入口，由 AWE 选择受权 Function 并生成受限计划。旧 EXTRACT v1、固定 Function 的 QUERY_METRIC v2 保持兼容。协议不接收 SQL、IR、客户端权限或自报验证结论。

工作台为 `/semantic-requirements.html`；下面所有接口使用消费者身份，与既有数据操作执行同一政策和来源检查。

## 显式表达一次需求

下面是格式示例，UUID、Hash、主体标识和字段名需要替换为实际发布的模型。示例不定义任何企业业务口径。

```json
{
  "protocolVersion": 3,
  "intent": "QUERY_METRIC",
  "metric": {
    "releaseId": "<metric-release-uuid>",
    "contentHash": "<metric-content-sha256>"
  },
  "requirements": {
    "statisticalSubject": {
      "entityId": "domain.device",
      "modelVersion": "1.0.0"
    },
    "unit": "device",
    "businessZone": "Asia/Shanghai",
    "timeRole": "EVENT_TIME",
    "measures": ["devices"],
    "dimensions": ["city"],
    "filters": [
      {"field": "app", "operation": "EQ", "type": "STRING", "value": "example-app"}
    ],
    "from": "2026-08-18T00:00:00+08:00",
    "to": "2026-08-19T00:00:00+08:00",
    "view": "OBSERVED_SNAPSHOT",
    "verification": "OBSERVE"
  },
  "consumer": {"purpose": "analysis"},
  "constraints": {"maxRows": 100, "maxBytes": 65536},
  "idempotencyKey": "domain-analysis-20260818"
}
```

`statisticalSubject.entityId/modelVersion`、unit、businessZone 和 timeRole 必须与该 Metric 正式契约一致。设备、账号和自然人不能互换；业务时区、事件时间与处理时间不能猜测。from/to 是显式偏移时间戳的半开区间；不推断“昨天”“最新账期”或历史身份规则。

measures 选择正式度量，dimensions 选择正式 grain 的子集；度量至少一个，维度可为空，各最多 32 个。filters 最多 32 项，仅约束已声明的维度，即使该维度没有出现在本次输出中。operation 为 EQ/NE/LT/LE/GT/GE；type 必须与维度类型一致，value 用字符串表达对应的精确类型值，例如 INTEGER 的 `"9007199254740993"`。BOOLEAN 只支持 EQ/NE。未知字段、类型、重复项和越界格式拒绝，不静默忽略。

view 为 OBSERVED_SNAPSHOT 或 PERIOD_COMPLETE；verification 为 OBSERVE 或 REQUIRE_INDEPENDENT。两者分别表达数据就绪和独立验证要求，不以一个成功替代另一个。结果列顺序不由 measures/dimensions 的列表顺序强制约定，正式名称和统计语义决定契约。

可选 externalTaskRef `{issuer,id}` 只作外部任务关联，不授予权限。存在多个实现时，可增加同级字段：

```json
{
  "candidateFunction": {
    "releaseId": "<authorized-function-release-uuid>",
    "contentHash": "<function-content-sha256>"
  }
}
```

这是候选选择，不是客户端认证。系统仍独立读取发布、核对其绑定的 Metric 和当前政策。

## 先评估，再按当前事实执行

| 接口或工具 | 行为 |
|---|---|
| `POST /api/requirements/assess` | 接收完整 v3 请求，生成并保存静态能力裁决与计划 |
| `GET /api/requirements/assessments/{id}` | 当前主体、当前政策下读取自己的不可变评估；重新检查候选可读范围 |
| `POST /api/data-tasks` | 接收同一 v3 请求，重新规划、授权并通过实际来源/执行/验证 Gate |
| `GET /api/data-tasks/{id}` | 当前授权下读取原语义请求、固定计划、实际结果和证据 |
| MCP `awe_plan_requirements` | 与静态评估共用服务 |
| MCP `awe_query_metric` | 支持原 v2 或新 v3，实际执行共用服务 |
| MCP `awe_validate_plan` | v3 使用同一静态评估；v2 保留固定正式计划检查 |

REST 请求正文上限 64 KiB。静态评估每次调用产生一个新观测；idempotencyKey 用于实际数据操作，同主体同键不同规范请求冲突。网络失败重试实际操作时保留原请求和键；新的独立执行使用新键。

评估返回 id、status、resolution、decision、catalogComplete、candidates、diagnostics，PLANNED 时还有 resolved 清单。decision 分开记录 resolution、authorization、supply、readiness；静态评估的 readiness 为 UNKNOWN，不读取数据、不证明账期到齐，也不授予后续执行权。

| status | 含义 |
|---|---|
| PLANNED | 当前可见范围恰有一个合法实现；resolved 包含服务端派生契约和 planHash |
| AMBIGUOUS | 多个合法实现；明确选择 candidateFunction 后重新评估，不自动挑最新版本 |
| UNSUPPORTED | 当前可见实现无法满足要求，或候选预算不足；查看具名 diagnostics |
| NOT_DISCLOSED | 未找到当前可披露的绑定；不声称企业没有该指标或能力 |

自动检索最多考察 256 个可调用 Function 发布。超限返回 CANDIDATE_BUDGET_EXCEEDED、catalogComplete=false、供给 UNKNOWN，不以截断后的列表认定唯一；可用精确 candidateFunction 单独检查。非法请求/Hash 拒绝、认证/政策拒绝仍使用实际 HTTP 错误，不包装成可执行成功。

评估不是执行预约。评估后新增候选可能使实际请求变为歧义；需要固定实现时显式选择受权 candidateFunction。实际运行仍复检当前身份、政策、来源、物化、完整扫描窗口、预算和所需验证，缺配置返回 NOT_CONFIGURED，分区未完成不能返回 0。新候选不会改变已经完成的操作；历史读取按服务端保存的实现检查当前授权。

## 当前可推导范围

原计划须是 Aggregate，或只重命名其分组键/度量的 Project(Aggregate)。正式 grain 必须完整且无歧义地映射到所有原分组键。新条件放在聚合前，原业务筛选、Join、集合和扫描窗口保留；原度量定义不替换。

例如原模型按城市、应用统计设备 COUNT_DISTINCT，设备可能在两个应用中重复。只要求城市时，AWE 从原输入重新去重；应用级计数为 2、1，不意味着城市级一定为 3。派生计划和结果必须以实际设备集合证明，不能把“不允许直接累加”当成“已有明细也不能推导”。

如果要求与原契约完全相同，原复杂计划和 BI DIRECT 仍可使用。需要重组的复合指标、计算输出或不明确分组映射返回具名限制。当前 BI DIRECT 不声明通用派生执行能力；改变完整契约返回 EXTERNAL_DERIVATION_NOT_REGISTERED，不把原 BI 数据解释成新粒度。

派生输出仍受主体、单位、时间、类型、空值和强制行策略约束。筛选字段、未直接输出的实际输入字段及扩展时间窗口均须获权；调用方提交的 purpose、预算或候选不能扩大服务器政策。

## 证据、反馈和边界

静态评估复用不可变 `awe_resolution_observation`。计划清单包含固定 Function/Metric/主体/来源、派生 contract、policyHash、rowScope、semanticRequirements，以及 derivation 的 algorithm、baseContractHash、requirementsHash；最终 planHash 绑定这些事实。实际操作在原 `awe_metric_task` 保存 v3 规范请求和该服务端清单，不改写旧发布与旧协议证据。

静态评估可通过既有反馈接口提交 observationId，使用 RESOLUTION 受审参考和真实重新评估回放；实际数据结果继续使用 DATA_RESULT，拒绝结局使用 DATA_OUTCOME。参考及模型替代的边界见 [修复回归指南](REPAIR.md)。计划成立、来源就绪、独立数值验证、参考回归和外部任务验收分别记录。

v3 数据反馈可对同一问题采用受审 Metric 新版本替代。原/新 Function 必须为同实体更高版本，Metric 也必须为同实体更高版本；服务端先核对请求与不可变发布的 Release/Hash，再在业务要求比较中忽略两个版本指针。时间、筛选、维度、统计主体、单位、政策及原参考答案不能改变。独立批准后仍须真实新回放，旧失败继续保留；跨指标、降级、混用 v2/v3 或只提高 Function 版本掩盖旧 Metric 均拒绝。这不等于停用旧版本或迁移全部消费者。

本次只实现受限指标需求规划。任意自然语言理解、新人群表达式、自由维度扩展、历史身份规则推断、SCD/AS_OF、单位换算、版本停用与全体消费者迁移仍需按域继续建设，不能用 Prompt/Skill 或未经认证 SQL 补成成功。现有平台负责物理执行，AWE 不管理 Agent 完整任务控制流。
