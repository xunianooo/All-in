# 两处有界重发：WorkBuddy 原生签名拒绝、Qoder 上限拒绝

更新于 2026-11-09 08:3x UTC。两处都只重发**一次**，且只有拿到明确拒绝才能重发；未知执行一律不重发。
实现集中在 `GatewayServer.java`（`forwardWorkBuddyNative`、`forwardQoder` 及其两个私有辅助），
另有三个小文件配合（见文末“改动面”）。

## 1. WorkBuddy 国内原生 Messages：签名拒绝后重发一次

### 上游语义

`magpie-community-reference/opencode-workbuddy-auth/source/index.mjs:3926-3931` 的 `if (native && res.status >= 400)`：
读回错误体，若命中 `BAD_SIGNATURE`（`:4123-4128`：`thinking_signature_invalid` / `invalid \`signature\` in
\`thinking` / `block is bound to a different conversation` / `"internal_code":"11159`），就把
`withoutSignedThinking(body)`（`:723-740`）——**去掉全部 thinking 块**，只剩空内容的 assistant 补一个空
text 块——再发一次；`null` 表示本来就没有块可去，也就是同一个请求，不重发。

### 本实现

- 判定与去块：`WorkBuddyNativeSignatureRecovery`（新，纯函数）。状态 `>=400` 才算，日志体超过 1 MiB 不解析；
  `11159` 后紧跟数字（如 `111590`）不算同一 code；`redacted_thinking` 保留（上游也只删 `thinking`）。
- 重发条件：`forwardWorkBuddyNative` 里 `send==0` 且 `isSignatureRefusal` 且 `withoutThinkingBlocks != null`。
  第二次直接返回结果，不再有第三次。
- **每次发送各自准入**：新增 `prepareNativeSend(...)`，每次物理 POST 都走 `admitOutbound`（因此
  `minIntervalMillis` / `rpm` / `maxInFlight` 对这种重发同样生效，不能借“重试”绕过节拍），拿到**自己的**
  票据、登记**自己的**连接、按 `remainingMillis` 收紧超时、各自 `markSent` 与 `handoff` 记账。
- **票据不泄漏**：发下一次之前先 `releaseNativeSend(...)`，把上一次的票据 `close()` 且把该连接从
  `RequestContext` 注销；`finally` 只回收“最后那一次”的票据。测试断言两次发送后
  `OutboundLimiter.active()==0`、`queued()==0`、注册连接数为 0。
- **取消可达**：第一次连接在重发前注销、第二次连接在真正写入前 `attach`，所以取消/超时能断到在飞的那一次；
  测试在第二次请求到达时断言注册表中恰好 1 条连接。
- **未知执行不重发**：只有拿到 HTTP 状态码+错误体才会进入判定；连接中断/读错走
  `OutcomeUnknownException → NoReplayException`，直接 502 并保持 `out.sent=true`（有专门用例：上游收下请求
  体后直接断链，断言只发 1 次、票据清空）。
- 顺带修掉文档里列为“遗留 3”的不对称：`finally` 改用 `result.connection`（而非
  `connection(transportResponse)`），hook 已登记但 transport 随后失败时也会注销，不留悬挂条目。
- 第二次发送后若连接失败/校验失败，`out.sent` 保持 `result.sends>0` 而不是被重置为 false，避免把“已经问过
  一次（被拒）”的请求记成从未发出。

## 2. Qoder：明确的上限拒绝后按新上限重发一次

### 上游语义

`opencode-qoder-auth/source/index.mjs:1548-1578`（`outputCap` / `reclamp`）与 `:3599-3603`：识别
`range of max_tokens should be [lo, hi]` 这一类拒绝，把 `parameters.max_tokens` 降到 `hi`、记住该模型的上限
（`LEARNED_CAPS`，后续请求一开始就按它发），然后**只再问一次**；请求本来就没超过 `hi` 时不重发。

