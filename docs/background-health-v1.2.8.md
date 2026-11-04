# 跨厂商后台健康与恢复（1.2.8 / code 14）

针对亮屏、ETA 前台而 All In 后台失联的报告，增强无 Root 服务健康与恢复，不假定单一厂商冻结原因，不添加无意义的静音音频、悬浮窗或精确闹钟权限，也不改变用户厂商设置。

## 服务健康与恢复

- GatewayService 运行时单线程约每 30 秒请求本机 `/api/health`，检查 HTTP200、服务名和本实例 UUID。启动成功两秒后首次探测；监听存活与 HTTP 健康分开，连接到其他占端口程序或拿到401不再冒充本服务正常。
- 健康检查连续三次失败后才考虑重建，恢复冷却两分钟；启动失败按2/10/30/120秒退避。所有引擎启动/停止串行，Activity 返回请求实际健康检查，而非只信静态RUNNING标记。
- 模型请求准入与“空闲恢复”共用原子门禁。活动请求从转发开始一直计到完整HTTP发送、尾帧、flush及清理退出；有请求时推迟重建。覆盖/v1、/v1beta、/backend-api和现存/responses别名。AllInTrackedResponse只委托NanoHTTPD原发送实现，保留长度、gzip策略、chunk framing及bulk读取。
- onDestroy先标记失效，停止任务和释放锁；慢初始化/监听关闭不阻塞主线程。候选实例在发布前重新检查停止及用户开关，避免关闭后旧启动任务复活。

## 无 Root 定时恢复

KeepAliveReceiver 使用goAsync，并在finally完成广播；广播只短时分派，不做连环sleep和长HTTP重建。定时使用15分钟请求间隔的非精确单次setAndAllowWhileIdle，到期后重新申请；不会每次打开面板推迟已有闹钟。升级可替换旧的重复闹钟。

用户关闭网关时取消闹钟；receiver、启动、恢复、开机和包替换入口均检查用户开关。重新启用后正常恢复。应用未解锁阶段不声称可以读取凭据或启动网关。后台启动受到系统限制时记录限制结果，不把启动请求计作恢复成功。

系统可延迟闹钟或拒绝启动；进程冻结时自己的30秒线程同样暂停。因此这些机制不能保证所有ROM永久存活，不能绕过用户强行停止。

## 多厂商设置与状态

识别小米/Redmi/Poco、OPPO/一加/realme、vivo/iQOO、华为、荣耀、三星及通用Android。厂商私有设置仅尝试对应品牌候选，失败回退标准应用详情；省电设置优先应用详情，不把其他厂商页面或普通权限页当成成功适配。没有宣称所有系统版本的私有入口实测通过。

通知与电池查询失败显示未知。自启动/省电“我已设置”仅为手动确认，不表示系统验证。面板展示PID、监听、HTTP探测时间/结果、失败数、恢复数、活跃请求和锁状态。旧Root守护心跳只表示收到上报，包名字符串不证明真正保护，verifiedTarget保持false，不根据没有冻结记录宣称24小时从未被冻结。

## 验证与边界

保留原21套离线测试，增加BackgroundHealthOfflineTest、RecoveryPolicyTest、TrackedResponseOfflineTest：准入与恢复竞争、长流/JSON/错误响应生命周期、HTTP最终flush/尾帧、写失败/读失败清理、固定长度和gzip发送、探测身份/伪造Host及转发头/重定向/超时/超限、连续失败冷却与启动退避。所有账本fixture隔离临时目录。

新增dashboard-background.cjs，与原三项Node和首装HTTP、三协议压缩协商节奏回归共同运行；Windows/Ubuntu CI都执行。保留SSE禁压缩和v2批量读取修复，首页Gaeul/GitHub卡片保留。

手机候选包验收：配置哈希保持；关闭后额外服务启动请求仍不监听，重新启用正常；真实WorkBuddy gzip请求798内容事件，首1630ms、持续16673ms、正常终态，活跃数1→0且无误重启；普通SIGTERM退出后观察到新PID健康，未使用force-stop证明自恢复；显式投递receiver验证one-shot重新排程。这些是单台Android16/MIUI设备结果，命令侧Root用于测试控制，应用自身未获得Root。不能替代无Root ColorOS16或其他厂商真机长时后台验收。

最终clean构建、签名、CI和同包验收数据见发布说明及仓外维护记录。朋友应在无Root OPPO ColorOS16保持ETA前台/All In后台复验，报告PID、健康最后时间、错误类型和是否回到面板后恢复，避免只凭常驻通知判断健康。
