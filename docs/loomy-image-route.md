# Loomy 绘图路由 `/v1/images/generations`

更新于 2026-11-09。对照片段：`magpie-community-reference/opencode-loomy-auth/source/index.mjs:396–435`
（`draw`）与 `:4216` 的 `_internal` 导出。本节记录**本地接线与治理**，真实服务未验证。

## 上游行为

- 路径：`POST {modelBase()}/images/generations`，即 `https://loomyad.xunfei.cn/api/v1/images/generations`。
- 请求头：`Content-Type: application/json;charset=utf-8`、`Accept: application/json`、
  `token: <session>`、`Authorization: Bearer <session>`。
- 请求体：**按宿主写好的原样发出**（聊天路径的 messages 改写不适用绘图）。
- 答案：普通 JSON（`{"created":…,"data":[{"url":…}],"points_consumed":110}`），不按 SSE 处理。
- 失败：`401/403` 一律变成 `401`「登录态已失效」；其它非 2xx 带出状态码与截断后的上游说明。
- `/images/edits` 不提供（上游从未测量过多部件编辑）。

## 本地实现（`GatewayServer.loomyImageResponse`）

`/v1/images/generations` 已登记进 `GatewayAccessPolicy.model()`，因此与其它模型接口同一套网关口令鉴权，
未知路由仍 404。分支本体只有一行转调，治理全部**复用现有实现**：

| 治理项 | 复用点 | 行为 |
|---|---|---|
| 隐私开关 | `PrivacyMasker.maskText/unmaskText` + `totalMasked`/`lastMaskedCount`/`lastMaskEnabled`/`lastMaskAt` | prompt 与请求体出站前按现有开关打码，命中数计入与聊天相同的统计；答案与错误说明按同一会话还原。占位符预算耗尽 → 400，不发出 |
| 模型治理 | `GatewayAccessCatalog.build(configJson, now, ZCodeGiftControl::peekSummary)`（与 `/v1/models` 同一判定） | `loomy/<model>` 必须出现在该清单里：供应商启用、账号可用、模型在目录内、通过 `exposed` 白名单。不在清单 → 403，不触网 |
| 账号可用性 | `GatewayAccessCatalog.loomyAccountUsable`（与 loomy 路由选号同一规则） | 只选启用、未 `off`、有 token、未过期、且账号目录覆盖该模型的账号；无可用账号 → 401 |
| 取消/超时 | `REQUEST_CONTEXT`（`RequestContext.check/attach/detach/remainingMillis`） | 发送前后检查，连接登记进取消表，连接/读取超时按 `remainingMillis()` 收紧；已取消或期限耗尽 → 503 |
| 出站节流 | `admitOutbound(target, request)` + `Ticket.markSent/close` | 与所有供应商一致的 `minIntervalMillis`/`rpm`/`maxInFlight` 与冷却；票据在 `finally` 归还，准入被拒 → 该状态码 + `Retry-After` |
| 模型必填 | 本地校验 | `model` 缺失/空白/仅前缀 → 400，绝不当作通配 |

其余：只接受 POST（否则 405）；请求体必须是 JSON 对象（否则 400）；只剥离精确的 `loomy/` 前缀；
固定主机、不跟随重定向；成功原样返回并加 `Cache-Control: no-store`、`X-Magpie-Served-By: loomy`；
上游非 2xx 保留状态码、正文经 `scrubUpstreamSecrets` 去掉账号 token 并截断 4096 字符；
超过 8 MiB 的响应直接 502，绝不返回被截断的 JSON；任何异常都报「执行结果未知」，**不重放**。

## 明确未做

1. **用量账本**：绘图不写 `usage.jsonl`，看板不含绘图点数；`points_consumed` 只随响应回到客户端。
2. **`/images/edits`**：上游无实测语义，不做多部件透传。
3. **账号轮换**：与 loomy 聊天路径一致，取第一个通过 `loomyAccountUsable` 的账号；失败不换号。
4. **图片能力判定**：清单里的 loomy 模型既含聊天也含绘图，网关无法区分哪个真能画；
   模型名原样透传，由上游拒绝（这种情况报上游状态码，不计为网关缺陷）。
5. **真实服务未验证**：全部为离线/环回 mock。

## 验证

- `LoomyDrawGovernanceHttpTest`（新，已登记 `app/build.gradle`）：隐私开/关两态（出站无邮箱、有占位符、
  统计推进；关闭时原样）、`exposed` 白名单（列入可画、未列入 403 且不触网）、未知模型 403、
  账号 disabled/off/过期 → 401、账号目录不含该模型 → 403、空模型/纯空白/仅前缀/别家模型 → 400、
  限流节拍（`minIntervalMillis=400` 时第二次 ≥300ms，结束后 ticket 归还）、
  期限耗尽 → 503 且不重放、不残留票据。
- `ProviderParityWiringHttpTest`（既有）继续覆盖路径/头/前缀剥离/上游 401·500/禁用供应商。
- **反向对照**：把 `GatewayServer` 换回未治理版本，同一用例第一处断言即失败（邮箱原样出站），
  证明断言有效。
