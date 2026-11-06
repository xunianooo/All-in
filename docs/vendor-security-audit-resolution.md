# 供应商审计逐项核验与修复

基准：用户提交报告针对1.2.15/code21 Image-Body；本次实际从已完成Trae的1.2.16/code22本地改动继续，保留Trae全部实现。报告中的优先级、风控原因和效果数字不是直接认定的事实。未使用真实推理或付费调用证明账号安全，未提交推送或发布版本。

## 核验结论

| 报告项目 | 本地事实和处理 |
| --- | --- |
| WorkBuddy旧UA、全零会话 | 确认存在。按实际npm0.2.24源码采用5.9.3/CLI2.165.0组合，并非报告的5.5.6/0.1.29。动态会话绑定显式session；没有会话时按逻辑请求生成。旧规则不能覆盖动态ID或恢复旧CLI UA。补齐两站商业Key三头；已存在强制流式与非流式聚合，继续保留。 |
| ZCode缺会话指纹 | 赠送通道原有header和metadata，报告全称缺失不成立。缺口在官方Coding Messages路径，现补齐并保留已有opaque user_id及签名消息块。Gift传输原本未传实际session，已接入；已有metadata不覆盖。 |
| ZCode 3012保护 | 原先405/拒绝后会再发一次dress请求，存在重复撞拒风险。现首发采用已知请求形态、拒绝不自动重发；结构化3012与可信边缘HTML分别分类和180秒本地冷却；裸405不宣称账号被封，亦不当作可安全重放。180秒是本地策略，不是证明上游账号强制冷却。 |
| ZCode 200错误信封 | 原有ZhipuEnvelope与挂接，但原适用路径较窄。现扩展至精确官方Coding Chat/Messages路径，兼容success:false和结构化error，1000→401、1113→429、3012→405，正常内容/choices不误改。200中的3012也进入冷却。 |
| DeepSeek工具Schema清洗 | 未证实普通模式一律拒绝$schema，不能据此删除。官方Beta strict明确要求additionalProperties:false，删除会弱化约束。保持原Schema，增加两协议桥的dialect、同名用户属性和嵌套约束保持回归。 |
| Cline完全没有DeepSeek pin | 不成立，原已有only deepseek和implicit-caching。现补有图时vision，并只作用官方精确路径；用户已有provider/providerOptions完全优先，不覆盖自主路由。不承诺实际算力归属或100%官方直连。 |
| MiniMax固定时区0 | 不成立。原代码已用TimeZone.getOffset(now)/1000，包含夏令时。本次仅抽取可测helper，补冬夏时、半小时时区回归，行为不变。 |
| Copilot降至vscode1.95.3、加随机session | 无足够依据，不降级、不伪造随机会话头。核到上游维护dist包0.1.2，其版本头组合与本地一致。该事实只是插件自述，不证明现实官方发行版本或防频控效果。 |
| 占位供应商清单 | 六家仍placeholder，Loomy为passthrough，报告对此基本准确；不为修审计而点亮未实现供应商。 |

## 同批发现并修复

1. Gift余额缓存对象/账号scope/时间原本分开volatile更新，存在并发错配；改为单一不可变快照，防跨账号取错权益。
2. Gift摘要失败原本可能被当成“不支持该模型”而落到Coding Plan；现在明确返回状态未知，不静默切换计费路径。
3. 模型探测和目录读取对disabled账号过滤不一致；统一使用启用账号，禁止已禁用账号进入Gift探测。
4. Gift加入请求期限、连接取消、出站限流、gzip、有界响应、禁止重定向和完整释放；冷却检查在请求发送前以及限流排队后执行。
5. Trae集中回归复现慢速滴流在JDK17关闭部分HTTP响应时可能耗时近10秒；限制底层读取、按绝对期限检查并避免主动drain未读完响应，连续滴流与挂起请求回归通过。保留刷新未知结果隔离。

## 兼容规则

内置规则升1.0.2，移除静态Conversation-ID；保留旧签名规则的精确头集合解析兼容。已验签但比内置旧的1.0.1缓存保留在磁盘，活动快照使用内置1.0.2，状态builtin_newer；主动更新取得较旧有效包显示newer_local，不当签名损坏，也不回滚。没有修改信任根、私钥或线上公开规则源；线上当前规则可能仍是1.0.1。

## 证据与边界

主机集中81离线套件、HTTP门禁、Debug/Release通过；16独立Node通过，专用HTTP Node fixture由对应Java门禁运行。lint实际读取0Error/0Fatal/102Warning。Android最终17组PASS，包括真实Context网关的WorkBuddy请求头/流式聚合、Cline图片pin、ZCode会话/信封/3012冷却和组回退，以及原Trae九组。

Android首轮测试因测试代码使用JVM JSONObject.similar（Android无该API）失败，已改为递归结构比较并重跑全17组通过；生产代码未依赖该方法。首次集中Trae滴流失败留在日志，不能用独立通过掩盖，修复后最终全门禁通过。

这些是本地协议、并发与隔离假上游验证，不能保证供应商永不风控、缓存达到固定百分比或真实账户一定可用。真实Trae授权/业务、各厂商实际阈值与旧API24/25实机仍未全面验证。文档第6节对其他供应商的赞许不等于本轮重新完整审计其全部实现。

来源证据：维护目录 /workspace/allin-package-maintenance/vendor-audit。WorkBuddy/ZCode/Cline/MiniMax发布包按npm SHA512校验；Copilot按上游dist索引SHA512校验；DeepSeek官方文档快照和Hash位于deepseek子目录。未将生产凭据、账号JSON或签名密钥放入交付物。
