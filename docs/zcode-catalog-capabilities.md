# ZCode 动态目录能力：从上游 provider config 同步并持久化

更新于 2026-11-09。上一版只做到"读取已保存元数据"，而真实账号的 `modelMeta` 里什么都没有；
本轮补上**数据来源**：同步模型目录时顺带读 ZCode 自己的 provider config，把每个模型声明的
档位/上下文/输出上限/图片能力写进 `modelMeta`。全程未跑 Gradle。

## 上游调用链（逐段对照 `opencode-zcode-auth/source/index.mjs`）

| 步骤 | 上游 | 本实现 |
|---|---|---|
| 客户端配置地址 | `clientConfigs()`（`:1885-1897`）：`GET ${ZCODE}/api/v1/client/configs?app_version=${APP_VERSION}&platform=${platform()}`，10 分钟缓存 | `syncZCodeCapabilities` 第一步，`ZCodeAuth.ZCODE` + `app_version=ZCodeAuth.APP_VERSION` + `platform=zcodePlatform()`；不做缓存（只在用户点同步时跑一次） |
| 请求头 | `call()`：`Content-Type`/`Accept`/`User-Agent: ZCode/<ver>`，url 以 `zcode.z.ai/` 开头时加 `X-Device-Mid`；**不带 Authorization** | 同：`Accept`、`User-Agent: ZCodeAuth.UA`、`X-Device-Mid`（账号的 device，否则与其它 ZCode 调用同一派生值）；**不发送任何凭据** |
| provider config 地址 | `remoteConfig()`（`:1871-1883`）：`c.configs.builtin_provider_config_json`，必须以 `https://` 开头 | `ZCodeProviderConfig.configUrl`：要求 https、host 非空、无 userinfo、长度 ≤2048，否则跳过 |
| 抓取文档 | `fetch(url, {headers:{User-Agent}})` | `zcodeConfigDocument`：同一 `openVendorConnection`（走既有网络配置）、不跟随重定向、12s/15s 超时、2MiB/4MiB 上限、仅 2xx |
| 选模型 | `modelsOf(cfg, plan)`（`:1802-1836`）：`providerConfigRules.providerRules[providerId===plan].builtinModelIds` + `builtinProviderModelRules`（`config.enabled===false` 记为 off），按小写去重保序 | `ZCodeProviderConfig.models` 同序同语义 |
| plan 归属 | `planID(base)`：含 `/zcode-plan/`→start、含 `bigmodel.cn`→bigmodel、否则 zai | `ZCodeProviderConfig.planId` 三条常量一致 |
| 每模型能力 | `modelRules[].modelMatch` 正则 `^(?:re)$` 忽略大小写；取 `properties.contextWindow`、`optionSpecs.maxOutputTokens.max`、`properties.inputFormat.supportsImage`、`optionSpecs.reasoningLevel.values`（disabled→none、enabled→high） | 同；Java 正则编译失败即跳过（上游也是跳过） |
| 失败 | `zcodeConfig().catch(() => null)` → 用内置表，magpie 保留上次列表 | 返回 false → 保留既有 `modelMeta`，列表照旧刷新 |

## 落盘与生效

同步分支（`/api/auth/{provider}/sync-models`）的 zcode 段现在：

```java
fetched = fetchZCodeModels(base, token);                       // 列表仍来自 /models
JSONObject zcodeMeta = carriedZCodeCapabilities(prov, fetched); // 先承接已保存的能力
syncZCodeCapabilities(base, zcodeSyncDevice(prov, token), zcodeMeta);  // 再叠加配置声明
```

- **承接**：只为本次真正列出的模型复制上一次的条目 → 保存的目录不会比列表更宽，也不会更窄。
- **叠加**：配置声明了什么就覆盖什么，**从不删除**；读取失败等于什么都没发生。
- **不臆造**：某个模型一个字段都没声明时，**不写条目**（避免"没声明"被读成"0"）。

字段落点与消费者：

