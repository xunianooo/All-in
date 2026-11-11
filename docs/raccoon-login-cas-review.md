# 小浣熊登录凭据与 CAS 审查（独立补丁方案）

## 审查结论

当前 All In 手机登录不是插件里的授权码登录。`LoginActivity` 打开 `https://xiaohuanxiong.com`，从 WebView CookieManager 查 `xhx_token` 和 `user_name`，再向 Gateway 通用 `/api/auth/raccoon/add` 发送 `{name, token, provider}`。JavaScript fallback 也只返回一个 JWT 字符串。Gateway 的通用入池分支调用 `addProviderAccount`，只保存 `id/name/token/addedAt`，所以当前来源不能拿到或保存 `refresh_token`、JWT expiry，也没有 `source`/`type`/`accountId`/`uid` 字段。供应商身份只在路由参数和请求 `provider` 字段上临时存在，未写入账号对象。

本地 `opencode-raccoon-auth` 插件提供另一条浏览器 OAuth 流：授权页回调带 authorization code，随后 `POST /api/web/auth/v1/login_with_authorization_code`，JSON `{authorization_code}`，成功响应 `code: 0, data: {access_token, refresh_token}`。插件要求两种 token 都存在。`expires` 来自 access JWT 的 `exp * 1000`，不是响应里命名为 `expires_at` 的字段；JWT claims 还用于读取身份 `iss`/`sid`/`sub` 和 `name`。插件的 desktop auth 是单独来源，读取桌面 `auth.json`，并有意不动桌面 refresh token。

独立解析器与合成 fixture 已加入，明确把供应商设为 `raccoon`、来源设为 `browser`，并解析 refresh、毫秒 expires、uid 和账号身份。当前 `LoginActivity` 已增加独立“授权码登录”按钮：按上游契约生成 32 字节随机 state 和动态 `127.0.0.1` loopback redirect，拦截回调并校验 host/port/path/state/重复参数/单次使用；code 经 Raccoon 专属 HTTPS 客户端交换，关闭 HTTP redirect 跟随并限制响应大小。浏览器拒绝、超时和交换错误均显示状态。交换成功只在内存验证凭据，界面明确报告“暂不入池”。

原有网页 cookie/JWT “完成入池”入口保持原样，继续用 access-only 通用添加流程。新授权码凭据不会调用那个会丢弃 refresh 的接口；桌面客户端账号仍没有读取或 refresh 支持。

## 为什么暂不能只改 Raccoon 专属代码和 LoginActivity 完成持久化

刷新候选构造可以只放在 Raccoon 专属协议类，登录页面也可以发凭据；但安全保存必须在 Gateway 的权威配置锁内完成。当前登录路径的通用 `/api/auth/raccoon/add` 不接受完整 OAuth 记录或条件版本，并把 refresh 字段丢掉。`/api/config` 是整份快照替换入口，没有按 Raccoon 账号的 CAS，也没有保留 Raccoon accounts 的专用合并规则。LoginActivity 直接改 `config.json` 会绕过 Gateway 内存配置与其写锁；向 `/api/config` 发旧快照则可能覆盖其他正在写入的配置。故本批在 LoginActivity 中只完成授权和凭据交换，不能实现可靠的账号级 CAS，也没有保存 refresh token；产品界面明确告知用户新 OAuth 凭据暂不入池。

Gateway 当前实现对其他凭据刷新也采用同一原则：在 `synchronized(GatewayServer.this)` 中核验账号唯一 ID、完整 expected 快照、启用状态和引用代次，写临时文件并原子替换成功后才更新内存。Raccoon 后续必须在 Gateway 所属方的唯一实现里增加同等原子入口；这次没有触碰 `GatewayServer.java`。

## 可安全分批落地的补丁

1. **本次已完成的部分：** `RaccoonAuthorizationFlow` 负责 loopback redirect、随机 state、回调验证、超时与取消；`RaccoonOAuthClient` 负责插件式 code exchange 与可注入 transport；`RaccoonLoginProtocol` 解析 response；`RaccoonRefreshPolicy` / `RaccoonRefreshService` 管理刷新决策与候选记录。生产 OAuth transport 只用于用户点击新授权码按钮并收到有效回调后；所有离线测试使用 fixture，不联网。凭据尚未持久化。
2. **Gateway 持有人后续接线：** 增加 Raccoon 专用 `POST /api/auth/raccoon/oauth-add`（或同等方法）。Gateway 自己在锁内找唯一 enabled 的 `raccoon` provider，按 provider identity 和 stable account id / uid 定位；对重复、缺 provider、disabled、disk error 明确失败。新登录只追加/更新匹配身份的账户并保留账户级策略；refresh 提交须 compare 完整 expected account、account generation 和 provider enabled，再只替换 `access`、`token`、`refresh`、`expires`、`lastRefresh`，`AtomicConfigFile.write` 成功后发布内存状态。不能通过整份 `/api/config` 回写完成。
3. **LoginActivity 后续接线：** 仅在 `currentProvider == "raccoon"` 时启动插件同契约的授权码流，监听并校验本次随机 state、授权回调与 code；在 HTTPS 下将 code 发给专属交换入口，要求完整 access + refresh 后再发 account record。若保留 cookie 手动登录，则缺 refresh 时仍走原通用 access-only 保存路径，保证老用法。
4. **桌面凭据：** 登录来源标记 `source: "desktop"`（必要时再加明确 `desktop: true`）；service 永不 refresh。桌面 access/refresh 不从本机文件透传到浏览器登录记录，也不从桌面 auth.json 消费 refresh token。

推荐持久字段：`id`（本地稳定账号记录 ID）、`accountId`（插件按 JWT name/uid 生成的逻辑账号身份）、`uid`、`name`、`type: "oauth"`、`provider: "raccoon"` 或依赖外层 provider、`source: "browser"|"desktop"`、`access`、`token`（兼容当前 gateway）、`refresh`、`expires`（毫秒，兼容既有刷新策略命名）、`lastRefresh`、`enabled`、`addedAt`。JWT 无 exp 时记 0，不能猜时间；若产品需要 `expires_at` 可作为同一毫秒数的别名，但参考插件实际用 `expires`。

## Fixture 与运行

`RaccoonAuthorizationFlowOfflineTest` 覆盖授权 URL、loopback redirect、随机 state、重复/错误 state、WebView 回调、拒绝、HTTP 本地 listener 和注入式 fixture 交换。`RaccoonLoginProtocolOfflineTest` 覆盖插件所用请求字段和成功响应、Bearer 清理、refresh_token、JWT exp、hex uid 与 name；还覆盖缺 refresh、过期授权码及旧 access-only 账户保持不刷。运行：

```sh
./gradlew :app:offlineTest_RaccoonAuthorizationFlowOfflineTest :app:offlineTest_RaccoonLoginProtocolOfflineTest :app:raccoonRefreshOfflineTest
```

这些是本地合成响应，不证明真实供应商当前端点或响应与参考实现完全一致；没有真实请求。
