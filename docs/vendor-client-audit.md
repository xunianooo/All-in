# Copilot / MiniMax 客户端指纹核审

核审日期：2026-11-06。未使用生产凭据、未进行真实推理；以下不是封禁或限频方面的保证。

## Copilot

本地插件包名为 `@magpie-community/opencode-copilot-auth`。npm 公共 registry 的 latest 查询返回 HTTP 404，因此不能假称从 npm 获得最新包。随后查阅项目维护的 [dist 索引](https://raw.githubusercontent.com/magpie-community/plugins/dist/index.json)：latest 为 0.1.2，索引时间 2026-11-03T05:57:52.444Z；从索引指定的 [tarball](https://raw.githubusercontent.com/magpie-community/plugins/dist/tgz/magpie-community-opencode-copilot-auth-0.1.2.tgz) 下载并验证 SHA-512 与索引一致：`sha512-fXuKvcdMe6eL27cd8vsH5QHzjf8e/Sadj33ER+LPyPA5DvqcJGsWMN/s4tbsFpbb/6n4a3d4R+UgGF1AZlp3Ag==`。索引、包及解包内容保存在 `/workspace/allin-package-maintenance/vendor-audit/copilot/`。

该包 `endpoints.mjs` 明确同时使用 `GitHubCopilotChat/0.68.0`、`vscode/1.140.0`、`copilot-chat/0.68.0`，与本项目一致。它是插件自述的客户端组合，并不独立证明这些都是对应日期实际发行的官方版本。报告建议的 1.95.3 没有提供与当前插件版本匹配的上游依据，不予降级。包内所有 mjs 文本中未发现 `Vscode-Sessionid`；仅能说明该插件包未使用，不能证明 GitHub 官方永不要求。现阶段不添加随机会话头，也不声称会改善缓存或频控。

`CopilotAuth.models()` 调用 `clientHeaders()`；token exchange 的三个版本头直接读取同一组常量。其他调用者需在各自范围内检查；本次没有修改 GatewayServer。

## MiniMax

`chatHeaders()` 原本通过 `TimeZone.getDefault().getOffset(now)/1000` 计算，已经涵盖本地 UTC 偏移和夏令时，报告的固定 0 判断不成立。本次只抽出同语义的 timestamp helper 以进行确定性测试，并未改变请求头行为。

离线 `ClientFingerprintOfflineTest` 覆盖纽约冬夏令时 -18000/-14400、印度半小时时区 +19800、默认时区实际 header 和 Copilot 三项头与常量一致。使用 `/workspace/tools/json.jar`、`/workspace/tools/android.jar`、已有 release classes 独立 javac 编译后运行通过；没有执行 Gradle 或真实网络推理。实际供应商兼容性和服务端策略仍需独立验证。
