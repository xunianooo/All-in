# Qoder：额度耗尽 + 模型被禁用时的 429（0.2.64 对齐，已接线）

参照：`allin-package-maintenance/qoder-latest/package/index.mjs`（opencode-qoder-auth **0.2.64**，上游 plugins#880）。

## 上游行为（唯一 429 条件）

`ask()`（index.mjs:3574-3594）：请求的模型不被账号启用时，

1. listing **点名**了该模型且 `enable` 为假（`models(cred).off`，:3278-3300），并且
2. usage 读出「额度确实耗尽」（`creditsSpent` → `spentOf`，:2810-2843：按顺序消耗的
   `Credits` / `Add-on credits` / `Shared credits` 三池**合起来**无剩余），

才答 429：

```
usage limit reached: the account's credits are used up (800 / 800 credits), and Qoder lists "dfmodel" off for it until they refresh
```

其余保持原行为：off 但有余量 → 400；usage 读不到 / 不表态 → 400；listing 从未出现的 id → 直接 400。

## 实现

| 文件 | 职责 |
|---|---|
| `QoderCredits.java` | `spentOf` 移植：`spent(envelope)` 返回 `"800 / 800 credits"` 或 `""`；`""` = 无法确认，**不是**「还有额度」。含 `gfmt`（无千分位；1e-4..1e6 之外两位指数） |
| `QoderCatalog.java` | listing 的 `app`+`chat` 合并；`statedOff()` 的保守语义；`statedOffKeys()` 读实时目录；`disabledModels`+`disabledObservedAt` 只作面板提示 |
| `QoderRefusal.java` | 唯一判定：**只接受请求时读到的实时目录**，多账号按账号分别引用读数 → 429 JSON 或 **null** |
| `QoderUsage.java` | 额度只读读取：唯一地址、单次有界状态读取、整读 deadline、可取消、无自建线程 |
| `GatewayServer.java` | ①同步持久化 off 集合与其时间戳；②两个入口在候选解析失败处调用 `qoderDisabledModelQuota`（请求时实时读目录） |

### GatewayServer 的四处改动

1. `qoderAccountGuard` 的排除表加入 `disabledModels` **与** `disabledObservedAt`：目录同步自己写的字段
   不算「账号变了」，否则每次同步都会和上一次结果对不上而永久 409。
2. `syncQoderCatalog`：`QoderCatalog.remember(result, catalog.disabledModels)`（自动盖时间戳）。
3. 保存账号时把两个字段一起写盘；读目录失败时旧值（含旧时间戳）原样保留。
4. `/api/test-model` 与 `/v1/chat/completions`（`/v1/messages` 共用）在 `candidates.isEmpty()` 之后先问一次
   `qoderDisabledModelQuota`：非 null 才 429。

## 保守边界（每条都有变异测试守住）

### B2　`enable` 只认「明确说关闭」

上游用 JavaScript 真值判断 `!m.enable`，而字符串 `"false"` 在 JS 里是 **真**，即该模型对 Qoder 客户端是
**启用**的。Java 若按字符串比较就会把它当关闭，额度耗尽时错误 429。`QoderCatalog.statedOff`：

| `enable` | 结论 |
|---|---|
| 布尔 `false`、数字 `0` | **明确关闭**（唯一会进 off 集合的两种） |
| 布尔 `true`、非 0 数字 | 明确启用 |
| 字符串 `"false"` / `"0"` / `""` | **不确认**（JS 视为启用；误 429 比 400 更糟） |
| 缺字段 / `null` / 对象 / 数组 | **不确认** |

`QoderAuth.readCatalog` 本身也要求 `enable` 必须是布尔，否则整份清单读取失败、同步保留旧数据。

### B7　`null` 与 `undefined` 分开：字段存在性决定读哪个拼写

