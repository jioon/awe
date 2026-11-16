# M2：模型约束的生产准入与逻辑目标物化

AWE 校验加工意图、制品、影响和回归，并提供在线一次性部署授权。外部开发/部署平台负责真实候选计算、独立参考、CAS 发布、作业运行和回滚。仅离线检查收据 Hash 不能形成准入。架构见 [ADR-018](../docs/architecture/adr-018-executable-capabilities-and-production-admission.md)。

## 模型与边界

先发布 v3 `Dataset(LOGICAL_TARGET)` 及显式行 Schema，无需伪造尚不存在的物理表和成功观测。Pipeline 仍是 `ObjectType(PIPELINE)`，增加 `pipelineContract`：

```json
{
  "sources": [{"alias": "events", "readFunction": {"id": "function.activity.events_read", "modelVersion": "1.0.0"}}],
  "targets": [{
    "dataset": {"id": "dataset.activity.active_users", "modelVersion": "1.0.0"},
    "rowSchema": {"id": "dataset.activity.active_user_row", "modelVersion": "1.0.0"},
    "plan": {"kind": "PROJECT", "input": {"kind": "SCAN", "source": "events", "fromDayOffset": 0, "toDayOffset": 0},
      "columns": [{"name": "user_id", "expression": {"kind": "FIELD", "name": "events.user_id"}}]}
  }],
  "requiredContracts": [{"id": "contract.activity.active_users", "modelVersion": "1.0.0"}],
  "mode": "MODEL_COMPILED"
}
```

该片段仅说明 facet 结构；完整 Schema/粒度/Contract 与绑定必须共同通过 Registry 校验。运行样本见 [ProductionAdmissionIntegrationTest](src/test/java/ai/awe/registry/ProductionAdmissionIntegrationTest.java)。Pipeline 输出只能指向明确逻辑目标，类型和实际产出必须遵循行 Schema。REGISTERED_EXTERNAL 是保留声明边界，当前准入只认证 MODEL_COMPILED 制品，不认证任意脚本的语义等价。

`GET /api/production/plans/{pipelineReleaseId}?contentHash=<hash>` 返回 `AWE_PIPELINE_PLAN_V1` 规范制品及其 Hash，含确切输入、目标、Contract 和依赖发布。平台应实际保存/编译这些字节；不能把任意代码贴上相同 Hash。候选和独立参考的计算仍在平台执行。

## 管理员配置

所有地址、资产映射和可信实现由部署方配置，不从用户请求接收。Registry 使用 V8 追加表；历史批准、消费和观测不可变。

| 环境变量 | 格式与用途 |
|---|---|
| `AWE_PRODUCTION_ENVIRONMENT` | 本实例固定物理环境，如 prod；缺失不授予许可 |
| `AWE_PRODUCTION_PLATFORM_URL`、`AWE_PRODUCTION_PLATFORM_TOKEN` | 固定可信平台网关；HTTPS，回环测试可 HTTP |
| `AWE_PRODUCTION_POLICY_REVISION` | 平台政策/执行实现变更时递增，旧证明随配置变化失效 |
| `AWE_PRODUCTION_TARGET_BINDINGS` | 对象：targetBindingRef → `{sourceSystem,externalRef,timeFields}`，不预填 fingerprint |
| `AWE_PRODUCTION_REGRESSION_SUITES` | 数组：`{pipelineId,suiteId,version,contentHash,independentCheckers:[checkerRef]}` |
| `AWE_PRODUCTION_CALLERS` | 数组：`{principal,token,pipelineIds:[id],environments:[environment]}` |
| `AWE_PRODUCTION_PROOF_MAX_AGE_SECONDS` | 60—3600，默认 900；真实回归证明的新鲜度 |

生产平台 Token 与作者、审核者、消费者 Token 必须分开。平台授权精确限定 Pipeline 和环境；请求自报角色/环境不增加权限。来源系统仍须配置对应 metadata/warehouse 映射；没有真实连接时返回 NOT_CONFIGURED。

目标映射不等于物化成功。`externalRef` 必须是来源适配器的管理员资产映射，timeFields 指明真实时间列；STATIC_SNAPSHOT 使用 `{}`。系统独立观测真实部署表的 fingerprint/列/主键/时间后才记录实现。

## 在线准入协议

