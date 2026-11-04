# All In

把一台安卓手机变成多供应商 LLM 网关：内置 NanoHTTPD 服务（默认 `0.0.0.0:3425`），
对外提供 OpenAI（`/v1/chat/completions`）与 Anthropic（`/v1/messages`）兼容接口，
聚合各家供应商账号池，配套 WebView 管理面板。

## 应用信息

- 名称：All In
- 包名：`com.gaeul.allin`
- 当前版本：1.2.3（versionCode 7）

此包名与旧版 `com.magpie.pocket` 不同，按新应用安装，可与旧版并存。
旧版账号、配置及系统授权不会自动迁移；两者的网关不要同时监听相同端口。

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

保留历史能力：`./app/build.sh` 仍可独立出包（依赖 `/workspace/tools` 下的
工具 jar 与 keystore），与本 Gradle 构建双轨并存，产物同源。

## 协议自动转换

网关同时接受 Chat Completions（`/v1/chat/completions`）、Responses（`/v1/responses`）和 Anthropic Messages（`/v1/messages`）。按供应商协议自动转换请求与 JSON/SSE 响应，客户端不需要跟随供应商切换接口；支持文本、图片、函数工具调用和结果。Responses 同协议请求在隐私处理、中间件与上下文保护允许时保留原生结构。状态式 `previous_response_id` 等未实现能力返回可读错误。实现参考 Magpie，详见 [协议说明](docs/responses-bridge.md)。

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
