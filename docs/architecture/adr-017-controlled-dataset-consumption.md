# ADR-017：版本化数据能力与 PostgreSQL 受控取数

状态：M1.1 工程增量；实际验收结果以集成测试、CI 和运行记录为准。不是完整 M1、F2/F3 或企业接入完成声明。

## 决策与复用

首个消费纵切面选择真实 Dataset 取数，复用 Registry、七类模型、版本/Hash、来源 Gate 和 Web 入口。现有三类 JDBC 质量检查不变，不被包装成指标查询；Metric 的完整计算定义和 BI DIRECT 消费在后续增量实现。实施顺序见 [实施路线](../implementation-plan.md)。

`DataTask` 位于能力协议层，不实现 `AweDefinition`，不增加第八类元模型。本轮只接受 `EXTRACT`：候选 Function 的精确 Release/Hash、明确字段、半开事件时间范围、用途、行/字节预算、幂等键及可选外部关联。未知字段、版本、数据视图或约束失败关闭，不静默降级。

## 正式声明与执行签名

只支持 `operation=dataset.extract.v1` 且 `executorBindingRef=postgres.dataset.extract.v1` 的已发布 Function。两者任一为保留标识时必须一致；其他 Function/Action 保持声明状态，不自动获得执行权。

- `ownerType` 指向精确版本 Dataset。
- `resultSchemaRef` 使用 `id@major.minor.patch` 指向已发布 `ObjectType(DATASET)`，其字段是明确的输出行 Schema，身份键与 Dataset 粒度一致。该引用进入发布依赖清单，不因旧字段是字符串而失去版本约束。
- 该执行签名明确定义同名物理列映射，仅支持 STRING、BOOLEAN、INTEGER、DECIMAL、DATE、TIMESTAMP；无表达式、Join 或别名推断。业务主体与输出行 Schema 分开。
- Function 参数固定为必需 TIMESTAMP `from`、`to`。Dataset 有 EVENT_TIME 及唯一 `postgres` 来源绑定，事件时间物理列为 TIMESTAMPTZ。
- 需要额外 `requiredAssertions` 的 Function 在首版不支持，不能把发布 Evidence 中的 `PASSED` 当成本次运行许可。

旧发布、Hash 和 v2 文档保持只读兼容。普通 Function 的不透明结果/执行引用不被自动解释为可执行能力；缺少新执行签名所需依赖清单的历史声明须重新审核发布。语义、映射、Schema、签名或绑定变化继续执行版本兼容检查。

## 来源、权限与执行

原生 PostgreSQL 元数据适配器读取服务端资产映射对应关系的实际 catalog：列、类型、可空性、主键与时间列，并生成来源版本和规范指纹。该范围内要求受支持的表与主键；不从缺少主键推断任意业务粒度，不从数据库可读推断账期完成。缺少连接或映射返回 `NOT_CONFIGURED`；源端不可用和不支持状态分别保留。

独立消费配置由部署端映射 Token 到服务主体与 Function/列/用途/预算允许范围。请求中的 `consumer` 只声明用途，不能提交主体、角色、委托或已解析引用。作者/审核 Token 不继承数据权。能力发现受策略过滤；读取操作证据要求同一主体及当前仍有效的允许范围。

源端另行配置只读身份和关系权限。首版只支持无继承子表的普通 PostgreSQL 表，不支持分区根、行级安全策略、视图/外部表、非确定性排序规则、敏感字段、任意 SQL 或调用方指定物理关系。普通继承父表的主键不能约束子表，分区根的并发挂载也不能借普通读锁冒称全部范围已经检查；两者明确拒绝。使用共享服务 Token 的参考配置不代表企业用户委托、行列策略和生产鉴权已完成。

每次新请求先复检当前来源，再在只读、repeatable-read 事务中确认实际 Schema 并读取明确投影；时间条件使用参数绑定，范围为 `[from,to)`，只接受 PostgreSQL 可无损表达的微秒时间边界。行、字节、单元传输、执行时间与并发均有边界；SQL 在向 JVM 传输前阻断超大单元。STRING 首版只支持 TEXT/VARCHAR，不支持可能因去填充转换绕过字节判断的 CHAR。超限或无法完成证明时不返回部分结果冒充完整。零行可以构成完整的当前范围抽取，状态为 `NO_RECORD`；不沿用质量检查中“空关系不能证明数据质量”的判定来伪造查询错误或零指标。

## 状态与证据范围

分别记录显式引用解析、DIRECT 供给、授权、来源 Gate、执行终态、结构检查、结果及限制。`readiness=UNKNOWN` 与 `view=OBSERVED_SNAPSHOT` 明确表明未证明业务账期完整；不提供 `PERIOD_COMPLETE` 或隐式旧账期兜底。

操作固定 Function、Dataset、行 Schema、主体的精确版本/Hash、归一请求与计划 Hash、服务端策略 Hash、来源观测、真实事务快照引用及结果 Hash。连接串、凭据和原始异常不进入消费者报告。事务快照引用绑定实际目标/配置身份/关系，不能用于恢复历史行。独立数值对账明确为 `NOT_RUN`，业务验收属于外部责任方，不产生全局 TaskSuccess。

同主体、同幂等键、同载荷返回原操作，不重新读取已变化的数据；更换载荷冲突。终态证据和接受的模型/请求不可改写。进程中断后仍为 RUNNING 的操作不冒称成功，首版没有跨进程自动恢复工作者。

原始行载荷与不可变证据分表保存：`resultHash` 是规范行数组的 SHA-256，证明记录只保存摘要和元数据。载荷默认 900 秒后禁止读取，可配置为 60—3600 秒；过期或主动清理后返回 `EXPIRED` 和原记录状态，幂等重试不重新取数。按 ID 读取时清理对应过期载荷；无人再访问的过期载荷仍需部署方数据库维护清理，不宣称已有后台物理清理调度。结果保留、导出和独立审计保留策略在生产准入时确定。

## 验收与后续门槛

使用真实 PostgreSQL 与 HTTP：发布模型/Schema/Function，独立消费身份发现并读取窗内真实行，与独立 SQL 对照。反例覆盖错误 Hash/引用、未知版本/字段/视图、敏感及未授权投影、用途/时间/预算、跨主体证据读取、幂等冲突、来源漂移、缺少配置、空结果和超限；数据库强制终态证据不可改写。

这些测试证明原生 PostgreSQL 参考执行范围，不冒充企业 Hive/BI、数据到齐、自然语言解析、指标计算或实际部署准入已验收。M1 生产准入仍需真实身份/委托与源端授权、结果保留/导出策略、成本容量、领域覆盖和独立业务验收。四类外部场景继续遵守 [ADR-016](adr-016-data-task-and-responsibility-boundaries.md)，不扩张为通用 Agent 工作流。