| 字段 | 谁在读 |
|---|---|
| `efforts` | `GatewayServer.savedZCodeEfforts` → `ModelCapabilities.zcodeDynamic` → `fitLevel`：档位贴到模型真正的档位，决定 `output_config.effort` 与 `thinking.budget_tokens` |
| `maxOutput` | `catalogOutputDefault`：Anthropic 转换路径的缺省输出上限，取代按型号硬编码的 128000/64000 |
| `contextWindow` | 仅记录（网关目前不用它构造请求，与上游 `limit.context` 对齐） |
| `image` / `zcodePlan` | 仅记录（可解释性：这批规则来自哪个 plan） |

普通 ZCode OpenAI Chat（非 Anthropic 转换）仍不注入 `max_tokens`（`effectiveOutputLimit` 里
`plainZCodeChat` 分支为 0），与本轮之前一致。

## 两个我无法在此环境验证的变量（如实记录）

1. **`platform` 取值**：上游发 `darwin-arm64|win32-x64|linux-x64`（`process.platform`+`arch`）。
   本 App 不是这些构建，所以按真实身份发 `android-<arch>`（`Build.SUPPORTED_ABIS` 映射
   arm64/arm/arm64-v8a…；取不到时只发 `android`）。**没有实测该端点是否接受这个值**；
   若不接受，效果是"能力读不到、目录照旧"（已按失败路径设计并有测试），而不是同步失败。
2. **`app_version` 门控**：上游注释说 ZCode 按 `app_version` 判能力。这里沿用
   `ZCodeAuth.APP_VERSION`（App 其它所有 ZCode 调用已经在用的同一个值），**没有**为了这个端点
   另造版本号。若真实端点要求更高版本才给完整配置，表现同样是"读不到→保留旧目录"。

## 验证（不跑 Gradle）

全量：`155 main + 205 test` 源重编译到 `/workspace/parity-wiring/{all-main,all-test}` 后跑注册用例。

新增两个用例：

- `ZCodeProviderConfigOfflineTest`（纯函数，逐条对照上游 `modelsOf`）：planID 三种 base；
  id 来源（providerRules + builtinProviderModelRules）、大小写去重保序、`enabled:false` 移除、
  别的 plan 不贡献；四个字段与 `disabled/enabled` 映射；未匹配模型"什么都没声明"且不建条目；
  不可编译正则/超长 pattern/类型错误/非整数数值/超量模型/超长 id 全部安全跳过；`configUrl`
  只放行 https（拒绝 http/ftp/协议相对/javascript:/空 host/userinfo/超长）。
- `ZCodeCapabilitySyncHttpTest`（端到端，双环回）：真实 `/api/auth/zcode/sync-models` 触发，
  断言 (a) 落盘 `efforts=[low,high]`、`contextWindow=200000`、`maxOutput=65536`、`image=true`；
  (b) 没声明的 `glm-5-turbo` 不产生条目；(c) 随后真实 chat 请求的上游 Anthropic body 里
  `max_tokens=65536`、`output_config.effort` 由 `max` 贴成 `high`、`thinking.budget_tokens=24000`；
  (d) 配置链两个请求**不带 Authorization**、带 `ZCode/` UA 与 `X-Device-Mid`、查询串含
  `app_version` 与 `platform`；(e) 配置端点 503 时同步仍 200、列表刷新、**旧 efforts/maxOutput 存活**、
  不为未描述模型编造条目；(f) 文档无声明时 `modelMeta` 为空、`effort` 与 budget 回到内置的
  `max`/32000；(g) `http://` 配置地址永不抓取。

**反向对照**：保留同一 seam、只删掉 `syncZCodeCapabilities` 调用重编译，用例在
"glm-5.3 has a saved entry: {}" 处失败 → 断言确实在测能力同步，不是空过。

## 仍未完成

1. 真实 ZCode 端点未验证（`platform` 与 `app_version` 门控见上）；未构建 APK、未签名、未装机。
2. 未做 10 分钟客户端配置缓存（上游有；本实现只在手动同步时读一次，不构成功能差异）。
3. 模型列表来源仍以 `/models` 为准，而非 `modelsOf`；上游用配置列表并在读不到时回退内置表。
   保留 `/models` 是为了不改变"账号真的能用哪些模型"的既有来源，属于有意取舍。
4. `contextWindow` 已存但网关尚未用它做任何决策（Guard 有独立配置）。
