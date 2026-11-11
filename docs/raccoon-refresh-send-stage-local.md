# Raccoon 刷新：发送阶段证据与「发送前失败 / 执行结果未知」区分（本地，未接入 GatewayServer）

本文记录对 Raccoon 刷新 transport **发送阶段** 的审查结论、已落地的独立实现与注入测试，以及把
`GatewayServer` 接上这套证据的**精确接法**。本次改动只发生在独立文件里：

- 新增 `app/src/main/java/com/gaeul/allin/RaccoonRefreshSend.java`
- 修改 `app/src/main/java/com/gaeul/allin/RaccoonRefreshService.java`（transport 契约、响应完整性、分类）
- 修改 `app/src/test/java/com/gaeul/allin/RaccoonRefreshServiceOfflineTest.java`（新增注入用例）
- 本文档

`GatewayServer.java`、`RaccoonRefreshPolicy.java`、`RaccoonAccountService.java`、`AccountTokenCoordinator.java`
**没有改动**，`AccountTokenCoordinator.Store`、`RaccoonRefreshService` 的既有签名保持兼容
（`Response` 新增的是重载而非替换），因此接入前仓库仍可编译、行为与之前完全一致。

## 1. 审查发现（发送阶段）

刷新 POST 的请求体里就是**一次性** refresh token，厂商只要处理过就可能轮换。当前实现把这条路径上的
所有失败压成同一种「`IOException`」，于是两种情况无法区分：

| 现状 | 位置 | 后果 |
| --- | --- | --- |
| transport 抛 `IOException` 时不带任何阶段/字节证据 | `RaccoonRefreshService.refreshCandidate`（改动前 89–91 行） | DNS/连接被拒/握手失败（**可证明一个字节都没发**）与「请求写完但读响应超时」（**可能已轮换**）完全同型 |
| 网关在发送前就 `reserveRefreshFence`，但发送失败路径无 `resolveRefreshFence` | `GatewayServer.java` 264–280（lambda）+ 只有 commit 失败/成功才 settle | 一次普通的断网就会被永久记成「上次刷新结果未知」，用户被迫重新登录，而其实什么都没发出去 |
| transport 返回 `null` 被当成 `RaccoonAccountService.Failure(...,false)` | 改动前 91 行 | 「已收到响应」的语义被套到「没有响应」上，执行状态被丢掉 |
| 2xx 但响应体读不全（`complete=false`）会被折叠成空 JSON，最终落成 `Failure(expired=true)` | `readRaccoonHttpResponse` + 旧 95–99 行 | 轮换可能已成功的场景被报告成「需要重新登录」；而且 `complete=false` 无法从服务层看出来 |
| `if(!REFRESH_PATH.equals(path)) throw` 排在 `reserveRefreshFence` **之后** | 同一 lambda | 一个非法 path 也会白白烧掉一条一次性凭据的围栏记录 |
| `RefreshUncertain`（值可能已被消费）与 `RefreshFenceBlocked`（拒绝发送、什么都没发）都继承 `RefreshBlocked` | `GatewayServer` 696–716 | 二者被同一层 catch 吞掉时，前者必须保留围栏、后者本可安全重试，语义被抹平 |

## 2. 已落地的分类契约

`RaccoonRefreshSend` 只从「声明出来的阶段 + 字节数」推导结论，绝不看异常类型或消息：

```
NOT_EXECUTED  ⟺  stage ∈ {NOT_STARTED, NOT_CONNECTED}  且  requestBytesWritten == 0
UNKNOWN       其它一切情况
```

阶段枚举：`NOT_STARTED`（调用方拒绝发送 / 围栏拦住）、`NOT_CONNECTED`（DNS/TCP/代理/TLS 未建立）、
`WRITING`（已尝试写请求，写了 0 字节也算）、`WRITTEN`（请求写完，等状态行）、`READING_STATUS`、
`READING_BODY`、`UNSPECIFIED`（transport 没有声明证据）。

要点：

