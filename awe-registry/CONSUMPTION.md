# M1.1：已发布 Dataset 的受控真实取数

本增量提供真实 PostgreSQL 取数，不是指标问数或完整 M1。整体路线见 [实施路线](../docs/implementation-plan.md)，职责和证明边界见 [ADR-017](../docs/architecture/adr-017-controlled-dataset-consumption.md)。

## 连接与消费策略

沿用 Registry 的数据库、Author/Reviewer 及启动方式。另为真实数据源配置：

| 环境变量 | 用途 |
|---|---|
| `AWE_WAREHOUSE_JDBC_URL` | PostgreSQL 真实数据源；远程要求 `sslmode=verify-full`，仅本地回环支持普通 TCP |
| `AWE_WAREHOUSE_JDBC_USER` / `AWE_WAREHOUSE_JDBC_PASSWORD` | 运维单独授予的只读源端身份；应用只读事务不代替数据库授权 |
| `AWE_WAREHOUSE_ASSET_BINDINGS` | 来源资产到允许关系的映射，如 `{"postgres:activity.events":"analytics.activity_events"}`；不由调用方指定表或 URL |
| `AWE_WAREHOUSE_MAX_ROWS` | 默认 10000、上限 100000；运行时与消费请求/策略取共同约束 |
| `AWE_CONSUMER_POLICIES` | 独立消费凭据与 Function、列、用途及预算的服务端允许清单；无配置/无效配置时消费入口返回 503，建模入口仍可使用 |
| `AWE_CONSUMPTION_RESULT_TTL_SECONDS` | 返回行载荷访问有效期，默认 900 秒，允许 60—3600 秒；永久证明与可清理行载荷分开 |

策略形状如下，凭据占位符须通过部署环境替换，不能原样作为配置使用，也不要将真实凭据提交到仓库或聊天：

```json
[
  {
    "principal": "activity-consumer",
    "token": "<独立消费凭据>",
    "functionIds": ["function.activity.extract_events"],
    "purposes": ["analysis"],
    "fields": ["event_id", "device_id", "event_time"],
    "maxRows": 1000,
    "maxBytes": 262144,
    "maxWindowSeconds": 604800
  }
]
```

Author/Reviewer Token 不自动拥有消费权，也不能复用为消费 Token。`consumer.purpose` 只是待检查的用途声明。首版服务主体与静态允许清单不代表企业 OIDC/SSO、个人委托或行级策略已完成；只支持无继承子表的普通 PostgreSQL 表，当前不支持分区根、敏感字段、RLS、视图或外部表。

## 从真实资产到发布能力

1. 使用 Author 调用 `POST /api/sources/postgres/imports`，正文为 `{"externalRef":"activity.events"}`。原生适配器读取映射关系的实际 catalog，返回本次 `fingerprint`、来源版本和快照；无连接/映射明确返回 `NOT_CONFIGURED`。Hive/BI 的配置与状态独立，不能以 PostgreSQL 验收冒称企业接入完成。
2. 复用已有建模流程发布业务主体 ObjectType，以及 Dataset。Dataset 必须声明实际主键对应的粒度、EVENT_TIME、业务时区和唯一 `postgres` 来源绑定；绑定使用实际导入的指纹及 `timeFields.EVENT_TIME`。不填写任意伪造指纹。
3. 另发布 `ObjectType`、`family=DATASET` 的输出行 Schema。其字段显式对应本次同名物理列，身份键与 Dataset 粒度一致。它与业务主体分开，不从主体类型推断全部表字段。支持 STRING、BOOLEAN、INTEGER、DECIMAL、DATE、TIMESTAMP；敏感字段不能读取，同名主体字段的敏感性不能被结果 Schema 降级。
4. 发布下面的 Function。所有引用须已发布且版本准确，正常经过不同作者/审核人提交、批准、发布。发布时会固定 Dataset 和行 Schema 的 Release/Hash 清单；运行时再次独立检查。

Function 的 `model` 示例（外层仍是严格 v2 的 `name/definition/ownerTeam/schemaVersion/model` 文档）：

```json
{
  "ref": {"id": "function.activity.extract_events", "modelVersion": "1.0.0"},
  "ownerType": {"id": "dataset.activity.events", "modelVersion": "1.0.0"},
  "operation": "dataset.extract.v1",
  "parameters": [
    {"name": "from", "type": "TIMESTAMP", "required": true},
    {"name": "to", "type": "TIMESTAMP", "required": true}
  ],
  "resultSchemaRef": "dataset.activity.event_row@1.0.0",
  "executorBindingRef": "postgres.dataset.extract.v1",
  "requiredAssertions": []
}
```

