# Q2 集成验收记录

- 内置规则仍提供默认快照；Android 26+ 在 `quirks_trust.json` APK asset 不存在时禁用远程更新，未配置实际源或公钥。Android 24/25 仅使用内置规则，状态标记 unsupported。APK 描述符格式和发布信任要求见维护交接；不得从 UI 接受 URL 或密钥。
- 读取 `GET /api/quirks` 显示版本、来源、规则数、schema、engine、加载错误及脱敏更新状态；`POST /api/quirks/check` 经既有管理鉴权，由用户主动执行。管理页面使用 textContent 展示；未知结果要求读取状态核对，不自动重试或轮询。
- 每个新请求读取当前不可变快照；旧请求保持原快照。验证成功并原子写缓存后才发布新快照，失败不替换已生效规则；服务停止时关闭更新器，过期 worker 不得发布。
- 签名算法 SHA256withRSA；缓存采用 `java.nio.file.Files.move` 的原子替换。官方 [Files API](https://developer.android.com/reference/java/nio/file/Files) 显示 API 26 引入；[Signature API](https://developer.android.com/reference/java/security/Signature) 记录签名接口。API26+ 访问与旧版隔离的效果仍需 API24/25/26 真机验证。
- 本次本机离线 Gradle：release Java 与离线测试编译通过；SignedQuirksStoreOfflineTest、QuirksUpdateControllerOfflineTest、QuirksGatewayHttpTest、ProviderQuirksHttpTest 通过。Node dashboard-q2 和 dashboard-combined 通过。Gateway HTTP 测试为 JVM session 模拟，下载测试为注入连接；尚无真实 TLS、Android 测试或完整 gate。
- 临时可信配置只是未来部署流程的输入，尚需真实匿名 HTTPS 源及固定生产公钥，并经安全审查；匿名传输通过 NetworkPolicy 的 socket 隧道发起 TLS，不使用全局 CookieHandler 或 Authenticator；目标 Android 的证书链与主机名验证仍需真机验证。控制器检查有等待 deadline，磁盘提交仍不能提供硬实时截止。请求线程的 snapshot 保证不代表多进程文件锁；全进程只允许一个 gateway/store。


## 联合最终验收

以维护区Q2-MINIMAX-HANDOFF.md与artifact-local-q2-minimax.json为当前最终状态，早期未集成/未构建条目是过程历史。59JVM/全部HTTP/14Node及HTTPNodefixture通过，最终DebugRelease/lint Error0/Fatal0/97Warning。Android原生RSA/cache/journal/隔离规则API与实际transport localhost TLS三项通过，native302/slow遭SIGKILL未完成。已同Gaeul签名保配置装机、6API200。远程可信分发仍disabled；真实MiniMax/旧API ART/多进程/断电未验。无commit/push/改版/发版。


## 真实生产签名源已激活

用户电脑首签经独立SPKI指纹核对通过，APK已内嵌固定trust asset。Pages current.bin匿名直接200/octestream；Androidnative/隔离Gateway真实updated1.0.1、unchanged、进程重启LKG回读通过；生产GUI“首页→供应商兼容规则→检查更新”已显示更新成功，配置哈希未变。此前治理页入口与disabled描述属过程历史。本次仅asset变化，定向5suite与DebugRelease/lint Error0/Fatal0/97Warning通过，前批59JVM证据沿用。完整证据见维护区RULES-ACTIVATION-HANDOFF.md。