- **0 字节的写失败仍然算 UNKNOWN**。`write()` 抛异常时内核缓冲里可能已经有数据，客户端无法证明对端
  没收到；这与仓库既有 `AntigravityAuth.SendFailure` 的保守方向一致。
- 与 `AntigravityAuth` 相比唯一更严的地方：**没有声明证据的失败一律 UNKNOWN**（那里默认落到
  `NOT_CONNECTED`）。重放一次性 refresh token 不可逆，方向必须是失败关闭。
- `notSent()` / `unknown()` 两个工厂互相拒绝对方的阶段，因此不存在「声称未发送却承认写了字节」的对象；
  判定规则另以 `executionOf(stage, bytes)` 公开，便于测试与被接线方复用。
- `RaccoonRefreshSend.releasesReservation(Throwable)` 是网关唯一允许释放围栏的依据；非 `Failure`、
  `RaccoonAccountService.Failure`、`null` 全部返回 false。
- `diagnostic()` 只含阶段名与字节数，不含 token / URL / 请求体。

服务层归一（`RaccoonRefreshService.refreshCandidate`）：

1. transport 抛 `RaccoonRefreshSend.Failure` → 原样透传（保留声明证据）；
2. transport 抛其它 `IOException` → `wrap()` 成 `UNSPECIFIED`/未声明 → UNKNOWN，**原异常文本原样保留**
   （网关现有围栏文案与 `GatewayReleaseHardeningOfflineTest` 里「请重新登录」的断言依赖它，分类不得改写它）；
3. transport 抛 `RuntimeException` → `wrapCrash()` → UNKNOWN（崩溃同样不能证明没发出去）；
4. `response == null`、`response.complete == false`、2xx 但 `body == null` → UNKNOWN；
5. 收到响应后的 HTTP 4xx/5xx、业务 `code` 错误、200 但缺 access token → 仍然是
   `RaccoonAccountService.Failure`（**已执行**），类型与 `expired` 语义不变。

## 3. 注入测试（离线，不联网、不跑 Gradle）

`RaccoonRefreshServiceOfflineTest` 新增 7 组用例，全部通过注入的 `Transport`/`Store` 驱动：

| 用例 | 证明什么 |
| --- | --- |
| `classificationIsStrictAndFailClosed` | 遍历 7 阶段 × {0,1,60,-1,MAX}，只有「未写字节的 NOT_STARTED/NOT_CONNECTED」是 NOT_EXECUTED；工厂拒绝自相矛盾的证据；`wrap` 不改写已声明证据；非发送失败一律不得释放围栏 |
| `provablePreSendFailureIsNotExecutedAndRetryable` | 连接被拒 → NOT_EXECUTED、字节证据与阶段穿透服务边界、账号未被改动；释放后**同一个** refresh token 可再发一次并成功 |
| `writeStageAndUndeclaredFailuresAreUnknown` | 0 字节写失败 / 部分写 / 写完等状态行 / 读状态 / 读响应体 / 未声明 / `SocketTimeoutException` / 普通 `IOException` 全部 UNKNOWN 且不可释放；transport 崩溃同样 UNKNOWN |
| `responsesThatProveNothingAreUnknown` | 无响应、`complete=false`、2xx 无 body 都不得被当成「已执行」或「未发送」 |
| `receivedResponsesStayExecuted` | 400/401/429/500/503 与 200-缺 token 仍是 `RaccoonAccountService.Failure`，`expired` 分类不变，且**不是**发送失败类型 |
| `fenceReleasesOnlyProvenNotSentAndBlocksReplay` | 用最小围栏夹具复刻网关规则：可证明未发送 → 释放 → 可再兑换一次；UNKNOWN → 保留 → 重放请求在触网前被拦下（发送计数仍为 1） |
| `classificationIsStrict…` 内的负向断言 | `releasesReservation(null/IOE/vendor Failure)` 均为 false |

变异验证（把实现改坏，确认测试会红，全部 CAUGHT）：