上游读 `u[a] !== undefined ? u[a] : u[b]`，随后 `p === null` 直接跳过。所以**驼峰名以 null 出现时，
它自己就是答案，不会回退到 snake_case**。若回退，就会把 Qoder 用 null 表示的那个池算进来，凭空得出
「已耗尽」。`QoderCredits` 现在用 `has()` 判存在性（`has` 对 `JSONObject.NULL` 也为真）：

| `qoderUsage` 内容 | 上游 | 这里 |
|---|---|---|
| 只有 `user_quota` | 用 snake | 用 snake ✔ |
| `userQuota` 为对象 | 用驼峰 | 用驼峰 ✔ |
| **`userQuota` 为 `null`，`user_quota` 有值** | 取 null → 跳过 | **跳过，不回落** ✔ |
| `userQuota` 是字符串/数组 | 跳过 | 跳过，不回落 ✔ |

内层字段仍按上游 `??` / `typeof === "number"` 语义：`total: null` 落到 `cap`，`used: null` 落到
`remaining`。

### B5　不再使用陈旧目录，改为请求时实时读取

同步写入的 `disabledModels` **不再参与判定**——它只说明「同步那一刻」，而 Qoder 在额度刷新时会把模型
重新打开，用旧集合判决就会把一个已经能用的模型永久判成额度耗尽。现在：

- 每个可用账号都在**请求时**用 `QoderAuth.readCatalog` 重读一次真实目录（与本仓目录同步同一条 COSY 签名
  读法，只需 uid/machineId/token），只信这次读到的 `disabledModels`；
- 读不到（401/403、超时、协议异常、非 2xx）⇒ 不判，保持原有 400；
- 实时目录说「开着」或根本没列出 ⇒ 不判；
- `disabledModels` + `disabledObservedAt` 仍写盘、仍出现在同步响应里，但**只作面板提示**，
  `QoderCatalog.storedOff` 的文档明确写着「never authorizes a refusal」。

因此「旧集合把已开启模型判成 429」在结构上不可能发生；测试用「模型被上游重新开启但没重新同步」
的路径验证：请求 400、不查额度、不含额度句；重新同步后正常 200。

### B8　多账号文案不再拿一个账号的额度代表全部

`QoderRefusal.confirmed(liveOff, model, List<String> spentPerAccount)` 逐个引用各账号**自己的**读数：

- 1 个账号：上游原句
  `usage limit reached: the account's credits are used up (800 / 800 credits), and Qoder lists "X" off for it until they refresh`
- N 个账号：
  `usage limit reached: the accounts' credits are used up (account 1: 800 / 800 credits; account 2: 1200 / 1200 credits), and Qoder lists "X" off for them until they refresh`

序号只是顺序标签，**不含 uid / 邮箱 / machineId / token**（有隐私断言）。任何一项读不到或有余量 ⇒ 不判。

### B9　状态行单次有界，不依赖 `getResponseCode` 重试

原实现用 200ms 读超时 + 超时重试来"切"头部等待。实测（JDK 25，环回、服务端延迟 600ms）：

```
readTimeout=200  -> SocketTimeoutException in 209ms, server saw 1
readTimeout=5000 -> status=200 in 605ms,           server saw 1
```

即 `getResponseCode` 在读到状态行前超时会**直接抛**，是否重发取决于实现细节（这里不重发，别处可能重发）。
依赖这种行为既不可靠、也可能多打一次请求。现在：**单次** `getResponseCode()`，连接/读取超时都取
`min(本次上限, 请求剩余时间)`；头部再慢也只有这一个预算。整读 deadline 由响应体循环保证（每读一次查一次
时钟），超时/取消即断开并抛异常；全程在调用线程，**不新建线程**。

### B6　401/403 不轮转设备令牌（有意为之）

可复用的 CAS 刷新链是 `QoderAuth.refreshCandidate` + `GatewayServer.qoderPersistRefresh`，但 Qoder 的
refresh token 一次性：先换新对再 CAS 落盘，若落盘时账号已变或写盘失败，**新 refresh token 就丢了、账号
被弄坏**。而这里刷新只是把 400 升级成 429 的装饰性优化。故 401/403 → 视为读不到 → 保持 400；测试断言
refresh 调用为 0 且账号记录逐字节未变。要做的前提是「先落盘再使用」或可回滚的令牌暂存。

