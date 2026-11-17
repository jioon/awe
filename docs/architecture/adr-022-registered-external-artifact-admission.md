# ADR-022：存量外部制品的具名有限准入

- 状态：已接受并实现有界工程范围
- 承接：ADR-018/019/020；M2 存量 SQL 纳管

## 问题

既有数仓已有大量 SQL 与任务。要求先把所有代码改写成 AWE 编译制品，无法逐域纳管成熟资产；反过来，仅登记 SQL Hash 或接受平台自报 PASSED，也不能证明实现遵循正式模型。有限结果回归不等于任意程序普遍等价，候选还可能访问未声明来源或产生未授权副作用。

## 决策

保留七类模型与 v2/v3 发布语义，启用已保留的 `PipelineContract.mode=REGISTERED_EXTERNAL`。Pipeline 继续表达确切输入读能力、逻辑输出、类型化加工意图与必需 Contract。外部平台继续维护代码、运行候选与独立检查，并执行部署。

管理员以不可变 Pipeline 实体/版本登记实际 artifactId、版本、mediaType、UTF-8 原文 SHA-256 和 proofScopeRef。AWE 独立读取可信平台实际字节并核对完整身份；调用方不能提交 URL、代码、Hash 或 PASSED 来增加权威。服务端进一步将确切发布/依赖/正式计划、profile、输入/目标、完整影响及注册套件绑定为义务与执行上下文。配置或证据改变后，待准入上下文必须重新验证审批；新候选登记不等于活动实现已被部署替换。

正式 `AWE_PIPELINE_PLAN_V1` 的内容/Hash 与外部候选的原文 Hash 分开，避免用正式计划 Hash 伪装实际运行代码。MODEL_COMPILED 的严格规范字节要求保持不变。未登记 profile 如实返回 NOT_CONFIGURED，不以正式计划存在冒称候选已注册。

## 约束执行面

当前只支持 `READ_ONLY_QUERY_DECLARED_TARGETS` 中无参 PostgreSQL STATIC_SNAPSHOT 来源：平台在受隔离的只读身份下读取正式结果 Schema 声明的列和完整静态总体，然后按现有一次性授权、完整目标版本向量和单调写意图 CAS 写入声明目标。参数化时间范围、额外行约束或其他读取类型尚无本 profile 的隔离实现，明确拒绝。平台必须提供可信、与本次 profile/readFunctions/targetBindingRefs 精确匹配的 execution-scope 观测。不能保证此边界的平台不得申请此 profile。

这不增加 AWE 的 SQL 引擎、脚本执行器或调度器。AWE 验证来自现有平台的具名数据义务，不能用用户自报输入清单认证程序没有其他依赖。真实平台接入仍须落实完整列/行/时间权限、函数执行权限、网络/资源隔离与受控写入，排除 PUBLIC 授权、安全定义函数和视图等增加的实际访问范围。

## 证明边界与回归

收据明确标记 `REGISTERED_EXTERNAL_FINITE_REGRESSION`，绑定完整外部 profile、原文 Hash、正式计划 Hash、套件、具名范围、输入快照、真实 run/attempt、基线和所有原有义务。候选结果/粒度/Schema/必需 Contract 与受影响消费者分别检查，独立 checker 规则不放宽。有限回归只证明当前输入与注册范围，不认证普遍语义等价或整体任务成功。

真实 PostgreSQL/HTTP 测试执行已登记的多行 SQL 原文，而非从正式 IR 重新生成候选；独立 SQL 发现错误行值。隔离 LOGIN 角色的列级来源权限阻断未声明输入及同表隐藏列；元数据事务不将其权限传给候选。随后沿原审批、在线消费、真实 CAS 建表、来源观测与正式指标再消费完成纵切面。字节、profile/范围、执行观测、配置变化及缺配置的反例单独验收。

现有逻辑物化、实时活动版本复检、旧失败历史和发布 Hash 不变。真实企业血缘覆盖、版本停用及所有消费者切换仍需后续治理；本增量不能作为整个 M1/M2 完成的声明。

接口与接入要求见 [EXTERNAL-ARTIFACTS.md](../../awe-registry/EXTERNAL-ARTIFACTS.md)。