| 变异 | 结果 |
| --- | --- |
| M1 `executionOf` 把 0 字节 `WRITING` 当未发送 | CAUGHT（`only an unwritten pre-send stage…`） |
| M2 服务层直接透传普通 `IOException` | CAUGHT（`case 6 must be classified`） |
| M3 无响应退回 `RaccoonAccountService.Failure` | CAUGHT（`missing response must be classified`） |
| M4 不再检查 `response.complete` | CAUGHT（`incomplete body must be classified`） |
| M5 `notSent` 接受写阶段 | CAUGHT（`notSent must reject a write stage`） |
| M6 `wrap` 把已声明失败重新包装成未声明 | CAUGHT（`declared evidence is never re-classified`） |
| M7 去掉 `RuntimeException` 归一 | CAUGHT（`a transport crash is an unknown outcome`） |
| M8 `wrap` 用固定文案覆盖 transport 原文 | CAUGHT（`the transport text is preserved`） |

运行方式（当前环境没有 Gradle，用 javac 直接跑；也不需要网络）：

```sh
javac -d /tmp/rc/main $(find app/src/main/java/org/json -name '*.java') \
  app/src/main/java/com/gaeul/allin/RaccoonRefreshSend.java \
  app/src/main/java/com/gaeul/allin/RaccoonRefreshService.java \
  app/src/main/java/com/gaeul/allin/RaccoonRefreshPolicy.java \
  app/src/main/java/com/gaeul/allin/RaccoonAccountService.java \
  app/src/main/java/com/gaeul/allin/AccountTokenCoordinator.java
javac -cp /tmp/rc/main -d /tmp/rc/test app/src/test/java/com/gaeul/allin/RaccoonRefreshServiceOfflineTest.java
java -cp /tmp/rc/main:/tmp/rc/test com.gaeul.allin.RaccoonRefreshServiceOfflineTest
# → RaccoonRefreshServiceOfflineTest passed
```

有 Gradle 的环境用既有任务即可，无需改 `build.gradle`（用例仍在该任务已注册的类里）：
`./gradlew :app:raccoonRefreshOfflineTest`

## 4. GatewayServer 精确接法（本次未执行，交给下一批）

### 4.1 替换 `raccoonRefreshService()` 里的 transport lambda（当前 259–281 行）

顺序很关键：**先校验 path，再建立围栏，再发送**。同时把每个阶段的失败升级成带证据的
`RaccoonRefreshSend.Failure`，并且只在「可证明未发送」时释放围栏。

