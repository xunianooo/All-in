# 供应商状态分层：审查修复后本地交付

本文件替代首批旧说明；修复审查发现的确定缺陷，未改变正式版本号或发布到远程。

## 诊断契约

- errorKind/failureLayer说明本次可观察的失败原因，不能仅凭403证明账号整体受限，也不能用credit/balance单词证明余额耗尽。
- affectsInference与canTryOtherModel为可空字段；证据不足返回JSON null，不从用量读取失败推出推理不可用。
- executionUnknown与transportKind独立。请求可能已发送而结果不可判读时，保留DNS/TLS/timeout等原因，并单独提示可能执行计费，先确认结果。面板请求或JSON解析失败也不宣称“未到上游”。
- 负状态不能归NONE，未知非空类别不能显示“没有失败”。业务码优先但未知业务码不能覆盖明确401/403/405诊断。

## 修复

1. gift不覆盖测试型号：返回model_not_in_gift容器，修复旧null导致的空指针；确实已验证覆盖不到才保留原有Plan测试路径。
2. gift摘要失败或不完整：停止推理、不改走付费Plan，保留已知HTTP码与业务码；读不到不缓存成零。
3. gift连接/读取失败：Stream/Answer携带异常分类，负状态映射502；已发送但结果未知不重放，也不回落Plan。
4. gift只接受2xx、有效的content块和字符串text/thinking；顶层或嵌套业务失败、坏JSON、空回包不假成功。错误用固定诊断，避免回显上游凭据。
5. Cline按identity/balance/limits分别校验schema和业务结果，保留有效部分并返回complete/partial/unread/sections。真实零与未知区分，DNS/TLS/timeout分类保留；连接finally关闭、正文1MiB有界。
6. 面板移除“账号整体不可用”的无依据断言，显示执行未知提醒；gift余额0正常显示；gift能力模型名、错误与Antigravity动态字段完整转义。

## 验证

- 全量offlineTests与assembleRelease通过，118个offlineTest任务，158个Gradle任务全部执行成功。
- 21个可独立运行Node脚本全部通过。dashboard-setup-key-http.cjs需真实fixture参数，不作为独立脚本直接执行；其相关Java offline门禁仍运行。
- 新增ClineUsageLayersHttpTest、ZCodeModelProbeLayersHttpTest；扩展ModelProbeDiagnosticsHttpTest、分类反例测试；新增dashboard-gift-safety.cjs并注册CI脚本，但未触发远程CI。
- gift真实API测试的合成链路覆盖：未覆盖模型回落、摘要失败/不完整禁止Plan、坏JSON、success:false、嵌套error、非字符串text、成功；服务端完整接收POST再断流，只收到一次请求，执行未知且不回落Plan。
- 普通通道发送请求字节后读取超时：保留timeout及executionUnknown，并显示计费提示。
- Cline真实fetchUsage方法配合合成HTTPS连接覆盖HTTP错误、schema变化、非法JSON、部分成功、真零、DNS/TLS/timeout及连接关闭。

## 装机证据

APK 2,879,162字节，SHA256 08a132837e50091543a95d82c3a154d7f888d077ee1b241edec7ccb71ba0af84，与安装后的base.apk一致。
版本1.2.18/versionCode24；8个健康API均200，FGS specialUse正常。
26家供应商×7字段(accounts/keys/models/modelMeta/baseUrl/base/protocol)装前装后哈希一致。
/api/config附带providerStateTokens是进程随机HMAC值，重启会变，不把它误当持久配置变化；未宣称整份API响应哈希一致。
真机无供应商/无凭据诊断符合预期；ZCode glm-5.3-flash使用gift真实推理成功。
Cline没有配置真实账号，仅完成合成调用链测试，不称真实账号验收。

## 范围

这是第1项第一批的纠错交付，后续状态板、探测记录、其他Auth分层和冷却粒度仍按接续文件安排。未提交、未推送、未发版。