| 步骤 | 接口与调用方 | 关键要求 |
|---|---|---|
| 1 准备 | 作者 `POST /api/production/admissions` | pipelineReleaseId、pipelineContentHash、artifactId、environment、idempotencyKey |
| 2 回归 | 作者 `POST /api/production/admissions/{id}/verify`，body `{}` | 服务端完整影响、义务和注册套件；可信网关观测真实候选/参考 |
| 3 批准 | 独立审核者 `POST .../{id}/approve` | checkId、expiresInSeconds（30—900）；当前最新完整 PASSED 才能批准 |
| 4 消费 | 平台 `POST .../{id}/consume` | receiptId、receiptHash、artifactHash、pipelineReleaseId、pipelineContentHash、environment、baselineRevision、idempotencyKey |
| 5 部署 | 外部平台自己的部署接口 | 仅消费返回的 authorization，按 expectedBaselineRevision 做原子 CAS，记录 consumptionId；AWE 不执行此步骤 |
| 6 观测 | 平台 `POST .../{id}/realizations` | consumptionId、deploymentRef、idempotencyKey；AWE 向可信网关获取实际部署事实并独立观测输出 |
| 7 使用 | 消费者按正式读能力查询 | 固定 Dataset/Schema Release 与实现证明，不改写原逻辑发布 Hash |

查看作者/审核范围：`GET /api/production/admissions[/{id}]`；查看数据集物化历史：`GET /api/production/datasets/{releaseId}/realizations`。工作台入口 `/production.html` 分开作者、审核、平台凭据，不把 AWE 取代为部署系统。

同一提案最多消费一次；同主体/同幂等载荷重试返回原记录，不同载荷冲突。批准、消费前重新观测当前制品、基线、来源、政策、完整影响和回归证明；新增下游依赖、共享资产变化、缺项、陈旧证明、错制品、过期批准均不得放行。影响超出预算时失败，不能截断后当成覆盖完整。

## 可信平台网关

AWE 使用固定网关的两个端点，不接受调用方提供的“测试通过”对象：

- `POST /admissions/observe`：请求含 protocolVersion、requestId、phase、pipeline、artifactId、expectedArtifactHash、environment、impactHash、suite、obligations。phase 为 PREPARE/VERIFY/APPROVE/CONSUME。响应须精确绑定请求/模型/环境，提供 bindingRevision、真实 artifact（artifactId/version/contentHash/mediaType/content）、baseline、inputs、suite、regression。mediaType 为 `application/vnd.awe.pipeline-plan+json`，content 必须与规范制品等同。
- `POST /admissions/realizations`：请求含 protocolVersion、requestId、consumptionId、deploymentRef、authorization。响应包含同一收据/制品/模型/环境、previousRevision/revision/activeRevision、runId/attemptId/completedAt、DEPLOYED 状态与精确输出集合。部署必须产生新的活动基线，输出覆盖每个确切 Dataset/Hash/targetBindingRef。

回归证明绑定 contextHash、impactHash、suite、真实运行/输入快照和时间；每个服务端派生 obligation 必须有注册独立 checker 的测量/比较记录。只声明 PASSED、删除失败项、借用其他模型或不同输入窗口的证明均无效。输入和输出 Schema、必需 Contract、候选与独立参考、全部受影响发布和共享资产分别形成义务；准入按匹配范围检查覆盖。

字段级权威协议见 [ProductionAdmissionService](src/main/java/ai/awe/registry/production/ProductionAdmissionService.java)、[ProductionObligations](src/main/java/ai/awe/registry/production/ProductionObligations.java)、[ProductionRealizations](src/main/java/ai/awe/registry/production/ProductionRealizations.java)。[SQL 参考平台](src/test/java/ai/awe/registry/SqlBackedProductionPlatform.java) 实际执行规范计划、独立 SQL 比较、CAS 建表部署；测试中的成功来自真实计算，而非预置响应。企业适配可沿用协议接入 Hive/Spark/调度/质量平台。

## 版本、恢复与实际约束

逻辑发布、实现、部署和数据快照是不同版本。查询必须持有完整实现证明，包含 Dataset/Schema 发布 Hash、realizationId、consumptionId、artifactHash、部署/run/attempt、真实来源快照及 binding。获得同物理目标的新写许可时，旧绑定先失效；同 Schema 的语义替换不能继续服务旧版本。保守影响包含历史版本及运行时共享物理实现。

AWE 与外部部署平台之间没有跨系统事务。平台必须在真正发布入口强制在线消费和 CAS，保持同一 consumption 的幂等提交，并提供可信活动版本观测。若消费后部署失败/取消，保持旧证明封禁，由平台恢复受审版本并重新完成准入/观测；不得删除消费记录或手改证据恢复绿灯。生产只读运行身份、DDL/写权限及旁路入口同样需在平台落实。

物化观测验证部署绑定与元数据，不自动证明全部历史行正确或所有业务效果达成。参考测试、企业连通、真实业务验收、强制切换及容量/容灾验证分别记录。复杂加工、历史恢复与 F6 自主操作仍需独立能力，不能凭本收据泛化授权。
