# SSE 自动 gzip 攒块修复（1.2.7v3 / versionCode 13）

## 问题与范围

1.2.7v2 修复了 Java InputStream 默认批量读取跨事件等待 16 KiB 的问题，但仍可能在后面的 HTTP 压缩层再次攒块。NanoHTTPD 2.3.1 默认允许压缩 text/*：客户端接受 gzip 时，SSE 会进入没有逐事件刷新的 GZIPOutputStream，结束时才集中释放。原测试没有显式协商 gzip，因此未覆盖实际客户端常见的请求条件。

在 1.2.7v2 实装网关的 WorkBuddy 小说请求中：identity 首内容 2090ms、输出持续 7461ms；gzip 首内容 8656ms、正文集中在 24ms 内到达。相同定时 mock 经生产 GatewayServer 的 Chat、Messages、Responses 三条路径也得到 identity 持续、gzip 结束后集中到达。客户端按完整 SSE 事件计时，已先解 HTTP chunk 再解 gzip，结果不依赖 TCP 包数。

## 实现

GatewayServer 覆写 NanoHTTPD.useGzipWhenAccepted(Response)，按最终 MIME 类型去分号参数、忽略大小写后精确匹配 text/event-stream：SSE 禁用自动 gzip，其余保持 super 原策略。不能只在 serve 中 setGzipEncoding(false)，因为 HTTPSession 会在 serve 返回后再按策略和请求头覆写该字段。

serve 在协议转换后为所有正常返回的 SSE 统一设置 UTF-8、Cache-Control: no-cache, no-transform、X-Accel-Buffering: no。后两项防止额外缓冲或转换，是辅助头，不能替代实际压缩策略，也无法强制所有外部代理遵守。

保留 1.2.7v2 的 bulk read 修复及原脱敏、中间件、工具参数、用量、取消关闭和错误终态。保持网页、脚本和普通 JSON 的原压缩能力。无需客户端强制 identity，也不引入对 NanoHTTPD gzip 发送循环的维护分支。

参考 Magpie 固定版本 129baeca0187ea92a95c9c1602d787ce61941f34：internal/gui/web.go 的 compressible 排除 SSE，internal/gui/pageservice_test.go 带 Accept-Encoding:gzip 验证首事件在上游结束前到达，internal/gateway/sse.go 逐事件刷新并设置反缓冲头。这表明上游防范同类风险，不代表已验证其全部部署或历史故障。

## 回归门禁

保留 21 套离线测试（原 20 套及 v2 的读取契约），保留首装 HTTP、三项 Node、首页 Gaeul/GitHub 卡片。扩充 streamingHttpTests：

- 显式 identity、gzip、gzip, deflate；五条路径为 Chat→Chat、Chat→Messages、Chat→Responses、Responses→Responses 原生、Responses→Chat。
- 断言 SSE 未压缩、UTF-8 和反缓冲头，首 reasoning/正文提前、20 个 50ms 内容事件持续输出、内容无丢重、正常及截断错误终态。
- 接受 gzip 时仍用工具首段门控上游续发，验证 Chat/Responses 工具参数提前可达；Messages 保留原工具身份分片/顺序缓冲，只断言最终完整性。
- 五条路径增加超过 16 KiB 的高可压缩文本；验证 HTML/JSON 接受 gzip 后仍压缩且正确解码。
- HTTP fixture 本身拥有 HTTPSession，压缩策略必须委托生产 GatewayServer 方法，不在测试 wrapper 复制排除逻辑，避免测到错误实例产生假阳性。

clean offlineTests streamingHttpTests setupKeyHttpTests assembleDebug assembleRelease lintRelease 及三项 Node 通过后推送；Windows 和 Ubuntu CI 均通过后发布 Gaeul 正式签名 APK+SHA256。实装包必须再接受 gzip 请求运行小说/三协议流式测试；电脑 ETA 原请求仍需最终验收。

本修复消除网关自动 gzip 的额外攒块，不承诺逐字 UI 显示；上游每次提供的增量大小及客户端渲染仍影响观感。Qoder/CatPaw 等原先聚合后生成 SSE 的供应商路径、Messages 工具身份缓冲不属于此补丁的重构范围。原生 Messages 上游的网络节奏尚不在本次五路径矩阵内。
