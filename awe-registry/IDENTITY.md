# 消费身份、委托与行列范围

数据消费与模型作者、审核者、生产平台使用独立身份。请求中的 `consumer.purpose` 是待检查的用途，`principal`、角色、租户、已审批或 JWT 的 `roles` 不会自行授予数据权限。模型定义和平台执行也不能替代当前授权。

## 服务 Token 与 OIDC JWT

原有 `AWE_CONSUMER_POLICIES` 的 `token` 条目保持兼容。接入企业 OIDC 时，将该条目的 `token` 替换为受部署方维护的 `oidc` 映射；两者必须二选一，其余允许范围同样由 AWE 配置：

```json
{
  "principal": "tenant-a-reader",
  "oidc": {
    "issuer": "https://identity.example.test",
    "subject": "example-sso-user",
    "authorizedParty": "data-agent-client",
    "delegatedActors": ["approved-agent-service"]
  },
  "functionIds": ["function.activity.device_count"],
  "purposes": ["analysis"],
  "fields": ["device_count"],
  "maxRows": 100,
  "maxBytes": 65536,
  "maxWindowSeconds": 604800,
  "rowScopes": [
    {
      "datasetId": "dataset.activity.events",
      "fields": ["tenant_id", "device_id", "event_time"],
      "filters": [
        {"field": "tenant_id", "operation": "EQ", "type": "STRING", "values": ["tenant-a"]}
      ]
    }
  ]
}
```

配置项：

| 环境变量 | 约束 |
|---|---|
| `AWE_OIDC_ISSUER` | 明确信任的发行者；必须与签名后的 `iss` 及服务端 policy 映射一致 |
| `AWE_OIDC_AUDIENCE` | 本服务期望的 audience；其他 API 的 token 不能复用 |
| `AWE_OIDC_JWKS_JSON` | 来自可信 IdP 的公钥集，不允许私钥；与 URL 二选一 |
| `AWE_OIDC_JWKS_URL` | 固定 HTTPS 公钥端点，仅回环测试允许 HTTP；不跟随重定向 |

实现使用标准 JOSE/Spring JWT 校验，限定 RS256、显式 `kid`、RSA 公钥及 issuer/audience/有效期。`exp` 必须存在，`nbf` 若提供则检查；时钟容差为 30 秒，未来 `iat` 超过容差会拒绝。不从 JWT 头的 URL 或内嵌 JWK 获取信任根。

远程公钥响应有字节和时间上限，成功公钥缓存 60 秒；未知 key 的刷新有最小间隔，过期缓存刷新失败时不能继续沿用过期信任。缺少或无法获取信任配置返回 503，无效凭据返回 401，不能伪造身份成功。

`authorizedParty` 若配置，必须精确匹配已验证 `azp`。签名中的 `act` 委托链仍须逐项属于服务端 `delegatedActors`，且不超过三层；未知主体、客户端或委托不会继承其他人的权限。主体和委托是身份归因，不等于业务事实认证。

## 字段与行范围

- `fields` 限制调用可以要求的输出字段；未提供 `rowScopes` 时，它也是旧策略的输入字段允许清单。存在 `rowScopes` 时，每个实际输入 Dataset 还必须有对应规则，`rowScopes.fields` 独立限制其可用输入字段，不能因指标只返回一个数就隐式授予全部来源字段。
- 行过滤仅使用类型化字段、比较/IN/空值运算和值；不会解释 SQL。参数由服务端绑定到实际 Dataset 与 source alias，再强制注入每次 Scan，用户模型或候选计划不能删除这些条件。
- `filters: []` 表示该 Dataset 仅施加列允许清单，不隐含其他行限制。缺失 Dataset 规则、字段不存在或类型不符会拒绝。
- EXTRACT 与 QUERY 都注入同一 policy 生成的服务端强制条件；EXTRACT 使用预编译参数，不解释过滤值中的 SQL。BI 绑定必须显式支持 `PREDICATE_V1`，否则存在行条件时拒绝执行。未接入强制过滤的其他调用方仍默认拒绝。
- 角色和发行者已验证也不代表源端权限已就绪；执行平台仍应验证来源身份、受控范围、预算和实际来源授权。

`policyHash` 覆盖正式允许范围、OIDC 映射及行条件，不包含 Token 或其摘要。服务 Token 轮换不改变原有相同允许范围的政策 Hash。运行证据可记录已验证的机制、issuer、subject、authorizedParty、委托及有效期，不保存完整 JWT。

这套能力允许接入真实企业 IdP，但仓库测试和示例配置不代表某企业 SSO、用户目录及生产行权限已经验收。企业 trust root、主体映射、来源授权和实际部署仍需分别配置验证。
