# All In

把一台安卓手机变成多供应商 LLM 网关：内置 NanoHTTPD 服务（默认 `0.0.0.0:3425`），
对外提供 OpenAI（`/v1/chat/completions`）与 Anthropic（`/v1/messages`）兼容接口，
聚合各家供应商账号池，配套 WebView 管理面板。

## 应用信息

- 名称：All In
- 包名：`com.gaeul.allin`
- 当前版本：1.2.21（versionCode 27）

此包名与旧版 `com.magpie.pocket` 不同，按新应用安装，可与旧版并存。
旧版账号、配置及系统授权不会自动迁移；两者的网关不要同时监听相同端口。

首页顶部提供作者 Gaeul（@xunianooo）主页与项目直达链接，图标使用本地内嵌 GitHub 矢量标识。项目仍为私密时，需要使用有访问权限的 GitHub 账号查看。

## 致谢

本项目的多数供应商适配（zcode、workbuddy、raccoon、catpaw、qoder、cline、
codex、antigravity、copilot、minimax 等）源自
[yetone/magpie](https://github.com/yetone/magpie) 及其插件生态的设计与实现，
部分协议细节直接移植自 Magpie 本体与社区插件。
没有 Magpie 就没有这个项目，在此向 [@yetone](https://github.com/yetone)
和插件社区表示感谢。

## 构建

Gradle 构建需要 JDK 17 和稳定版 Android SDK Platform 37.0；目标 API 为 34，最低 API 为 24。详见 [SDK 与前台服务说明](docs/sdk.md)。

```bash
# Debug 包（无需签名配置）
./gradlew assembleDebug

# 离线单元测试（纯 JVM，不需要模拟器）
./gradlew offlineTests

# 正式签名包：密钥不进仓库，走环境变量
MAGPIE_KEYSTORE=/path/to/magpie.keystore \
MAGPIE_KEYSTORE_PASS=口令 \
MAGPIE_KEY_ALIAS=gaeul \
./gradlew assembleRelease
```

产物在 `app/build/outputs/apk/`。

## 手机端自编译（Termux / Alpine chroot）

手机端可在配置好 JDK 17、Android SDK 和签名凭据的 Linux 环境中使用同一套 Gradle 工程构建；签名密钥与口令仅保存在本机，不进入仓库。

## 协议自动转换

网关同时接受 Chat Completions（`/v1/chat/completions`）、Responses（`/v1/responses`）和 Anthropic Messages（`/v1/messages`）。按供应商协议自动转换请求与 JSON/SSE 响应，客户端不需要跟随供应商切换接口；支持文本、图片、函数工具调用和结果。Responses 同协议请求在隐私处理、中间件与上下文保护允许时保留原生结构。状态式 `previous_response_id` 等未实现能力返回可读错误。实现参考 Magpie，详见 [协议说明](docs/responses-bridge.md)。

Responses 兼容完整 Agent 请求中的 `user`、`stream_options`、`text.verbosity`、常见可空默认值及普通 reasoning／工具历史。未知顶层扩展字段在副本上忽略，类型错误仍返回 400；非空服务器端会话、模板和异步执行不会静默丢弃。跨协议展示选项降级通过兼容性响应头提示，详见 [字段兼容表](docs/responses-agent-compatibility.md)。

## 1.2.21 自动客户端兼容与管理边界

- WorkBuddy 自动处理 DSH 通道拒绝及流式空字段，用户无需配置兼容开关。
- ZCode 赠送通道按账号排队，针对明确并发拒绝做有界短重试，并记录实际发送次数和等待。
- 隔离手机管理身份与推理 Key，完善配置脱敏保真回写、WebView 来源、CORS/Host 和 Responses 原生入口保护。
- 电脑 DeepSeek Harness 的 WorkBuddy 与 ZCode 路径均已通过用户真实验收。

本版为快速存底，复用此前定向回归与实测结果；完整范围及验证边界见 [1.2.21 更新说明](docs/release-1.2.21.md)。

## 1.2.18 供应商可靠性、端点探测与新图标

- 补齐 Qoder 工具与实时流式链路，完善多家供应商账号连续性、模型目录提交和故障诊断。
- 20 家供应商新增登录前匿名端点探测，检查固定官方地址，不使用凭据、不提交推理或改写路由。
- 修复 ZCode gift 空预览误拦已有额度，以及 WorkBuddy 同步失败覆盖模型目录的问题。
- 修复供应商二级页系统返回直接退出，更新桌面普通、圆形、自适应及面板图标。

完整更新及验收边界见 [1.2.18 更新说明](docs/release-1.2.18.md)。

## 1.2.17 Trae CN 与供应商兼容性

- 新增 Trae CN 个人账号授权、账号池、动态模型同步和签到，支持工具往返与流式响应；完善登录恢复、辅助事件、历史字段和思考档位兼容。
- WorkBuddy 工具轮次复用请求 ID，并保持客户端的流式/非流式意图；无可靠会话信息时保守使用独立 ID。
- Qoder 排队业务码与鉴权错误分开处理，排队及执行结果未知时不自动重放；输出上限不足 64 时发送前明确拒绝。
- ZCode 增加 ToolSearch 请求别名与响应还原，完善 gift 用量、拒绝冷却及账号缓存隔离；Cline 图片路由保留用户选项。
- 内置兼容规则更新至 1.0.2，较旧有效签名缓存保留，但不覆盖较新内置规则。线上规则源独立维护。

本批电脑功能验收通过；本地集中回归覆盖 86 个离线套件、17 个独立 HTTP 任务、17 个前端脚本及 Android 原生隔离验证。真实厂商计费策略、所有模型和旧版 Android 实机不据此宣称全面验证。Trae 企业/Global/Max 等未实现能力保持明确边界。

## 1.2.14 供应商管理、网络配置与连接可靠性

- 已接入供应商紧凑管理、搜索、启停、详情及局部 Key 保存，支持草稿保留与冲突保护。
- 显式出站默认网络 / 直连 / HTTP 代理，失败不绕行，独立保存并保留请求网络快照。
- 路由组可选“近期用量优先”：按供应商/模型共享近期负载，保留流式终态与最终交付双条件。
- 请求取消、单调截止时间、稳定凭据身份和错误分类；出站间隔、滚动 RPM、并发与有界队列可独立配置。
- 小浣熊 `user_model_rate_limited` 单独处理，避免同账号短间隔重放；限额默认不预设厂商阈值。
- R1 覆盖普通商业 Chat/Anthropic/Responses 和小浣熊推理；其它特殊通道及登录/刷新不宣称全面覆盖。
- 本版不包含后续耐受重试、SSE 心跳或会话亲和。

验收说明：[`docs/connection-resilience-r0-r1-acceptance.md`](docs/connection-resilience-r0-r1-acceptance.md)、[`docs/recent-usage-routing-acceptance.md`](docs/recent-usage-routing-acceptance.md)、[`docs/network-configuration-acceptance.md`](docs/network-configuration-acceptance.md)、[`docs/provider-management-acceptance.md`](docs/provider-management-acceptance.md)。

## 1.2.13 ZCode 用量与地址修复

修复 ZCode gift 通道实际调用未入账，以及治理页汇总和最近请求停留时不自动刷新的问题。统计页可见时约每 3 秒刷新，保留时间范围、分组和编辑内容；缺失或部分用量不会补成已知零值。

首页明确区分本机应用推荐地址与局域网其他设备地址，ETA 与本机地址使用同一实际端口；网络地址失效时清除旧值。ETA 的显示与复制同步当前密钥。复测范围和历史漏记限制见 [修复验收说明](docs/zcode-usage-address-fix.md)。

## 1.2.12 可靠性更新

原生 Anthropic Messages 流直接保留 thinking、signature 和 redacted 块；已签名历史遇到不兼容的脱敏、裁剪或协议转换时明确拒绝。脱敏占位符使用安装范围的私有 HMAC 密钥，映射按请求隔离，并检查碰撞与异常。

普通商业 API Key 支持目标内接力、分范围冷却和 Retry-After；流已经转发或执行状态未知时不扩大重放。用量区分逻辑请求、实际上游尝试和已知／部分已知／估算／未知来源，保留旧账本口径。智谱错误信封仅在限定来源和接口形态下适配。

复验命令和兼容边界见 [本批验收说明](docs/reliability-batch-acceptance.md)；订阅多账号调度与后续可选路线图不在本版范围。

## 接入客户端

- 接口地址：`http://<手机IP>:3425`（本机/模拟器内为 `http://127.0.0.1:3425`）
- 鉴权：`x-api-key` 或 `Authorization: Bearer`（127.0.0.1 来源免口令，供面板与同机 agent 使用）
- 模型名格式：`provider/model`（完整列表见 `GET /v1/models`）

路由页的「允许裸模型兜底」默认关闭，旧配置缺少该项也视为关闭。裸模型名如果未匹配已列出的模型，开启后才尝试第一家配置了密钥的供应商；`provider/model` 和 `group/组id` 不受此开关影响。

首次设置第一步点击「应用密钥，下一步」后，密钥立即保存并生效；返回权限页或重建面板也会恢复已保存密钥。只有第三步完成后才标记向导完成。通用配置保存不会覆盖密钥或向导状态，改密钥请使用专用入口。

首次设置向导完成前，网关只接受本机回环地址请求；局域网访问需在手机本机完成向导后再按网关口令鉴权。已有 `setup.done` 完成标志的用户保持原来的局域网鉴权行为。

模型白名单按供应商和路由组默认收起，标题显示已选数量。已有账号或 API Key 的供应商点击标题展开；未配置凭据时显示导入提示和「去配置」，不铺开模型。当前页面刷新保留展开状态，收起或暂时移除凭据不清除已选模型。

## CI

`.github/workflows/ci.yml`：push 时自动跑离线测试并产出 Debug APK artifact。
正式签名 job 需要配置 Secrets（`MAGPIE_KEYSTORE_BASE64` / `MAGPIE_KEYSTORE_PASS`）
后取消注释启用。

GitHub 标识来自 Primer Octicons，许可见 `app/src/main/assets/licenses/primer-octicons-MIT.txt`。
