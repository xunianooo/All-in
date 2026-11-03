# All In

把一台安卓手机变成多供应商 LLM 网关：内置 NanoHTTPD 服务（默认 `0.0.0.0:3425`），
对外提供 OpenAI（`/v1/chat/completions`）与 Anthropic（`/v1/messages`）兼容接口，
聚合各家供应商账号池，配套 WebView 管理面板。

## 应用信息

- 名称：All In
- 包名：`com.gaeul.allin`
- 当前版本：1.1.1（versionCode 3）

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

```bash
# Debug 包（无需签名配置）
./gradlew assembleDebug

# 离线单元测试（9 个套件，纯 JVM，不需要模拟器）
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

## 接入客户端

- 接口地址：`http://<手机IP>:3425`（本机/模拟器内为 `http://127.0.0.1:3425`）
- 鉴权：`x-api-key` 或 `Authorization: Bearer`（127.0.0.1 来源免口令，供面板与同机 agent 使用）
- 模型名格式：`provider/model`（完整列表见 `GET /v1/models`）

## CI

`.github/workflows/ci.yml`：push 时自动跑离线测试并产出 Debug APK artifact。
正式签名 job 需要配置 Secrets（`MAGPIE_KEYSTORE_BASE64` / `MAGPIE_KEYSTORE_PASS`）
后取消注释启用。
