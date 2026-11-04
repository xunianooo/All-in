# v1.2.1 修复后审计

审计基线：v1.2.0 / main 81ee19b；对照其相对 v1.1.1 的改动，检查新增协议桥、网关转发、路由开关、首次设置及 SDK 构建。修复按实际复现和回归证据合并，不宣称没有任何剩余缺陷。

## 确认并修复

| 范围 | 复现问题 | 修复与验证 |
| --- | --- | --- |
| 请求校验 | 非法 Chat messages 引发异常类型不一致；tools/tool_calls 错误类型可能被忽略 | 统一转换校验，返回可读客户端错误 |
| Responses 原生投影 | store 字符串 true 绕过布尔校验；超大 max_output_tokens 可越过 int 表示范围 | 投影前校验 store；有限、正整数且 <= Integer.MAX_VALUE；补参数类型回归 |
| 工具流 | 参数先于 id/name 或 name 分片时元数据丢失 | 缓冲未就绪工具，关联 output_index/call_id，回归双工具交错 |
| 最终输出 | 仅在 arguments.done 或最终 response 给出的内容未补入 Chat 流 | 按输出和内容索引追踪已发前缀，补齐后缀并避免重复；拒绝无有效 call_id/name 的最终工具 |
| HTTP 错误 | Responses 上游422被映射为500；错误详情缺失影响上下文错误/重试判断 | 保留HTTP状态及有界详情、遮盖目标key，统一 error.type=gateway_error，保留code |
| 流资源与账本 | 中途取消未主动断连或未记录；移交流后请求线程仍留用量上下文 | 上游关闭和账本各执行一次，取消记499；正常/异常终止释放连接；清理请求线程ThreadLocal |
| 原生 SSE 边界 | 尾部可能匹配占位符的普通花括号被误判；超长事件/错误体额外读取 | 尾部flush保留文本、索引和递增事件序号；单事件1Mi字符限制；错误体64KiB停止读取 |
| 路由 UI | 白名单读取误重置裸模型开关；异步旧读取覆盖用户操作 | 隔离状态来源并用revision/进行中写入计数屏蔽过期响应 |
| 配置并发/保存 | 两个路由开关并发更新丢字段；路由/向导保存失败仍成功 | 同步合并保存，失败返回500并回滚对应内存字段 |
| SDK静态检查 | 旧代码4处数值常量触发Lint WrongConstant | 改对应命名常量，数值和运行行为不变；Lint零Error/Fatal |

EOF策略审查中发现强制要求Chat流[DONE]会破坏原有明确finish_reason后EOF的兼容性，该尝试未保留；最终仍接受有效finish_reason后EOF，无终态EOF才失败。

## 验证

- clean offlineTests assembleDebug assembleRelease lintRelease通过。保留原13套，新增ResponsesBridgeAuditTest、GatewayAuditOfflineTest、RoutingAuditOfflineTest，共16套全通过。
- 原有9套及v1.2.0共13个既有测试文件保持逐字节一致；MIT/LICENSE、git策略和原有CI步骤保留。
- Lint报告：0 Error/Fatal、94 Warning。警告包括旧目标API、locale、反射、WebView、资源和文本等，未作为此次范围外整体整改。
- node tests/dashboard-routing-audit.cjs通过；验证过期路由读取和白名单状态隔离。
- Mock含真实本地ServerSocket、计数输入流/连接和账本文件：取消/重复close、终态未由消费者close、截断、错误状态、超长事件、错误体读取边界、API key遮盖。
- Gaeul证书指纹34618e7f2f748fb81c4a524f445233a35344c29e39f289f4f4e8e9e9e8442443，APK v2/v3验签通过。包名com.gaeul.allin，version1.2.1/code5，min24/target34/compile37。
- Android16真机覆盖安装，配置含凭据整体指纹一致；dataSync前台服务和监听正常，未发现本应用崩溃记录。已安装APK与交付APK SHA-256一致。
- WorkBuddy/auto真实Responses JSON/SSE、Chat JSON、Messages JSON均有效200；未知裸模型400；stream、store、max_output_tokens错误类型实测400/gateway_error。

## 交付

AllIn-1.2.1-Gaeul-v5.apk，2,496,079字节。
SHA-256：12a01291e1d6ef69d5161408c2dd20147c5b5cf9078de795083c0468981949f5。
GitHub Release和手机Download交付同一份APK，附.apk.sha256。

## 仍需验收/已知边界

- API34模拟器通知首次授权/拒绝、真实重启后台FGS、Raccoon真实Responses、Codex/Copilot线上特殊行为仍需电脑端/对应账号验证；mock及Android16真机不能替代。
- 老配置缺setup.done时须在手机本机完成一次向导，之后恢复带口令局域网访问。这是新增门禁的兼容行为，非永久锁死。
- target35+及specialUse仍只做设计预留，未启用。
- 历史saveConfig的rename失败后复制兜底仍可能在极端复制失败中损坏磁盘文件；本次返回值和回滚只修新增路由/向导的假成功，未整体重构所有配置事务。
- SSE转换的多choice、状态式Responses和无法表达的上游专有内容仍按协议说明拒绝或有限支持，不能声称与Magpie全部能力完全等价。
