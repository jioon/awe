# AWE 七类核心抽象与类型层级

F0/F1a 建立基础契约与 PostgreSQL Registry；七类抽象随后进入 **Java 21 sealed metamodel**。F1b 将同一组内核记录贯穿严格文档解码、模型校验、不可变发布和 Web 工作台；能力执行与真实数据验证仍按各阶段建设，不因类型已发布而自动成立。发布边界及历史迁移见 [ADR-013](adr-013-typed-registry-and-source-gates.md)。

## 正式顶层分类

| 设计类型 | Java 类型 | 数仓含义 | 和何种数据系统对应 |
|---|---|---|---|
| Object Type | ObjectType | BusinessEntity/Event、Dataset、Pipeline、DataProduct、物理资产的对象型定义 | BI/Hive/调度资产类型 |
| Link Type | LinkType | 类型化关系，含语义关系与可 Join 性，Cardinality、时间策略 | 语义图、血缘、事实维度关系 |
| Semantic Model | SemanticModel (Dataset/Metric/Feature) | 真实业务计算语义；统一 Grain/Time 但保留指标与特征类型专属约束 | DWD/DWS 指标与画像特征 |
| Function | FunctionDefinition | 无副作用的只读查询、计算、检查、诊断；受控 Executor Binding | BI、SQL、质量、调度只读 API |
| Action | ActionDefinition | 有副作用的发布、重跑、回补；强制授权、幂等、补偿及后验检查 | 开发平台、调度、审批 |
| Contract | ContractDefinition | 面向作者/消费者的可验证义务，含执行阶段、阻断级别 | ODCS、指标契约、API/Policy |
| Evidence / Assertion | EvidenceAssertion | 明确的验证声明及证据，绑定 Checker、发布内容、数据快照与已知盲区 | 测试、质量系统、对账、运行记录 |

## 基础约束

- **七类是元模型，不等于七种数仓业务实体。** 例如 DAU 是 SemanticModel.Metric 的发布实例；用户实体的类型是 ObjectType；用户记录还在数据系统，不在知识图中。
- **统一的规范不是任意 YAML schema。** 编译/发布路径必须根据类型生成和验证同样的契约；反序列化层必须变成 Java 强类型值对象，并拒绝未知字段或不受支持语义。
- **ObjectType 定义实体类别，BusinessEntity 是对象家族分类；SemanticModel 又保留 Dataset/Metric/Feature 三个可扩展分支**，不会把算法 Feature 当作 Metric。
- **LinkType 和 RelationContract 不是重复权威。** LinkType 定义可发布关系；RelationContract 是编译时的 Join Safety 约束；关系边不是天然可 JOIN 的 SQL。
- **Action 与 Function 在类型层面断开。** 能力执行时还必须进行当前身份、源系统、审批、安全和资源预算检查；仅有声明不授予权限。
- **Evidence 绑定验证范围与数据快照。** 没有独立校验的数据即使已通过业务认证，也不能伪装成绝对真值。
- **Registry v2 文档直接映射七类 Java 类型。** `entityType` 选择具体记录类型，`spec.schemaVersion=2`，`spec.model` 包含该记录的完整字段和精确版本 `ref`；未知字段和不受支持的语义失败关闭。旧发布只读保留；旧草稿需作者明确补全，不能猜测身份或时间字段进行自动迁移。
- **已发布不等于可执行或数据已验证。** 发布依赖绑定精确版本与 Hash，来源验证记录自己的范围；Function/Action 仍需受控执行器，Contract 的未来阶段义务不能冒充已运行，Evidence 不能由作者任意声明 `PASSED` 来提升来源权威。

## 为什么避免一开始强制所有复杂业务进入七类

这七类定义 AWE 元架构的演进边界，不意味业务数仓团队需要学习七类对象并逐一填写。
数仓建模入口继续围绕业务对象、Fact/Dataset、Metric、Dimension、Feature、Pipeline、DataProduct；对象型定义使用 `ObjectType.family`，语义计算使用 `SemanticModel` 的具体分支。维度使用 `ObjectType` 的 `DIMENSION` 家族和 `dimension.<domain>.<name>` ID；语义模型的维度引用必须解析到正确的已发布家族。
工作台提供七类文档模板和依赖/来源/校验视图；模板只是填写结构，不会自动确认业务含义或创建通过验证的 Evidence。
另须明确不支持的语义类型应失败关闭，不能利用自定义 JSON 片段绕过规则。

## 本轮验收

Java CI 的正反契约测试覆盖对象身份、关联扇出、DAU 去重聚合、特征历史可用性、Function/Action 区分、Contract 阶段校验、Evidence 盲区与 sealed 家族闭合；PostgreSQL/HTTP 集成测试覆盖同一类型从建模到发布和来源漂移 Gate 的路径。

**后续关键门槛：真实 Hive/BI 来源与领域模型验收、任务消费精确发布模型、运行验证及修复回放。** 在这些路径可证明前，不能以 Resolver、Agent 演示或 Registry 页面替代数仓生产消费闭环。