## 预算与失败保留

- 所有网络读取共用**一个**总预算（默认 8s = `qoderUsageTimeoutMillis`），且再被本请求
  `RequestContext.remainingMillis()` 收紧；预算用尽 ⇒ 视为读不到。
- 每次起实时目录读取前要求剩余预算 ≥ `min(1.5s, 总预算/2)`，避免在只剩几百毫秒时再开一次读取。
- 事实：`QoderAuth.readCatalog` 自带 15s/20s 超时且不可取消（本次不许改 `QoderAuth`），所以**单次**目录
  读取最坏可能超过 8s 预算；多账号时按账号串行、每次前查预算。这是已知上界，已如实记录，不影响正确性
  （越界只会让判定变成「读不到」，永不产生 429）。

## 验证（未跑 Gradle；javac + java 直接编译与运行；不访问任何真实账号）

- 全量 `main`+`test` 编译通过（android-37 `android.jar` + nanohttpd 2.3.1 + 仓库自带 patched org.json；
  `R` 用 /tmp 桩，不入仓）。
- `QoderDisabledModelQuotaOfflineTest`：**158** 条断言（B2 真值表、B7 存在性/内层 `??`、`spentOf`/`gfmt`
  与真实 `index.mjs` 的 `_internal` 逐字节一致、B3 滴流/预算/取消/线程、B9 慢头部单次请求、多账号文案与
  隐私）。
- `QoderDisabledModelQuotaHttpTest`：**18 组**环回端到端（同步持久化与失败保留、B2、B7 null 池、
  三个入口、B3 超时与滴流、B4 精确地址、B5 模型被重新开启、B8 双账号四种组合、B6 不轮转且不改账号、
  非 Qoder 供应商、隐私无泄漏）。
- **变异校验**（改一处、跑、逐字节还原并 `diff -q` 确认）：

| 变异 | 结果 |
|---|---|
| B7：`null` 回落 snake（旧写法） | 失败于「a camel pool present as null hides the snake one」 |
| B5：用存盘记录判定（旧写法） | 失败于「a model Qoder lists on is not a quota」 |
| B8：所有账号共用一个读数 | 失败于「account 1's own reading」 |
| B9：状态行用 200ms 短超时 | 失败于「a slow header leg still reads」 |
| B2：字符串 `"false"` 当关闭 | 失败于「the string "false" is not off」 |
| B3：去掉整读 deadline | 失败于「and the request is not held by it」 |

- 回归：Qoder 全套 + CatalogReadIntegrity/ProviderCatalogCommit/TokenPersistence/AccountTokenCoordinator/
  ProviderParityWiring/ProviderBilling/GatewayWiringAudit/AccountOps/GatewayAccessCatalog/ConfigurationView/
  UsageLedgerPartial/OutboundRouting/RecentUsage/SessionAffinity/Trae*/Responses 共 29 个套件全 PASS。

## 仍未覆盖 · 如实记录

- **不缓存判定**：每次请求最多 N 次目录读 + N 次额度读（N = 可用账号数），共用 8s 预算。
- **单次目录读取的时长不由我们控制**（`QoderAuth.readCatalog` 的 15s/20s，不可取消）；越界只导致
  「读不到」，不会产生错误 429。
- 额度查询不过 `OutboundLimiter`：此刻还没有 `RouteTarget`，无可绑定预算；与目录读取同性质。
- 429 不带 `Retry-After`（上游也不带），不改登录态、不改账号记录。
- 面板上的 `disabledModels`/`disabledObservedAt` 是「上次同步看到的」，与判定无关；若将来要在面板提示
  「已过期」，应使用 `QoderCatalog.OFF_MAX_AGE_MS` 作显示阈值，而不是判定阈值。
