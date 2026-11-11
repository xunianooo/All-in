# Raccoon 响应整形：接线结果

对照片段：`magpie-community-reference/opencode-raccoon-auth/source/index.mjs:506–563`
（`rewriteSseModel`）与 `:1151–1161`（签入状态）。

## 纯函数（已测）

`app/src/main/java/com/gaeul/allin/RaccoonResponsePolicy.java`

| 能力 | 上游行为 | 本类 |
|---|---|---|
| 签入状态 | `res.status===401 → "expired"`；`didRenew && res.ok → "renewed"`；否则 `"kept"` | `signInState(status, renewed, ok)` |
| 头名 | 恒设 `X-Magpie-Sign-In` | `SIGN_IN_HEADER` |
| JSON 模型名 | 有 `model` 键才改写；改写时删 `content-length` | `rewriteJson(text, requestedModel)` |
| SSE 模型名 | 仅 `data:` 帧且 `model !== requestedModel` 时改写；`[DONE]`／非 data 行／解析失败原样透传 | `rewriteSseLine(line, requestedModel)` |

测试：`RaccoonResponsePolicyOfflineTest`。

## 网关接线（2026-11-09 完成，GatewayServer.java）

1. **非流式**：`forwardAttempt` 在响应侧中间件之后、`toAnthropicBodyIfNeeded` 之前调用
   `RaccoonResponsePolicy.rewriteJson(body, raccoonVisibleModel(requestedModel))`。
   `raccoonVisibleModel` 优先 `CLIENT_MODEL`（model-map 别名），否则用客户端请求的模型串，
   与 `/v1/responses` 现有回显口径一致。响应随后由 `newFixedLengthResponse` 重新构造，
   因此旧 `Content-Length` 不会随改写后的短名发出。
2. **流式**：`forwardAttempt` 进入公共流之前，用 `RaccoonModelStream`（同文件私有内部类）
   按行调用 `rewriteSseLine`；单行超过 256 KiB 时原样透传，避免异常帧撑大缓冲。
   只在 OpenAI 形状输出路径包裹——Anthropic 桥自己会写 `model`。
3. **头**：`forwardAttempt` 的 `finally` 给所有 Raccoon 响应（成功／失败／流式）加
   `X-Magpie-Sign-In`。`expired` 由 401 决定；`renewed` 只有本次交换真的 401 后轮换过
   token 且最终 2xx 才成立（`raccoonSignInRenewed` 现在声明在发送循环之外）。

## 顺带修掉的一个死路径

原 401 重放条件要求 `raccoonError.complete`（即上游错误体能被完整读回）。实测：
JDK `HttpURLConnection` 在 `setFixedLengthStreamingMode` 下遇到 401 会抛
`HttpRetryException` 且 `getErrorStream()` 返回 null，于是 `complete` 永远为 false，
**每一家 Raccoon 账号的续期在真实 HTTP 下都不可达**（只返回 `expired`）。
上游插件只按状态码续期、不要求错误体，因此条件改为只看
`!renewed && account 存在`；是否允许刷新（非 desktop、有 refresh、只重放一次）
仍由 `RaccoonRefreshPolicy.mayRetry401` 与 `ensureRaccoonToken` 的 CAS 负责。

## 验证

`ProviderParityWiringHttpTest`（新，已登记 `app/build.gradle`）用双环回 HTTP 覆盖：

- 非流式：`model` 变成客户端请求串、内容不动、上游仍收自己的模型名、头为 `kept`；
- 上游响应无 `model` 键时不得凭空造一个；
- 流式：每帧改写、`[DONE]` 与非 data 行保留、内容不丢、头为 `kept`；
- 401 且无账号→`expired`；401 有账号→刷新一次、重放用新 token、头为 `renewed`、只重放一次；
- 非 Raccoon 供应商不受影响（无该头、仍报上游模型名）。

仍属离线／mock 结论，未对真实小浣熊服务验证。
