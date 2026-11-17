# 存量 SQL 的有限生产准入

`PipelineContract.mode=REGISTERED_EXTERNAL` 将既有工程平台维护的制品纳入正式加工意图、独立回归和在线准入。它复用 [生产准入协议](PRODUCTION.md) 的完整影响、必需义务、独立审核、一次消费、共享目标 CAS 与真实物化。设计见 [ADR-022](../docs/architecture/adr-022-registered-external-artifact-admission.md)。

AWE 不执行 SQL、不接管代码工作区，也不把有限样本通过认证为任意代码普遍等价。当前外部执行 profile 固定为 `READ_ONLY_QUERY_DECLARED_TARGETS`，只接受无参 PostgreSQL `STATIC_SNAPSHOT` 读能力：候选在来源端隔离的只读身份下读取正式结果 Schema 声明的列和完整静态总体，平台仅通过原有授权与 CAS 写声明目标。参数化时间范围、额外行约束或其他执行能力尚无此 profile 的受支持隔离实现，会明确拒绝；不能只核对表名就声称落实了读能力。

## 管理员登记

`AWE_PRODUCTION_EXTERNAL_ARTIFACTS` 为数组，每个不可变 Pipeline 实体/版本登记一个确切制品：

```json
[
  {
    "pipelineId": "pipeline.activity.daily_users",
    "modelVersion": "1.0.0",
    "artifactId": "warehouse.activity.daily_users.sql",
    "artifactVersion": "release-17",
    "mediaType": "application/sql",
    "contentHash": "<真实UTF-8原文字节的SHA-256>",
    "proofScopeRef": "scope.activity.daily_users.partition-regression.v1"
  }
]
```

所有字段必填，未知字段、重复实体/版本及错误格式使整组配置失效。原文字节可以有换行、CR 和 TAB，Hash 不修剪空白、不改写编码、不规范化 SQL。制品内容从固定可信平台实际读取，不从提案请求接收；不接受调用方自报通过、正文、URL 或 Hash 替代观测。有限范围引用由管理员与领域责任方维护，须对应注册套件实际检查的数据范围/规则，不能以一个不透明名称代替真实检查。

登记绑定唯一且不可变的 `pipelineId@modelVersion`；服务端进一步固定该发布的确切 Release ID、contentHash、依赖及正式计划。配置、原文 Hash、套件或范围改变后，旧准入上下文及待消费批准失效，须重新准备、验证和审批。已部署的确切实现继续按原物化证据和实时活动版本独立复核，不能把新候选登记当成已部署替换。配置缺失时 prepare 返回 `NOT_CONFIGURED`，不产生外部候选 Hash 或部署许可；正式加工意图仍可读取。

## 正式意图与真实候选是两个 Hash

`GET /api/production/plans/{releaseId}?contentHash=...` 保持既有 `content` 与 `contentHash` 一致：它们是 `AWE_PIPELINE_PLAN_V1` 正式意图，而非既有 SQL 原文。额外 `externalCandidate` 提供实际登记的 artifactId/version/mediaType/contentHash、proofScopeRef 和 executionMode。未登记时为 `{status:NOT_CONFIGURED}`。

提案的 `expected_artifact_hash` 和最终收据/authorization 的 `artifactHash` 是登记的**原文** Hash。`artifact_doc` 分开保存 referenceArtifact/referenceArtifactHash/externalCandidate。工作台分别展示两种 Hash，平台消费请求始终从真实批准收据取得候选 Hash。

## 平台观测与有限证明

生产网关协议 v2 继续有效。REGISTERED_EXTERNAL 的 `/admissions/observe` 请求额外携带：

- `externalArtifact`：登记的完整 profile。
- `externalArtifactProfileHash`：该 profile 的规范 Hash。
- `referenceArtifactHash`：正式加工意图的规范 Hash。
- `externalExecution`：mode=`READ_ONLY_QUERY_DECLARED_TARGETS`、profileHash、按 alias 排序的 readFunctions，以及排序的 targetBindingRefs。

平台须从自己实际保存的字节和真正强制执行的隔离边界生成证据，而不是仅复制请求。每个阶段响应须提供完全匹配的 `externalExecution`；原有 `artifact` 返回准确 ID、版本、mediaType、原文和 SHA-256。任一不匹配不能进入 PREPARED 或继续批准/消费。

实际回归的 `regression` 额外提供 externalArtifactProfileHash、referenceArtifactHash、proofScopeRef，必须与本次固定 profile 一致。所有原有义务仍需完整覆盖：真实输入快照、输出 Schema/粒度、必需 Contract、候选和独立参考，以及传递/共享物理资产消费者。有限 profile 同时参与义务 scope、执行 contextHash、配置 Hash、收据和 authorization。删除义务或借用另一个 profile 的成功均不成立。

收据 `proofScope=REGISTERED_EXTERNAL_FINITE_REGRESSION` 只证明注册套件对本次输入/运行和具名范围的检查通过。它不证明任意历史/未来数据、未观测业务语义、任意 SQL 等价、线上任务总体完成或企业血缘全覆盖。物化与活动版本复检仍按原协议执行。

## 实际执行与接入边界

[ExternalArtifactAdmissionIntegrationTest](src/test/java/ai/awe/registry/ExternalArtifactAdmissionIntegrationTest.java) 使用真实 PostgreSQL/HTTP：发布逻辑目标和正式 Pipeline、登记多行 SQL 字节、执行独立 SQL 对账、审批/消费/CAS 部署、来源观测后按正式指标消费。错误 SQL 行值、字节篡改、缺 profile、错误有限范围/执行证据、配置变化、未声明来源以及同表未声明列分别验证拒绝。

参考平台为外部候选创建独立 PostgreSQL LOGIN 角色，仅授予正式 Function 结果 Schema 的列级 SELECT；元数据锁定由另一个可信只读事务完成，候选不继承其数据权限。使用候选角色的新只读事务执行原文，而非从 IR 替换生成候选，并施加查询时间与结果行预算。独立参考使用单独 SQL；实际数据和目标所有者按现有共享目标事务 CAS 提交。该测试范围不替代企业 Hive/Spark 的只读隔离、可信输入足迹、预算、受控写入口及强制上线接入；接入方必须落实完整的列、行、时间及函数权限边界，不能用声明表名取代能力约束；PUBLIC 授权、安全定义函数、视图或平台旁路可能增加实际可见范围，必须由执行隔离策略排除。

普通 `MODEL_COMPILED` 保持原有规范字节严格比对，不因新增外部 profile 降级。已有模型 Release/Hash、发布清单及历史证据不改写。版本停用、所有消费者切换和外部系统生产依赖覆盖仍是独立工程能力。
