# 手机用量热力图与面板改进：电脑 Agent 验收

本批基于已发布提交 `99307877c712920d74619a1eaf954ed5b2d1e0b0`，提交源码供独立验收。应用版本仍为 `1.2.9 / versionCode 15`，本批没有创建 Release、标签或替换已发布 APK。后续若正式发布，须另行确定版本并递增 versionCode。

## 改动范围

- 治理页用量卡片中，在每日柱状图之后、拆分维度之前新增热力图。最近 53 周，周一为每列顶部，每格一天；手机保留 16px 格子，通过卡片内横滑查看历史，默认最新日期。
- Token / 请求切换，点格子查看准确的输入、输出、请求与失败数；隐藏未来日期，支持深浅主题、手动刷新和治理页可见时每 30 秒刷新。数据未变不重建网格，保留选择与滚动；隐藏页面中的异步响应不消耗首次定位。
- `GET /api/usage/heatmap` 使用全部持久账本建立轻量每日索引，新记录增量更新；不受原 90 天明细缓存限制。处理时区变化、跨日/DST、时钟回拨和未来记录，使用 long 累加。
- `/api/usage/summary` 改用本地日历午夜边界，修正柱状图每日合计与总量的时间窗口不一致，并改为 long 汇总。普通明细查询仍受原约 90 天 Row 缓存限制，全年历史由热力图接口提供。
- 账本写入异常会尝试回滚至写前长度；无法确认写入结果时显式报告，避免内存重放重复计数。重新打开文件时隔开残缺尾行，避免污染下一条完整记录。初始化扫描与并发记录通过快照和增量合并。
- 同批包含此前本地修改：权限/服务状态按近期真实探测显示；守护未验证与暂无冻结记录分开；删除首页整块电脑端 Agent 快速接入卡片；服务 IP 独立保存；14 个二态开关统一极简滑动样式。

## 统计口径与接口

接口返回 `ok, timezone, start, end, weeks, days, totals, firstRecordedDate, invalidLines, readErrors, persistenceErrors, uncertainWrites, generatedAt`。日期为手机当前时区的 `YYYY-MM-DD`，从本周周一减 52 周起至今天；返回 365–371 个实际日期，前端补未来占位至 371 格。

`days[]` 为 `{date,tokens,input,output,requests,failed}`，`totals` 含相同计数字段。

- Token = 成功行的输入 + 输出，与 All In 现有用量卡片一致。缓存字段不额外叠加，避免重复计算；未提供的 usage 不估算。此口径与当前上游包含缓存流量的热力图并不完全相同。
- 请求包含失败；账本每行是一次逻辑调用，`tries > 1` 仍只计一次。本批未重新审计所有供应商协议和所有提前断流路径，不能宣称旧账本从无漏记。
- 没有可靠定价来源，因此没有费用指标。
- `invalidLines/readErrors/persistenceErrors/uncertainWrites` 非零时界面标明可能不完整。零错误也不代表可恢复安装前或未上报的数据。`firstRecordedDate` 是最早已记录日期，不是数据完整覆盖起点。
- 独立核对时以账本时间戳按接口 `timezone` 分日，排除晚于 `generatedAt` 的行；失败条件为 `st >= 400` 或 `err` 非空，失败行不累计 Token。

参考只读上游提交 `129baeca0187ea92a95c9c1602d787ce61941f34` 的 `internal/usage/heatmap.go` 与 `internal/gui/assets/plugins/usage/page.js`。手机 UI 和账本格式沿用本项目。

## 电脑端复测命令

需要 JDK 17、Node 22、Android SDK Platform 37.0。无须提供正式签名凭据即可运行测试和构建 Debug。

```bash
node tests/dashboard-background.cjs
node tests/dashboard-setup-key.cjs
node tests/dashboard-routing-audit.cjs
node tests/dashboard-exposed.cjs
node tests/dashboard-heatmap.cjs
./gradlew clean offlineTests streamingHttpTests setupKeyHttpTests assembleDebug lintRelease --no-daemon
```

Windows 使用 `gradlew.bat`。专用 Java 用例可单跑：

```bash
./gradlew offlineTest_UsageLedgerHeatmapOfflineTest --no-daemon
```

CI 已将五项 Node 与含热力图的 `offlineTests` 纳入 Ubuntu / Windows 矩阵。签名 Release 构建仅在手机本地验证；CI 上传的是 Debug 测试产物，不是正式 Release。

## 已完成验证与待复验点

手机上已通过五项 Node、26 套 JVM 离线用例、流式 HTTP 节奏/压缩/工具测试、首次设置 HTTP、clean Debug/Release 构建及 lint。Lint 为 94 Warning、零 Error/Fatal。

新 Java 用例覆盖：日历午夜、DST、时区变化、53 周起点、未来排除、150 天旧账本重载、失败/重试、32 位以上累计、坏行/读取失败、部分写入/flush/回滚失败、残行后的正常写入、初始化并发和时钟回拨。Node 用例覆盖精确数值、日期补齐、色阶、点选、指标切换、刷新竞态、错误/空数据与隐藏渲染。

手机原始账本曾与接口逐日核对，并验证今天/7天/30天的 summary、daily 与 heatmap 合计一致；取样账本、配置和凭据不入库。配置哈希在覆盖安装前后保持一致。

电脑 Agent 请重点独立复验：

1. 真实浏览器与 Android WebView 的横向滑动、点格详情、默认最新定位、切页后返回、自动刷新保留选中日期；模拟 DOM 通过不能代替触摸验收。此前手机 GUI 树因大量格子截断，部分手势结果未可靠观察。
2. 320/360/390/430px 窄屏与大字体下无整页横向溢出，月份与星期行对齐；深浅主题色阶、选中描边和文字可读。
3. 今日/7天/30天与热力图重叠日期的精确总数一致，跨午夜和切换时区后无重复/漏日；有请求但没有 usage 时，不虚构 Token。
4. >90 天账本、损坏行和模拟磁盘错误时范围与异常提示正确，重启后无重复累计；读取原始数据只做有界只读核对，不清空用户配置。

本批仅源码同步供测试；后续修改、发布需以用户新指令为准。不要操作旧 magpie-pocket 仓库，也不要把本批同版本本地 APK 与已发布原 1.2.9 附件混淆。
