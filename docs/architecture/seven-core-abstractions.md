# AWE 七类核心抽象与类型层级

F0/F1a 已实现了 EntityRef/Grain/TemporalContract/RelationContract 等基础字段和 Registry，但并未实现核心七类抽象。此增量将它们作为 **Java 21 sealed metamodel** 正式引入，仍属基础内核，不宣称完成所有可执行规则。

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
- **当前 F1a Registry 仍使用 JSON 编辑和基本字段校验。** 这套七类 Java 类型尚未完全贯穿 Registry 持久化/REST/API 和 Schema 版本升级，这是下一项必做的工程集成，不应以类型已创建冒充整体实现完成。

## 为什么避免一开始强制所有复杂业务进入七类

这七类定义 AWE 元架构的演进边界，不意味业务数仓团队需要学习七类对象并逐一填写。
数仓建模入口继续围绕 BusinessEntity、Fact/Dataset、Metric、Dimension、Feature、Pipeline、DataProduct；
Web 和 CI 将自动生成/关联 Function、Contract、Evidence，以及 Action 的候选要求。
另须明确不支持的语义类型应失败关闭，不能利用自定义 JSON 片段绕过规则。

## 本轮验收

Java CI 的正反契约测试覆盖对象身份、关联扇出、DAU 去重聚合、特征历史可用性、Function/Action 区分、Contract 阶段校验、Evidence 盲区与 sealed 家族闭合。

**下一项核心工程工作：从完整元模型到类型化发布表示、Schema Evolution 和模型迁移，不应跳跃到 Agent Resolver。**
