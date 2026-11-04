# 前台服务用途调整（1.2.9 / code 15）

本补丁保持 targetSdk34、compileSdk37、minSdk24。独立调整FGS分类，保留1.2.8服务健康、原子请求保护、恢复策略、权限引导及SSE/协议行为。

## 类型选择

- Android14/API34及以上：实际startForeground只传SPECIAL_USE(0x40000000)，不混入dataSync。
- Android10/API29至Android13/API33：实际传DATA_SYNC(0x1)，兼容没有specialUse类型的旧系统。
- Android7/API24至Android9/API28：传统两参前台服务调用，状态明确legacy。

清单声明dataSync|specialUse作为跨版本允许集合，运行时显式选择一个子集，绝不使用默认清单集合回退。声明FOREGROUND_SERVICE_SPECIAL_USE和具体PROPERTY_SPECIAL_USE_FGS_SUBTYPE：用户主动开启、持续为其他应用/受信局域网客户端提供本地HTTP AI网关，直到用户关闭。dataSync类型权限仅限maxSdk33，避免现代系统误用兼容类型。

FGS提升失败时记录真实异常类别，不记录密钥；停止服务、取消恢复闹钟，不持锁、不启动工作线程/HTTP监听，onStartCommand同样守卫。现代系统不退回dataSync或两参默认类型。外部启动请求先记录再dispatch，避免异步失败被“已请求启动”覆盖。面板显示实际类型与预期类型，并说明服务分类不等于免冻结。

## 为什么调整

长期本机监听服务不是有明确完成时刻的一次数据同步。官方specialUse用于其他类型无法覆盖的有效前台用途，需要具体用途说明；Google Play分发仍需按用途审核，本项目不假定一定获批。

Android15+针对target35+的dataSync后台累计限额是未来架构风险；本补丁现代运行时不再选择该类型。但target保持34意味着不能把当前运行测试说成完整target35升级验收，也不能凭短测宣称已经完成6小时计时、API35开机及所有OEM验证。

## 验证范围

ForegroundServicePolicyTest用真实生产策略注入Promoter，在API24–37输入下验证实际选择、错误传播及无fallback；解析源清单的权限/集合/subtype。25套离线、首装HTTP、四项Node、gzip协商真实流式回归和clean Debug/Release/lint保持。构建后另外核对merged/打包清单；装机后必须以dumpsys实际foreground type及HTTP状态确认，不只读应用自报。

真机可用设备为Android16/API36，需确认runtime type=0x40000000且不含dataSync、权限授予、用户关闭不复活/开启、进程恢复、流式和配置哈希保持。旧版本纯Java策略验证不代替旧系统安装和运行矩阵；Android14/15、ColorOS16和target35兼容限额/开机限制需要后续实际设备验收。

本补丁不承诺specialUse能绕过厂商冻结、用户强行停止或任意后台启动限制，不增加悬浮窗/无障碍/静音音频等保活手段。

官方依据（审查时）：Android Developers foreground service types、Service.startForeground API及foreground service timeouts。发布说明列实际结果与未验证边界。
