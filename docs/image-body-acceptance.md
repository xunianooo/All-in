# 图片与入站请求体完整性：本地验收

本批以当前未同步工作树为基准，保留此前Q1/R3/加密备份/Q2/MiniMax/生产信任资产。没有远端恢复、提交、推送、改版本或Release。

## 行为

- 共享ImageContent严格验证可转换图片URL/dataURI/base64语法，不下载或重编码图像，不默认PNG。Chat工具图到Anthropic有序保留；Chat工具非字符串输出不能转换到Responses时明确400，避免字符串化丢图。非auto图片detail不可表达时拒绝。
- 原生Anthropic图片中签名、cache_control、未知source等通过native投影+requiresNative+最终等价检查保护，不能投影成普通Chat后误发。未知内容不代表各供应商支持。
- ContextGuard保留图片/关联工具回合/最新user；文字裁剪仍显示统计，无法满足预算则413。文本Token估计排除图片载荷，imageTokenEstimateKnown=false；imageBytes仅编码字符串或URL字节，完整body字节上限仍有效。
- IngressBody严格CL、完整body、UTF8和上限，raw guard在Nano合并头前拒重复CL/TE、CL+TE、折叠头及超过8192字节头。正常keepalive/分片多请求有TCP回归。默认body8MiB，绝对配置上限64MiB，不承诺峰值内存。
- 入站chunked明确501（需Content-Length），压缩body非identity415；其他格式/短体400、超时408、超限413，失败关闭连接。响应流压缩功能保留。

## 证据

64套JVM和注册HTTP通过；15Node通过。首次Debug公开JSON API不兼容已修，已知增量JSONException问题以强制完整编译收口，最终Debug/Release和lint Error0/Fatal0/Warning96。Android正常Context的隔离Gateway真实start后7组全部PASS，包含长于1MiB工具图片SHA256/顺序、native签名图消息等价、body错误零上游、rawTCP。测试断言适配JSON斜杠转义及完整错误响应后reset，未放松产品保护。生产保配置安装、7API健康、规则缓存1.0.1、重复CL实际400；未作真实供应商推理。

详细本地证据在维护工作区IMAGE-BODY-HANDOFF.md与artifact-local-image-body.json；电脑新包尚待验收。旧Android实际ART、真实图像供应商能力与高并发内存、跨设备/断电不在已完成证据范围。
