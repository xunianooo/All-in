# WorkBuddy / 小浣熊登录前端点探测（本地试做）

本批仅覆盖国内 WorkBuddy `workbuddy` 和小浣熊 `raccoon`。在供应商卡片登录按钮前增加“端点探测”，已接入详情及未配置的添加供应商卡片均使用同一入口。无需登录、账号或模型 Key。探测不作为登录前强制门槛。

## 行为与边界

管理 API：`POST /api/providers/endpoint-probe`，JSON 严格为 `{ "provider": "workbuddy" }` 或 raccoon。沿用现有管理接口鉴权，不接受用户自定义地址、方法或请求头；响应 no-store。

- WorkBuddy：GET 固定登录页；OPTIONS 实际认证初始化路径；OPTIONS 实际 chat/completions 路径。
- 小浣熊：GET 网页入口；GET 模型目录（需认证的服务入口，明确不是独立认证验证）；OPTIONS 实际 chat/completions 路径。
- 地址以当前 LoginActivity / GatewayServer 实际接线为依据。OPTIONS 不初始化授权，不发短信，不提交推理任务。
- 使用调用开始时捕获的 NetworkPolicy 快照。手工固定 HTTP/1.1 经默认信任 TLS / HTTPS 主机名验证及策略 CONNECT 隧道，无 CookieHandler / Authenticator / Authorization / Cookie，不接受凭据参数，不读取生产账号配置。
- 每项只解析有界响应头（总16KiB、单行4KiB、状态行1KiB、最多3个1xx），不读取响应正文、不跟随重定向、不保存响应 Cookie/Location。异常不显示原始内容。
- 两家分别单飞，共享最多6个工作线程、无等待队列；三端点并行，总响应预算10秒，单阶段socket timeout最多4秒，无自动重试。DNS或CONNECT返回前取消有系统限制，返回后立即关闭，线程数量始终有界。
- 分清DNS、TLS、超时、连接失败、策略阻断；401=要求认证，403=拒绝访问，404/405=端点/方法未验证，429=限流，5xx=服务错误。顶层reachable只代表收到HTTP响应，UI按明细显示警示。
- 不能证明账号、额度、真实登录或模型推理成功；WebView登录网络路径可能不同于网关出站设置。

## 本地验证

新增3套Java定向：ProviderEndpointProbeHttpTest（请求/响应头与预算）、ProviderEndpointProbeTlsHttpTest（真实环回CONNECT+TLS、正确SAN、错误域名/不信任拒绝、无凭据、header-only、滴送总预算）、ProviderEndpointProbeGatewayHttpTest（无账号可用、严格请求、管理鉴权、策略快照）。首轮另测NetworkPolicyOfflineTest和ZCodeGiftSummaryHttpTest，通过；末轮header高位字节兼容修订后三套再次通过并签名构建。

Node：dashboard-endpoint-probe.cjs、dashboard-provider-management.cjs、dashboard-model-diagnostics.cjs通过。未执行全量门禁、lint、远程CI、Android24实机；当前Android36实际装机API与GUI点按均已验证。

真机当前网络结果（一次快照，非永久可用承诺）：WorkBuddy GET网页301、OPTIONS认证404、OPTIONS模型API401；小浣熊GET网页200、GET目录401、OPTIONS模型API200。面板准确显示对应含义，未自动打开登录或同步模型。本次未向两家发送推理请求。

## 后续供应商扩展的必需验证（用户明确要求）

吸取WorkBuddy同步目录被错误覆盖为auto的经验，后续各供应商新增探测时，不能只验按钮或HTTP响应，必须按影响面验证原业务：

- 核对实际登录/认证/API地址、客户端头与目标响应契约；匿名探测不初始化登录、不使用凭据、不改账号/模型/路由配置。
- 覆盖模型同步HTTP失败、业务失败、缺字段、空目录和错误分组；不得用合成auto/默认模型伪装云端成功并覆盖旧models/meta。有效目录才能原子提交。
- 检查探测前后配置与凭据、原登录入口和同步结果；账号/模型仍存在不能代替值一致核对。正常刷新等预期变化须明确记录。
- 结合影响面做定向回归；具备合适真实验证条件时核查实际同步、代表性非流式/流式调用及终态，明确真实调用消耗范围。无账号/授权/条件则保留未测边界，不宣称全通过。
- 区分DNS/TLS/HTTP到达、认证、模型能力与实际推理。401/403/404/OPTIONS200不作为完整业务验收。
- 保持小改定向、完整节点集中验收的节奏，不因此每次重跑全量或远程双CI。

## 全部已支持供应商补齐（后续累计批）

统一匿名探测现支持20家：WorkBuddy、小浣熊、WorkBuddy AI、Trae CN、CatPaw、DeepSeek网页、ZCode、Qoder CN、Cline、Codex、GitHub Copilot、MiniMax Code、Antigravity、DeepSeek API、SiliconFlow、OpenAI、Anthropic、OpenRouter、腾讯混元、Loomy。现有placeholder不伪装为已实装。每家根据实际源码地址配置2~5个固定GET/OPTIONS，商业供应商只检查已有默认API入口，不编造网页登录。

UI覆盖未配置添加页、已接入详情和商业Key行；新增WorkBuddy AI/Loomy静态卡片让无账号也可探测，Loomy不新增网页登录能力。商业Key草稿/输入事件保持，重渲染恢复探测pending/结果。探测明确标识official-defaults，不代表用户自定义base、地区、动态项目或浏览器路径。Antigravity旧高级probe函数/API不改，只把原按钮标为“高级探测并切换端点”，旁增无副作用的统一探测。

扩展后按固定目录维护供应商单飞，并增加全局单批gate，避免多家同时点按产生线程争用和虚假故障；原最多6工作线程/10秒预算保持。退出finally关闭所有任务/socket，busy失败显示繁忙，无自动重试。

验证：114套offlineTests和19个Node脚本通过、Release签名构建成功；所有20家通过生产API真实执行匿名探测（部分海外端点在当前网络超时/403，结果如实展示，非所有站点都可用）。完整20家探测前后config字节相同，accounts/keys/models/meta/base/protocol/enabled逐项哈希无变化。安装即时配置哈希相同，installed APK hash一致；6健康API200、ZCode gift summary正常。

原业务：对本批起点做源码隔离比较，只有本探测类和dashboard变化，GatewayServer仅改不支持供应商的提示；原Auth/登录/推理/模型同步/自动同步/gift/Key编辑函数未修改。额外通过生产API同步WorkBuddy17模型、小浣熊9官方模型（旧12包含本地别名，手动同步按当前官方目录更新，不归因探测）；三家WB/小浣熊/ZCodeGift代表模型非流式与流式实测正文OK，流式DONE、无error。小浣熊64token预算先只有reasoning，256预算正常，不据空正文误判业务失效。实际同步/调用后凭据仍一致。

边界：本批未用20家全部真实账号重新登录、未逐模型完整工具长会话、无旧API实机/远程CI/lint。不将合成测试或匿名HTTP响应等同真实账号全量验收。所有此前本地累计改动和ZCode/WorkBuddy修复保留，暂不发版。