```java
    private RaccoonRefreshService raccoonRefreshService() {
        RaccoonRefreshService service=raccoonRefreshService;
        if(service==null) synchronized(this) {
            service=raccoonRefreshService;
            if(service==null) service=raccoonRefreshService=new RaccoonRefreshService(raccoonTokenCoordinator(),(path,body) -> {
                // 1) 先校验，再消耗：非法 path 不得烧掉一条一次性凭据。
                if(!RaccoonRefreshService.REFRESH_PATH.equals(path)) throw new IOException("Invalid Raccoon refresh path");
                String wireRefresh=body==null?"":body.optString("refresh_token","");
                // 2) 一次性值离开进程之前落围栏。
                try { reserveRefreshFence(RaccoonRefreshService.PROVIDER,wireRefresh); }
                catch(RefreshUncertain alreadySent) {
                    // 这个值此前可能已被消费：必须保持围栏，绝不能按「没发出去」释放。
                    throw RaccoonRefreshSend.unknown(RaccoonRefreshSend.Stage.UNSPECIFIED,-1,-1,
                            "Raccoon refresh value was already sent",alreadySent);
                }
                catch(RefreshFenceBlocked refused) {
                    // 围栏拒绝发送，一个字节都没出去，也不存在新增记录（拒绝即回滚）。
                    throw RaccoonRefreshSend.notSent(RaccoonRefreshSend.Stage.NOT_STARTED,0,
                            "Raccoon refresh was fenced off before sending",refused);
                }
                RaccoonRefreshService.Transport fixture=raccoonRefreshTransportForTests;
                if(fixture!=null) {
                    try { return fixture.postJson(path,body); }
                    catch(IOException fixtureFailure) {
                        RaccoonRefreshSend.Failure classified=fixtureFailure instanceof RaccoonRefreshSend.Failure
                                ?(RaccoonRefreshSend.Failure)fixtureFailure:RaccoonRefreshSend.wrap(fixtureFailure);
                        if(RaccoonRefreshSend.releasesReservation(classified))
                            resolveRefreshFence(RaccoonRefreshService.PROVIDER,wireRefresh);
                        throw classified;
                    }
                }
                HttpURLConnection connection;
                try { connection=openVendorConnection(RACCOON_ORIGIN+path); }
                catch(IOException noLink) {
                    // 链接都还没开：不可能有请求字节到达厂商。
                    resolveRefreshFence(RaccoonRefreshService.PROVIDER,wireRefresh);
                    throw RaccoonRefreshSend.notSent(RaccoonRefreshSend.Stage.NOT_CONNECTED,0,
                            "Raccoon refresh link could not be opened",noLink);
                }
                try {
                    connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(RaccoonOAuthClient.TIMEOUT_MILLIS);
                    connection.setReadTimeout(RaccoonOAuthClient.TIMEOUT_MILLIS); connection.setRequestMethod("POST"); connection.setDoOutput(true);
                    connection.setRequestProperty("Accept","application/json"); connection.setRequestProperty("Accept-Encoding","identity");
                    connection.setRequestProperty("Content-Type","application/json; charset=utf-8"); connection.setRequestProperty("Cache-Control","no-store");
                    byte[] bytes=body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    connection.setFixedLengthStreamingMode(bytes.length);
                    OutputStream out;
                    try { out=connection.getOutputStream(); }
                    catch(IOException handshakeFailure) {
                        // 这套 HttpURLConnection 在 getOutputStream() 建链（DNS/TCP/TLS），
                        // 该调用抛异常时请求字节尚未写出。
                        resolveRefreshFence(RaccoonRefreshService.PROVIDER,wireRefresh);
                        throw RaccoonRefreshSend.notSent(RaccoonRefreshSend.Stage.NOT_CONNECTED,bytes.length,
                                "Raccoon refresh link failed before the request was written",handshakeFailure);
                    }
                    long written=0L;
                    try { out.write(bytes); written=bytes.length; out.close(); }
                    catch(IOException writeFailure) {
                        // 写失败时内核缓冲可能已收下部分字节：结果未知，不释放围栏。
                        throw RaccoonRefreshSend.unknown(RaccoonRefreshSend.Stage.WRITING,written,bytes.length,
                                "Raccoon refresh request write failed",writeFailure);
                    }
                    RaccoonHttpResponse response;
                    try { response=readRaccoonHttpResponse(connection,RACCOON_MAX_RESPONSE_BYTES); }
                    catch(IOException readFailure) {
                        throw RaccoonRefreshSend.unknown(RaccoonRefreshSend.Stage.READING_STATUS,bytes.length,bytes.length,
                                "Raccoon refresh response could not be read",readFailure);
                    }
                    if(!response.complete)
                        throw RaccoonRefreshSend.unknown(RaccoonRefreshSend.Stage.READING_BODY,bytes.length,bytes.length,
                                "Raccoon refresh response incomplete",null);
                    return new RaccoonRefreshService.Response(response.status,response.json==null?new JSONObject():response.json,true);
                } finally { connection.disconnect(); }
            });
            else service=raccoonRefreshService;
        }
        return service;
    }
```

说明：

- `RefreshUncertain` / `RefreshFenceBlocked` 都是 `GatewayServer` 的静态内部类，lambda 内可直接用短名；
  `RefreshUncertain` **必须先 catch**（它与 `RefreshFenceBlocked` 是兄弟，都继承 `RefreshBlocked`）。
- 不再需要旧的「先 reserve 再校验 path」顺序，也不要把 `resolveRefreshFence` 放到 `finally`：只有上面
  三个「可证明未发送」点允许释放。
- `readRaccoonHttpResponse` 内部会把读体异常折叠成 `complete=false`（超限同理），所以 `!response.complete`
  才是真正的判定点；`READING_STATUS` 的 catch 只兜 `getResponseCode()`。
