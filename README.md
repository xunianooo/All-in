# All In

**把 Android 手机变成随身 AI 网关。**

All In 将不同供应商的账号、API 密钥和模型集中到一个本地服务中，让手机、电脑和其他兼容客户端通过统一接口访问。你可以在手机上管理供应商、配置路由和查看用量，也可以让客户端继续使用熟悉的 OpenAI 或 Anthropic 接口。

项目由 [Gaeul](https://github.com/xunianooo) 开发，前身为 Magpie Pocket。All In 参考并移植了 Magpie 和社区插件的部分设计与实现；感谢原项目及各位贡献者，详见下方[致谢](#致谢)。

> 当前仓库版本为 **1.2.23-test**（versionCode 29），是供真机验证的测试版本。供应商服务、客户端协议和系统后台策略都可能变化，现有测试不能代表所有账号、模型和设备均已验证。正式发布版本请以仓库的 Releases 页面为准。

## All In 能做什么

- **统一接入**：提供 OpenAI Chat Completions、Responses 和 Anthropic Messages 兼容入口，按供应商和接口能力处理请求与响应。
- **供应商与账号管理**：通过手机面板登录支持的账号、配置 API Key、查看模型目录和调整供应商状态。
- **路由与模型管理**：配置路由组、候选目标和模型白名单；可按需要启用近期用量优先等策略。
- **用量与诊断**：查看请求记录、用量与连接状态，并区分已知、部分已知、估算和未知的用量信息。
- **请求保护**：提供上下文预算、隐私脱敏、出站网络配置、限流和取消等能力。对于执行结果未知的请求，部分路径会保守停止，避免盲目重放。
- **维护工具**：提供配置备份与恢复、供应商端点探测和签名兼容规则更新。
- **手机后台运行**：通过前台服务和保活机制维持网关；实际运行效果仍取决于 Android 版本及设备厂商的后台限制。

不同供应商和模型对图片、工具调用、流式输出及特定协议字段的支持不同；统一入口不代表所有功能都可以无损互转。

## 支持的供应商

仓库默认配置中已接入或提供适配的供应商包括：

- **账号与订阅类**：ZCode、WorkBuddy、Trae CN、CatPaw、小浣熊 Raccoon、Qoder CN、Cline、Codex、Antigravity、GitHub Copilot、MiniMax Code、DeepSeek 网页版。
- **API Key 或其他配置方式**：WorkBuddy AI、Loomy、DeepSeek、SiliconFlow、OpenAI、Anthropic、OpenRouter、腾讯混元。

登录方式、模型、可用功能和额度由对应供应商决定。部分适配依赖网页或社区协议，可能随上游更新而失效；Loomy 等供应商可能需要手动配置已有会话或凭据。请以应用内提示和供应商实际状态为准。

默认配置还包含 AgentMore、文心网页、Meoo、Runable、当贝和 Tabbit 的占位入口。这些入口不代表已经完成推理协议接入。

## 开始使用

### 1. 安装并完成设置

1. 从项目 Releases 获取合适的 APK，或按下方说明自行构建并签名。
2. 安装并打开 All In，按照向导检查权限、网关口令和服务状态。
3. 在「供应商」中添加 API Key 或使用对应的登录方式，再同步或选择需要的模型。
4. 按需要在「路由」和「治理」中配置路由组、模型暴露范围及其他策略。

当前应用包名为 `com.gaeul.allin`，与旧版 `com.magpie.pocket` 是不同应用。旧版账号、配置和系统授权不会自动迁移；两者同时运行时应避免监听同一个端口。

### 2. 连接客户端

默认网关端口为 `3425`。实际地址、口令和监听状态可在应用面板查看：

| 客户端位置 | 地址示例 |
| --- | --- |
| 与 All In 位于同一手机 | `http://127.0.0.1:3425/v1` |
| 同一局域网内的电脑或其他设备 | `http://<手机局域网 IP>:3425/v1` |

OpenAI 兼容客户端通常填写以上 `/v1` 地址；Anthropic 客户端请按客户端要求填写服务根地址并访问 `/v1/messages`。在客户端填写面板中的**网关口令**，而不是直接填写供应商账号的密钥。

可用模型可通过面板或 `GET /v1/models` 查看。通常使用 `provider/model` 格式；路由组使用 `group/<组 ID>`。裸模型名兜底默认关闭，可在路由设置中按需启用。

例如，在本机环境变量中设置网关口令后查询模型：

```bash
curl http://127.0.0.1:3425/v1/models \
  -H "Authorization: Bearer $ALLIN_API_KEY"
```

请求示例：

```bash
curl http://127.0.0.1:3425/v1/chat/completions \
  -H "Authorization: Bearer $ALLIN_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"model":"provider/model","messages":[{"role":"user","content":"你好"}]}'
```

将示例模型名替换为应用中实际可用的模型。客户端也可使用 `x-api-key` 请求头；请避免把真实口令写进脚本、截图或公开日志。

### 3. 保持网关运行

网关运行时需要 Android 前台服务。若锁屏或切换应用后连接中断，请检查通知权限、电池优化和厂商后台限制。不同设备对后台运行的管理方式不同，应用不能保证永不被系统终止。

## 接口与兼容性

| 接口 | 用途 |
| --- | --- |
| `GET /v1/models` | 获取可用模型目录 |
| `POST /v1/chat/completions` | OpenAI Chat Completions 兼容入口 |
| `POST /v1/responses` | OpenAI Responses 兼容入口 |
| `POST /v1/messages` | Anthropic Messages 兼容入口 |
| `POST /v1/images/generations` | 图片生成入口，具体支持取决于供应商 |

网关根据供应商协议处理 JSON 和 SSE 流，也支持部分工具调用与图片场景。原生协议、跨协议转换、模型能力及客户端实现之间存在差异；不支持或无法安全转换的字段可能返回错误，而不是静默丢弃。更多信息见 [Responses 协议说明](docs/responses-bridge.md)和 [Agent 字段兼容说明](docs/responses-agent-compatibility.md)。

## 安全与隐私

- **设置自己的网关口令。** 新安装会生成随机口令；历史安装若仍使用公开默认口令，非本机推理请求会被拒绝。请在手机面板生成、复制并应用新口令，再更新电脑端配置。
- **管理凭据与推理口令分离。** 应用管理接口使用独立管理凭据，客户端连接通常只需网关推理口令。
- **局域网连接使用 HTTP。** 请求内容和口令在网络中不是端到端加密的；只在可信网络使用，不要直接将网关暴露到公网。
- **保护账号和备份。** 供应商密钥、登录状态、备份文件、账本和签名私钥都应妥善保管，不要上传到公开仓库或分享给不可信的人。
- **脱敏不是万能的。** 隐私处理依赖规则和请求形态，使用前请自行检查发送内容；兼容规则更新会验证签名，但不能代替对上游服务的判断。
- **失败恢复有边界。** 部分供应商的一次性凭据在网络异常后可能处于结果未知状态，为避免重复使用会被保护性阻断，可能需要重新授权。

使用第三方供应商时，请遵守其服务条款并留意额度及费用。All In 不提供供应商账号或免费额度，也不能保证上游服务持续可用。

## 构建与测试

### 环境

- JDK 17
- Android SDK Platform 37.0
- Gradle Wrapper 9.3.1、Android Gradle Plugin 9.1.1
- Android 最低版本：API 24；target SDK：34

具体环境与前台服务说明见 [SDK 文档](docs/sdk.md)。配置好 Android SDK 后，在仓库根目录执行：

```bash
# Debug APK
./gradlew assembleDebug

# JVM 离线测试
./gradlew offlineTests

# 本地 HTTP 集成测试
./gradlew setupKeyHttpTests streamingHttpTests outboundRoutingHttpTests
```

Windows 使用 `gradlew.bat`。APK 产物位于 `app/build/outputs/apk/`。面板脚本使用 Node.js，具体命令可参考 [CI 工作流](.github/workflows/ci.yml)。

Release 构建需要自行准备签名文件，并通过 `MAGPIE_KEYSTORE`、`MAGPIE_KEYSTORE_PASS`、`MAGPIE_KEY_ALIAS` 配置；如密钥口令与仓库口令不同，可设置 `MAGPIE_KEY_PASS`。这些 `MAGPIE_` 前缀是历史兼容命名，签名密钥和口令不应提交到仓库。使用其他签名证书构建的 APK 无法直接覆盖安装原证书签名的版本。

手机端也可在具备 JDK、Android SDK 和 Gradle 所需环境的 Linux 容器中构建。测试与 CI 的实际结果请查看对应提交的运行记录；1.2.23-test 的 Windows CI 曾在测试管理凭据初始化阶段失败，不能据本机测试结果宣称 Windows 已通过。

## 项目结构

```text
app/src/main/java/       Android 应用、网关、协议与供应商适配
app/src/main/assets/     管理面板、默认配置、资源与内置规则
app/src/test/            JVM 离线测试与 HTTP 集成测试
app/src/main/assets/licenses/  随应用提供的第三方许可
tests/                   面板 JavaScript 测试
docs/                    设计、兼容性与历史验收说明
```

## 致谢

All In 建立在开源项目和社区分享的工作之上，感谢每一位作者和维护者：

- **[Magpie](https://github.com/yetone/magpie)**：感谢 [@yetone](https://github.com/yetone) 和 Magpie 贡献者提供的开源网关、协议处理及供应商适配思路。All In 的部分实现参考或移植自 Magpie。
- **[magpie-community/plugins](https://github.com/magpie-community/plugins)**：感谢维护 OpenCode / Magpie 供应商插件的社区与各插件作者。项目在适配不同供应商的登录、请求和响应时，参考了社区发布的 npm 包及其源码；各插件有各自的作者和许可，请以对应项目的声明为准。
- **DeepSeek 网页适配贡献者**：感谢相关社区作者与贡献者分享实现；随应用附带的许可文件见 [`magpie-deepseek-web-auth-MIT.txt`](app/src/main/assets/licenses/magpie-deepseek-web-auth-MIT.txt)。
- **[NanoHTTPD](https://github.com/NanoHttpd/nanohttpd)**、**[JSON-java](https://github.com/stleary/JSON-java)**、**[Primer Octicons](https://github.com/primer/octicons)**，以及 Android、Gradle 和所有相关开源依赖的维护者。

感谢所有提供反馈、测试问题和改进建议的朋友。

## 许可证

本项目采用 [MIT License](LICENSE)。项目中引用、移植或分发的第三方代码和资源仍遵循其各自的版权及许可声明；随应用附带的许可文件位于 [`app/src/main/assets/licenses/`](app/src/main/assets/licenses/)。
