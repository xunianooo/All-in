# Responses Agent 请求兼容（v1.2.7 / versionCode 11）

参照 Magpie `c7bbd3faf6de7c59ff65848450b0640a7ee2ecb1` 的 `internal/gateway/responses.go`、`ir.go`、`chat.go`：先解析共同消息结构，容忍附加字段，再按目标协议构造请求；同协议在隐私、中间件和guard允许时保留原生结构。这是针对现有Android Java管线的实现，不宣称与上游全部能力等价。

| 字段或内容 | Chat 上游 | 原生 Responses 上游 |
| --- | --- | --- |
| user / safety_identifier | 字符串保留 | 保留 |
| stream_options | include_usage映射，忽略include_obfuscation | 保留include_obfuscation，不发送Chat专属include_usage |
| text.verbosity | 接受low/medium/high，展示选项降级 | 保留；不强制同时提供format |
| text.format | 转为response_format，格式语义保留 | 保留 |
| reasoning.effort | 映射reasoning_effort | 保留 |
| reasoning.summary、已知include展示选项 | 可降级，返回兼容性警告 | 保留 |
| 普通reasoning历史 | assistant.reasoning_content，不伪造用户正文 | 原始reasoning项保留 |
| 加密reasoning / refusal历史 | 明确400，提示使用原生Responses路由 | 安全条件下保留 |
| functions、calls、outputs、图片 | 转换保留；连续工具轮保持结果配对 | 保留 |
| 可选字段显式null | 视为未设置 | 规范化为未设置 |
| previous_response_id空值、conversation/prompt空值、background=false | 接受 | 接受 |
| 非空previous_response_id、conversation、prompt，background=true | 明确400：提供完整input或同步调用 | 本网关尚不实现服务器端续接/异步，仍明确400 |
| 正数max_tool_calls、store=true | 明确400，不能谎称实现限制或存储 | 交原生上游执行；Codex仍不支持store=true |
| top_logprobs、prompt_cache_retention、client_metadata、access_programs | 不能安全表示时明确400 | 保留 |
| 非空context_management | 明确400，尚不实现自动压缩语义 | 同左 |
| 未识别顶层扩展（如foo_bar）及未知流选项 | 在副本上忽略，不转发上游 | 同左，不绕过规范化 |
| 错误类型/范围 | 400 | 400 |

省略未知扩展时返回 `X-AllIn-Compatibility-Warning: unknown-fields-ignored`；跨协议展示配置可能降级时附 `responses-display-fields-may-be-omitted`。警告不含请求值、账号或密钥。未知字段容忍不代表支持所有未来字段，已识别的会话、模板、限制等语义不得随意丢弃。

## 回归与验证

新增 ResponsesAgentRequestOfflineTest，使offlineTests由19增至20套。真实Socket mock覆盖完整Agent组合请求的JSON和SSE、user/metadata/structured format/图片/工具/effort/usage、未知扩展忽略且原对象不变、原生字段保留、非法字段400、双协议等价转换、guard封顶、word guard、普通reasoning与并行工具调用后结果配对。mock会拒绝不属于目标协议的流参数，避免仅测试“上游接收任何字段”。账本写临时目录，不落源仓。

旧测试中与明确兼容行为相冲突的断言更新：text:null由错误改为缺省，改用text:42继续检查类型错误；temperature:null投影由保留null改为字段缺省；跨协议加密reasoning拒绝仍保留，错误原因从include更新为加密reasoning。其余测试继续执行，v1.2.6空字符串工具调用回归保留。

本地clean offlineTests setupKeyHttpTests assembleDebug assembleRelease lintRelease通过；20套JVM、首装HTTP、3项Node回归均通过。Lint零Error/Fatal、94Warning。Android16覆盖安装配置/凭据指纹一致；带user/stream_options/text.verbosity/reasoning.summary/metadata/include/null状态值及foo_bar的真实WorkBuddy Responses JSON/SSE均200；同类完整请求实际返回lookup工具调用，携带普通reasoning和function_call_output回传后的下一轮也返回200/completed。

完整ETA客户端、Raccoon原请求、API34仍需电脑端同包复验；构造的完整请求及mock不冒充实际ETA请求抓包。加密reasoning跨非原生路由等明确限制仍存在，错误将给出指引。
