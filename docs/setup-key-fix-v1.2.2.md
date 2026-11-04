# 首次向导密钥修复（1.2.2 / versionCode 6）

## 根因

全新安装时默认配置使用 magpie。旧向导第一步生成随机候选口令，但点击下一步仅切页，直到第三步才提交密钥；系统权限页造成Activity/WebView重建时，未保存候选丢失。除此之外，供应商全量配置的旧server.authKey快照可覆盖已保存的新密钥；旧loadAuthKey响应可能覆盖页面显示，原生共享密钥缓存未及时失效。专用改密钥接口还忽略了写盘失败。

## 修复行为

- 第一页按钮明确为“应用密钥，下一步”，成功持久化后才前进。第三步仅完成向导，保留第一步已应用的密钥。
- 页面重建恢复已保存的非默认口令，并同步接入示例、显示和当前认证状态。修订号阻止旧密钥读取结果覆盖新值。
- 写入过程中禁用输入、复制、换一个、保留当前及导航，重复点击不会重复提交或把未应用值当作当前值。
- 供应商保存不携带server快照；后端通用配置保存始终保留权威authKey/setup。更改密钥或完成向导须走专用端点，不能通过旧快照回退。
- 改密钥和完成向导使用严格临时文件写入/fsync/rename，成功后才发布内存配置；失败返回500并保留原状态。
- 成功持久化、加载配置或切换数据目录时，使原生密钥缓存失效。
- 用户显式选择“保留当前口令”仍有效；可能保留magpie，界面会提示，不将它伪装成应用了随机口令。

## 验证

- 全量 clean offlineTests setupKeyHttpTests assembleDebug assembleRelease lintRelease通过：17个JVM测试套件、前端Node回归、全新配置真实HTTP集成均通过。Lint零Error/Fatal、93 Warning。
- setupKeyHttpTests在空临时目录用实际NanoHTTPD和GatewayServer启动；Node执行真实向导函数，经真实HTTP验证生成→首步写盘→页面重建→旧快照保存→完成向导→模拟远端鉴权→后端从磁盘重载。完成后默认magpie返回401，新密钥返回200。
- 覆盖写盘失败、缓存失效、数据目录切换、重复点击、保存中操作、旧异步读取、手动编辑/保留当前和HTTP500。旧16套Java测试源不改。
- CI保留原离线测试、Debug构建/上传，并新增Node向导/路由及真实HTTP集成测试步骤。
- Android16真机1.2.1覆盖安装1.2.2成功；配置和凭据指纹一致，target34 dataSync前台服务及3425监听正常。真实WorkBuddy Responses JSON/SSE、Chat和Messages均有效200。
- Gaeul正式证书v2/v3验签通过，com.gaeul.allin、min24/target34/compile37。交付APK与安装APK哈希应一致。

## 交付与限制

APK：AllIn-1.2.2-Gaeul-v6.apk，2,497,047字节。
SHA-256：f70197c74a8bb8f9bf47311abfc3085fa38c34c7e9f52c4768092d11ba4bb633。

未清除用户真机数据，也未替换用户当前密钥。全新安装逻辑通过真实HTTP/临时磁盘集成验证；完整Android WebView首次安装、权限页系统回收及API34模拟器仍交电脑端用同一份APK验收，不把隔离测试称为真实手机首装验收。
