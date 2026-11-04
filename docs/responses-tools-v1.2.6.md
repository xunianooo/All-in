# Responses 空字符串工具调用修复（1.2.6 / versionCode 10）

## 复现与原因

v1.2.4和v1.2.5的非流式Chat→Responses转换收到合法工具回复 content="" 时，字符串分支因isEmpty跳过，随后被不支持内容分支拒绝；/v1/responses因此502，而同一mock上游经/v1/chat/completions为200。该问题不代表所有工具调用或流式/原生Responses路径都失败。

新增ResponsesEmptyContentOfflineTest先在旧编译产物上复现 Unsupported response content，再做生产代码最小修复：先按String类型接收，非空才创建message项，空串继续处理tool_calls/refusal/usage。其余非法类型判断保持。

## 回归范围

- 转换函数覆盖空串、JSON null、缺失content、非空正文、空内容数组＋两个工具，检查call_id、顺序、名称、含中文的arguments、模型名和usage。
- 空串无工具及空串＋refusal不报错，不伪造空message。
- 数字、布尔、对象、不支持的内容数组仍拒绝。
- 使用真实本地ServerSocket mock上游，调用完整GatewayServer /v1/responses，空串＋两个工具现在200并含两个function_call，tools请求正确转发到Chat上游；同一回复走Chat入口仍200/tool_calls。
- 原18套测试源不改，新增1套并接入offlineTests，合计19套；首装真实HTTP、3项Node回归保留。首页Gaeul/GitHub卡片、许可、双平台CI保持。
- 本地clean offlineTests setupKeyHttpTests assembleDebug assembleRelease lintRelease通过。Lint 0Error/Fatal、94Warning。
- Android16覆盖安装与冷启动成功，配置凭据整体指纹一致；实际提供的首页仍含作者与项目链接。

## 正式包与电脑验收

AllIn-1.2.6-Gaeul-v10.apk，2,500,360字节。
SHA-256：4e438daeb4e135d01702ec7306ebccdbd295bc4b2cbd808c2b238f308cbe0224。
Gaeul证书v2/v3，com.gaeul.allin，min24/target34/compile37。

电脑端请使用同一APK复验原Raccoon工具请求，检查/v1/responses为200/function_call及同请求Chat工具调用；再做API34覆盖安装/凭据保持回归。本地mock复现不等于所有真实供应商都已验收。