### 本实现

- `QoderFailurePolicy`：新增 `rememberRangeCap(modelKey, cap)`（`learnRangeCap` 改为复用它，行为不变）与
  `reclampTo(body, modelKey, cap)`——只在 `asked > cap` 时改动并记忆，否则返回 false（同请求不重发）。
- `QoderStream.Failure`：新增 `maxTokensCap` 字段（`rangeCap(body)` 得来；旧的 5 参构造保留，其它调用点不变），
  因为拒绝体只活在 stream 内部，网关原先拿不到这个数字。
- `forwardQoder`：发送包一层 `for(send=0;;send++)`；失败时先 `admitted.close()` 把本次票据还掉，再决定是否
  `reclampTo(...)` 重发。第二次发送重新 `admitOutbound`（节拍、票据各自独立），成功则照常交付。
- 不重发的情形：拒绝没点名上限、请求没超过上限（改了也一样）、非 range 拒绝（如 `illegal prompt`）、流已建立
  之后的任何失败（`Failure.sentOrUnknown` 路径原样透出）。
- 学习到上限后，`QoderAuth.buildChatBody` 的 `QoderFailurePolicy.modelLimit(...)` 会在**发送前**就用它，
  不必每轮都先撞一次拒绝。

## 改动面（本批）

| 文件 | 变化 |
|---|---|
| `GatewayServer.java` | `prepareNativeSend` / `releaseNativeSend`；原生发送改为有界重发；`finally` 回收与注销；Qoder 发送循环 |
| `WorkBuddyNativeSignatureRecovery.java` | 新增：签名拒绝判定 + 去 thinking 块（纯函数） |
| `QoderStream.java` | `Failure` 增加 `maxTokensCap`（新增 6 参构造，5 参保留） |
| `QoderFailurePolicy.java` | 新增 `rememberRangeCap` / `reclampTo`，`learnRangeCap` 复用前者 |
| `app/build.gradle` | 登记两个新用例 |
| `docs/native-signature-and-reclamp-retry.md` | 本文 |

## 验证

不跑 Gradle：用 SDK `android.jar` + 仓库内补丁版 `org.json` + nanohttpd，把当前工作树**全部** main 与 test 源
重编译到 `/workspace/parity-wiring/{all-main,all-test}`，再逐个运行 `app/build.gradle` 的注册用例。

新增用例：

- `WorkBuddyNativeSignatureRecoveryOfflineTest`（纯函数）：拒绝文识别（4 种措辞、5 种状态、成功体不算、
  无关 400、`111590` 不算、空/超大不吃）、去块（签名/未签名都删、可见文本与工具调用保留、`redacted_thinking`
  保留、剩空补空 text 块、不改入参、无块返回 null、user 内容不动）。
- `NativeRecoveryHttpTest`（双环回 HTTP）：
  - 原生：11159 一次 → 第二次 200，断言**恰好 2 次** POST、第一次真带外来签名、第二次无 thinking 且保留可见
    内容与整段会话、同一模型、仍要流；两次发送间隔 ≥400ms（配置 `minIntervalMillis=500`，证明重发走了节拍）；
    第二次到达时注册连接恰好 1；结束 `active()==0`、`queued()==0`、注册 0；
  - 成功只发 1 次；无 thinking 块可去时 1 次并原样 400；无关 400 时 1 次；上游收下请求体后断链时 1 次、票据清空；
  - Qoder：100000 被 `[64, 8192]` 拒绝 → 第二次按 8192 发出并交付答案，`request_set_id` 不变，之后
    `modelLimit` 已按 8192；请求未超上限时 1 次；无关拒绝 1 次。

**边界**：以上全部是离线/mock，没有对真实 WorkBuddy 或 Qoder 验证；真机是否出现 11159、Qoder 是否仍用这套
range 文案，需要用户授权后再测。未构建 APK、未签名、未装机、未上传。
