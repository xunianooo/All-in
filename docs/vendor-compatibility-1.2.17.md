# 1.2.17 供应商兼容验收

本版包含 Trae CN 接入与登录/流式/历史参数兼容，以及 WorkBuddy、Qoder、ZCode 和 Cline 的集中修复。基于社区精确发布包 WorkBuddy 0.2.24、Qoder 0.2.61、ZCode 0.1.51 及 Trae 0.2.31 的源码核对；不声称这些版本永远是最新。

## 行为

- WorkBuddy：可信显式会话和真实用户轮次派生 `X-Conversation-Request-ID`，同轮工具往返复用，下一用户发送更换；账号/provider隔离、OAuth刷新保持。补每请求消息ID；无可靠会话/轮次时随机降级，进程重启换盐。后端计次效果属于供应商行为，不能仅凭请求头存在就宣称节约额度。
- Qoder：业务10605/明确队列结构返回503及有界Retry-After，业务105表示登录过期，普通403保留403。排队、错误及发送后结果未知不自动重发/换目标，避免错误冷却或使会话亲和失效。错误正文和流事件有界，取消/deadline及资源关闭受控，截断流不冒充成功。attempt账本保留业务码。
- Qoder输出限制：两个字段严格正整数并取小；小于64在发送前明确400，不静默突破客户端或治理上限；缺失默认32000。
- ZCode：仅官方Messages端点的ZCode请求，将ToolSearch临时改名并同步工具声明、历史及具名选择，响应在交付或协议转换前恢复。移除DeferredToolPlaceholder/defer_loading；已有ToolSearchTool冲突时不改。工具ID、参数、正文及不透明thinking/signature不做全局替换。覆盖Coding Plan/gift、原生/桥接、流式/非流式；受保护历史仍服从现有原生门禁，不为gift绕过。
- ZCode拒绝分类、gift摘要失败不落付费Plan、账号缓存隔离及200错误信封继续保留；Cline图片路由补vision但尊重用户选项。
- 较旧签名规则缓存不覆盖较新内置1.0.2；缓存保留以供验证/后续更新，线上规则源独立维护。

## 验证与范围

用户确认电脑Agent已通过最新集中功能包。此前本地86离线套件、17独立HTTP任务、17前端脚本、Android36原生隔离7组通过；最后诊断修订另做6定向、Debug/Release及lint0Error/0Fatal。发布前Windows/Ubuntu CI另按正式提交运行。

测试包含同轮/次轮ID、双端点、输出cap、HTTP及SSE排队、截断与取消、零fallback、别名冲突、并行工具及签名内容保留。真实厂商计次、真实排队和Claude Code工具可见性未逐项取得独立上游证据；电脑“通过”不替代所有模型、旧Android24/25实机、长会话和供应商阈值验证。

复验入口：`./gradlew offlineTests streamingHttpTests setupKeyHttpTests outboundRoutingHttpTests`；前端脚本见CI。签名密钥、实际账号配置、模型正文与运行账本不进源码仓库。
