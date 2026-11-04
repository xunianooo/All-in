# 流式批量读取修复（1.2.7v2 / versionCode 12）

`GatewayServer.streamForwardResponse` 的匿名 InputStream 原来只实现单字节 read。NanoHTTPD 2.3.1 以 16,384 字节批量读取响应，Java 默认 bulk read 会反复调用单字节 read，跨未来 SSE 事件等待填满或 EOF。Chat 因此延迟整批送达；以该流为输入的 Responses 转换也受影响。单加 flush 无法让尚未返回的 read 提前完成。

现在显式实现 read(byte[], off, len)：通过原单字节处理链准备片段，再仅返回当前片段内可用字节，不读未来事件填满调用方缓冲。保留原脱敏、中间件、工具参数、用量和终态处理；补标准参数检查并显式使用 UTF-8。close 仍关闭上游并在 finally 中 disconnect。

## 可重现的真实 HTTP 回归

运行 `./gradlew streamingHttpTests`。环回 HTTP 定时上游 → 真实 NanoHTTPD 网关 → Socket 客户端；客户端解析完整 SSE 事件计时，独立解码 HTTP chunk，不使用 TCP 包数作为判据。

上游 20 个内容事件，间隔 50ms。每条路径断言内容无丢重、首内容距上游首次写出小于 700ms 且早于末事件、至少 3 个内容事件在上游末事件前到达、客户端输出跨度至少 400ms、至少 4 个内容间隔不小于 20ms，且 reasoning 和成功终态保留。测试在未修生产实现上先失败于 `Chat native from Chat first content delayed`。

覆盖路径：

| 上游 | 客户端 | 内容节奏 | 工具验证 |
| --- | --- | --- | --- |
| Chat | Chat | 定时持续输出 | 首段参数门控上游续发 |
| Chat | Messages | 定时持续输出 | 最终身份、参数、终态完整 |
| Chat | Responses | 定时持续输出 | 首段参数门控上游续发 |
| Responses | Responses 原生 | 定时持续输出 | 首段参数门控上游续发 |
| Responses | Chat | 定时持续输出 | 首段参数门控上游续发 |

Messages 转换的工具块原来就为保留分片工具身份和顺序而延迟到结束；本补丁不改变该策略，不声称其工具参数提前输出。原生 Messages 上游的网络到达节奏不在新增 HTTP 覆盖内。原生 Responses 单独验证，不能由 Chat 转换故障推断它原先也攒块。

门控测试中，上游发完第一段工具参数后等待客户端实际收到才发其余部分，超时即失败，防止完整收尾后的集中输出假阳性。另验证 Chat、转换 Responses 和原生 Responses 的上游截断会输出错误终态。

`GatewayStreamReadOfflineTest` 补零长度/非法参数、输入端 UTF-8 多字节跨读取边界、1–3 字节输出读取、content/reasoning/工具参数交错分片解除占位符、think-tags/word guard、用量只提交一次、读取 IOException 错误终态及关闭/异常关闭必断开上游的契约。保留原 20 套离线测试，新增为第 21 套；首装 HTTP、三项前端回归和首页 Gaeul/GitHub 卡片不变。

## 上游参考与验收范围

只读参考 Magpie `0ee424e3ee61ae545d93160084a8d930b88a8193` 的 sse.go、native.go、chat_from_anthropic_test.go、call_args_stream_test.go：借鉴当前数据即时转发、内容节奏和参数门控测试，修复仍针对 Java bulk read 根因。

发布门禁：clean offlineTests streamingHttpTests setupKeyHttpTests assembleDebug assembleRelease lintRelease，三项 Node 回归，Windows 和 Ubuntu CI 均通过后发布 Gaeul 正式签名 APK 与 SHA-256。同一附件供电脑 Agent 在 ETA/Raccoon 和原 800-token 请求上复验；环回 mock 通过不代表这些真实客户端已完成验收。