`resultSchemaRef` 指向上述输出行 ObjectType，不是随意字符串或 Dataset 的隐含所有字段。该执行签名明确定义同名列映射，无 Join、表达式、过滤 DSL 或用户脚本。STRING 只支持 TEXT/VARCHAR，CHAR 明确拒绝；业务事件时间物理列须为 TIMESTAMPTZ，范围边界须为微秒精度。无法表达的能力保持不支持。

## 消费接口与工作台

打开 `/consumption.html`，输入独立消费 Token。Token 仅保留在页面内存中。页面从真实接口加载授权能力、字段及预算，提交显式时间范围并展示数据、版本、状态及证据；不预置伪造成功结果。

| 接口 | 范围 |
|---|---|
| `GET /api/capabilities` | 当前策略允许的已注册取数能力及支持的列/用途/预算；发现本身不证明连通或账期就绪 |
| `POST /api/data-tasks` | 创建一次 DataTask v1 数据操作；只支持明确的 EXTRACT 请求 |
| `GET /api/data-tasks` | 当前主体仍有权查看的操作摘要 |
| `GET /api/data-tasks/{id}` | 同主体、当前仍获授权的操作证据及尚未过期的结果 |

使用能力发现得到的真实 Function ReleaseID 与 Hash：

```json
{
  "protocolVersion": 1,
  "intent": "EXTRACT",
  "externalTaskRef": {"issuer": "development-agent", "id": "analysis-step-42"},
  "proposedFunction": {"releaseId": "<Function UUID>", "contentHash": "<真实 SHA-256>"},
  "requirements": {
    "fields": ["event_id", "device_id", "event_time"],
    "from": "2026-11-12T00:00:00+08:00",
    "to": "2026-11-13T00:00:00+08:00",
    "view": "OBSERVED_SNAPSHOT"
  },
  "consumer": {"purpose": "analysis"},
  "constraints": {"maxRows": 100, "maxBytes": 65536},
  "idempotencyKey": "analysis-42-read-1"
}
```

时间范围为显式 `[from,to)`，不猜“昨天”或最新账期。`maxBytes` 限制规范 JSON 行数组的 UTF-8 字节，不包含外层证据元数据；它与行数、服务端源配置和策略共同收紧执行。相同主体、键和载荷返回原操作，改变载荷返回 409；想读取新快照必须创建新键。

## 如何解读结果

- `EXTRACTED`：返回指定事务可见范围内满足条件的完整受限投影。`result.state=RECORDS` 或 `NO_RECORD`；空集不表示零指标、分区完成或埋点无漏报。
- `NOT_CONFIGURED`：真实连接、资产映射等缺失，没有成功数据结果。`BLOCKED/UNAVAILABLE/INCONCLUSIVE` 分别说明 Gate/执行或覆盖未满足，不能按零值使用；超行/字节预算不返回部分行。
- `decision.readiness=UNKNOWN`：只提供 `OBSERVED_SNAPSHOT`，不证明账期到齐。请求其他视图或未知要求会拒绝，不隐式降级。
- `verification.structural=PASSED` 仅表示本次支持的 Schema、类型与范围检查；`independentReconciliation=NOT_RUN`，不冒称独立业务对账或整体任务成功。
- `resolved` 固定模型/Hash、输入范围、执行器与计划 Hash；`gate_report` 引用来源观测；`data_snapshot_ref` 是实际读取的事务/目标摘要，不能恢复历史数据。`resultHash` 是规范行数组 SHA-256。
- INTEGER/DECIMAL 通过精确 JSON 数值字面量返回；客户端必须采用无损数值解析，不能先转 IEEE-754 Number 再宣称值未变化。工作台保留不安全整数与小数的数字原文显示；同样的要求适用于下游 Agent/SDK。
- 行载荷到期或被清理后返回 `EXPIRED`，保留原记录状态、行数、Hash 和不可变证据；幂等重试不会悄悄重新查询。读取会删除该操作的过期载荷，部署方还需维护清理无人再访问的过期记录，例如定期执行 `DELETE FROM awe_data_task_result WHERE expires_at <= clock_timestamp()`。这不是内置通用调度能力。

真实 PostgreSQL/HTTP 测试验证该执行范围；域内数据语义、源端授权、数据到齐、BI/指标、容量及正式业务准入仍须分别验收。
