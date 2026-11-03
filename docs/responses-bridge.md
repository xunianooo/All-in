# 协议自动转换

网关接受 `POST /v1/chat/completions`、`POST /v1/responses` 和
`POST /v1/messages`，按所选供应商的 Chat、Responses、Anthropic 或 Codex
适配器发送请求，再返回客户端所用协议的 JSON 或 SSE 响应。所有入口复用
鉴权、隐私脱敏、中间件、上下文保护、路由及降级，无内部回环 HTTP 请求。

## 转换范围

支持文本、图片 URL/data URL、instructions/system、函数工具定义、工具调用
及字符串结果、tool_choice、输出上限、采样、reasoning effort 和文本/JSON
输出格式。响应支持文本、refusal、reasoning summary、函数调用、用量、
completed/incomplete 状态及流式生命周期。工具调用 ID 和分片参数会关联；
截断或失败的上游流返回失败事件，不伪装为成功完成。Chat 的多 choice 暂不支持。

Responses 输入会投影为共同消息格式，以执行中间件和上下文保护。向 Responses
协议供应商或 Codex 转发时，只有共同消息语义未改变、最终原生请求也独立通过
上下文保护，才保留原始 Responses 结构；路由后的模型名和输出上限同步写回。
因此同协议路径可保留 instructions、include、reasoning.summary、加密 reasoning
等已验证的原生内容。原生 JSON/SSE 仍执行隐私还原及用量记录。

中间件或上下文保护改变消息后，可表达的请求使用转换后的内容；含无法安全
表达的原生字段时返回可读 400，不会恢复被裁剪或改写的原文。跨协议无法完整
保留供应商专属 reasoning 签名、引用和其他没有对应表示的内容，不能视为
Magpie 全部功能的等价实现。

暂不提供有状态的 `previous_response_id`、conversation 或存储结果读取接口。
`store: true` 仅可传给配置为 Responses 协议且支持该字段的上游；Codex 路径
明确拒绝。服务器托管工具、file ID 和不支持的输入项/字段返回可读错误，
不会静默丢弃。真实供应商是否支持特定模型和能力仍取决于其接口。

## 上游来源

设计参照 yetone 及贡献者维护的 Magpie，核对版本：
`b045362a92e7d3b0b48c2b88e254ee91dfed9263`。
主要参考 `internal/gateway/ir.go`、`responses.go`、`chat.go` 和
`docs/subsystems/gateway-routing.md`：解析共同消息、构造上游协议、渲染客户端
协议，并在安全条件下保留同协议原生结构。本项目用 Java 在现有 Android
管线中实现，没有引入 Go 运行时。感谢 Magpie 作者和社区；MIT 许可保存在
`app/src/main/assets/licenses/magpie-MIT.txt`，项目 README 和 LICENSE 中的
致谢与版权声明继续保留。

## 验证边界

离线 mock 覆盖三类入口的 JSON/SSE 转换、Responses 原生转发、Codex
转换、Copilot 能力回退、鉴权、模型映射、guard 封顶、中间件降级和拒绝、
隐私占位符跨片还原、工具/refusal/用量及截断流。mock 不能替代真实供应商
认证、Android 生命周期及电脑端 API 34 模拟器的交叉验收。
