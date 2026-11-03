# v1.2.0 手机端验证与电脑端交接

版本：1.2.0，versionCode 4，包名 com.gaeul.allin。
SDK：min 24 / target 34 / compile 37；AGP 9.1.1 / Gradle 9.3.1 / JDK 17。

## 已完成

- 稳定渠道 sdkmanager --list --channel=0 列出 platforms;android-37.0 revision 2；本地包 codename 为空、PreviewSdkInt=0，Google 仓库元数据为 channel-0。
- clean offlineTests assembleDebug assembleRelease 成功；保留原9套并增加4套，共13套测试。
- #2：修复前无key、非空供应商列表的裸模型请求500；修复后Chat/Messages/Responses在兜底开关开关两种状态下返回400，未知供应商404，错误type为gateway_error。
- #3：参考Magpie的共同消息表示及安全原生路径。离线mock覆盖协议双向JSON/SSE、函数工具、图片、refusal、reasoning、鉴权、guard、中间件、隐私还原、流异常。
- 正式签名Gaeul，v2/v3验证通过；证书SHA-256：34618e7f2f748fb81c4a524f445233a35344c29e39f289f4f4e8e9e9e8442443。
- Android 16/API36真机由1.1.1覆盖安装1.2.0成功；安装前后配置（含已保存凭据）整体SHA-256一致，未输出凭据。
- target34的dataSync前台服务类型0x1，端口3425正常监听；通知权限已有授权。
- 已配置WorkBuddy/auto真实对话：Responses JSON和SSE、Chat JSON、Messages JSON均有效200；unknown-bare-model为400。
- 停止GatewayService后模拟BOOT_COMPLETED广播，服务恢复为前台dataSync并重新监听3425。测试时应用进程位于前台，因此不等价于真实重启后的后台豁免验证。
- 裸模型未知名兜底默认关闭；原有已列出的裸模型匹配保留。首次向导未完成时非回环请求403，完成后沿用原局域网鉴权。

## 正式APK

文件：AllIn-1.2.0-Gaeul-v4.apk，2,492,983字节。
SHA-256：246f3554cf61d84acac6061a50397be81caaab0afceb66ee9b904fea4998a007。
手机Download交付文件和GitHub Release使用同一份APK；电脑端请验签并核对哈希后覆盖安装。

## 电脑端待交叉验收

- Android14/API34模拟器：验签→覆盖安装→凭据保留→三协议冒烟与回归。
- 本机没有已配置Raccoon账号；验收要求的Raccoon模型真实Responses补全留给电脑端，手机只验证了WorkBuddy真实上游和本地mock。
- 通知运行时请求代码已存在并保留；真机已有授权，本轮未撤销用户授权来重新触发弹窗。API34须检查首次授权与拒绝路径。
- 真正重启或后台状态BOOT_COMPLETED启动前台服务须由API34模拟器验证；没有重启用户手机。
- target35+暂不启用。specialUse切换设计与dataSync超时/开机限制见sdk.md；尚未实现或验证切换。
- Codex/Copilot真实认证与线上特殊行为仍需各自账号验证，离线mock不代表所有上游均已验收。