- `RefreshFenceBlocked` 的极少数路径（目录 fsync 失败后把记录放回内存）会留下一条**仅内存**记录：
  后续重试会被当作「已发送/未知」挡住。方向是失败关闭，不会造成重放，按现状保留即可。

### 4.2 调用点把「未知」与「未发送」分开回报

各 `ensureRaccoonToken(...)` 调用点现在要么 `catch(Exception)` 一律 502/401，要么静默忽略。至少改
模型目录同步（6771 附近）这一处，让用户看到可行动的文案：

```java
                        try { chosen=ensureRaccoonToken(chosen.optString("id"),null,0); }
                        catch(RaccoonRefreshSend.Failure outcome) {
                            return errorResponse(outcome.provablyNotExecuted()?503:401,
                                    outcome.provablyNotExecuted()?"小浣熊刷新未发送，可稍后重试":"小浣熊刷新结果未知，请重新登录");
                        }
                        catch(Exception unavailable) { return errorResponse(502,"Raccoon account refresh failed"); }
```

- 其它调用点（319、574、1811、11470、13618、14277）可照抄同一模式；**不要**把
  `RaccoonRefreshSend.Failure` 与 `RaccoonAccountService.Failure` 合并处理，后者代表「响应已收到」。
- `RaccoonRefreshSend.Failure` 是 `IOException` 子类，既有的 `catch(IOException)`/`catch(Exception)`
  语义不变，未改的调用点仍能编译通过。

### 4.3 接入时要一起确认的边界

1. **`getOutputStream()` 是否真的是「最后一个可证明未发送的点」**：Android 的 `HttpURLConnection`
   实现（OkHttp）在该调用里完成建链与 TLS 握手，但不同版本/代理配置下不保证。接入时用仓库既有
   「端点探测/真机验证」方式确认一次：断网、连接被拒、代理不可达、证书错误四种情形下，异常必须来自
   `getOutputStream()`（或被 `openVendorConnection` 提前抛出），而**不是** `write()`。若不成立，则把该
   分支降级为 `unknown(WRITING,0,…)` —— 只是失去「可重试」的便利，判定方向仍然安全。
2. 释放围栏之后必须重新 reserve 才能再发：`reserveRefreshFence` 是唯一写入口，重复调用同一值在
   前一次已释放时是允许的。
3. 不要为 `refreshAfter401` 的「一次重放」预算做任何改动：`RaccoonRefreshPolicy.mayRetry401` 仍是
   唯一闸门，本次分类只影响「这次失败算不算把 token 花掉了」。
4. 若把 `Response.complete` 传成 `false`（例如读超限），服务层会直接判 UNKNOWN 并保留围栏——这是预期。
5. 兼容性：本次改动不会让既有网关测试的文案断言失败。围栏拦下的 `RefreshUncertain` 仍是
   `IOException`，`getMessage()` 仍是原来的「…请重新登录」；区别只是类型变成
   `RaccoonRefreshSend.Failure` 且多了一个 UNKNOWN 分类。按 4.1/4.2 接线后，建议把那几处文案断言
   升级为「文案 + `!provablyNotExecuted()`」双断言，避免以后又退回只看文本。

## 5. 本次未做

- 未改 `GatewayServer.java`（按要求），因此生产路径**尚未**产生阶段证据：网关仍走旧的 2 参 `Response`
  与裸 `IOException`，行为与改动前完全一致（分类只在服务层内部生效，绝不改变已有成功/失败结论）。
- 未联网、未发起真实请求、未运行 Gradle。
- 接入前有一处刻意的文案/分类不一致：网关围栏拦下时抛的是 `RefreshFenceBlocked`（文案写「本次刷新
  请求未发送」），而本层因为「未声明证据」把它判成 UNKNOWN。分类只看证据、不看文案，这是刻意的；
  按 4.1 接线后该分支会被显式映射为 `NOT_STARTED`，两者随即一致。
- 未改 `RaccoonAccountService` 的 `Failure` 语义（它继续表示「已执行」），也未把 `expired` 与执行状态
  混在一个字段里。
