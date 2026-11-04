# 配置替换跨平台修复（1.2.4 / versionCode 8）

## 修复

配置持久化统一使用 AtomicConfigFile：同目录唯一临时文件写入、flush/fsync、关闭后替换，成功后发布新配置并使密钥缓存失效。普通保存和严格保存使用同一底层实现，移除普通保存中直接截断目标文件的复制兜底。

Android 使用 Linux 文件重命名的替换行为，包含 API 24/25；失败时保留旧文件并返回错误。桌面环境在 rename 失败后使用 Files.move(REPLACE_EXISTING, ATOMIC_MOVE)。NIO 文件 API 引用隔离在仅桌面分支使用的内部类，Android 可见主类的编译字节码没有 java.nio.file 引用。

Windows 并发读取时，目标可能因共享句柄短暂返回 AccessDeniedException；只在源和目标均为普通文件时作有界重试（每次10ms、最多约490ms等待）。永久拒绝、不支持原子移动或其他失败不会回退为先删除目标或复制覆盖。

## 测试与CI

- 保留 SetupKeyOfflineTest 无参数离线测试和 setupKeyHttpTests 的真实HTTP集成测试。两者覆盖不同路径，没有删除测试掩盖失败。
- 新增 AtomicConfigFileOfflineTest，18套offlineTests全部执行。覆盖已存在文件反复替换、强制桌面分支、模拟Android重命名失败、不覆盖目录、失败临时文件清理、路径空格及并发读取完整配置。
- 并发测试用latch确认读线程已执行，避免零次读取假通过。真实Windows CI首次暴露共享句柄拒绝后加入有界重试，保留原断言。
- HTTP fixture在Windows启动node.exe，目录枚举流关闭后再删除临时文件。
- CI为ubuntu-latest/windows-latest双平台，Java17/Node22/SDK37，均运行离线、三项dashboard Node回归、首装HTTP集成及Debug构建/上传。SDK初始化只安装platform-tools，避免旧action默认安装已下架tools包；两平台产物名称不同防冲突。
- 本地 clean offlineTests setupKeyHttpTests assembleRelease lintRelease通过；此前同源Debug构建通过。Lint零Error/Fatal、94Warning。

## Android验证与交付

Android16覆盖安装成功，配置和凭据指纹保持；真实 /api/config 对已存在配置替换返回200，前台dataSync及3425监听正常。WorkBuddy/auto Responses JSON/SSE、Chat和Messages真实回归通过。Gaeul v2/v3证书指纹保持。

最终APK：AllIn-1.2.4-Gaeul-v8.apk，2,498,651字节。
SHA-256：a8bab0a94147a50f1da1ea3360ef47c4e78a93e1d287d358b52056f02ae3e603。
包名com.gaeul.allin；versionCode8；min24/target34/compile37。Release使用与手机安装相同的APK，并附.sha256。

## 验收边界

Android7兼容性通过代码分支、字节码隔离和Lint检查，没有声称已经在API24/25实机运行。电脑端仍需对同一正式APK做API34覆盖安装、面板、凭据保持和业务回归。

普通保存的部分历史调用方会先原地修改配置并忽略失败返回值；本次修复保证目标磁盘文件不会被复制兜底截断，但没有整体重构全部历史配置事务。密钥/向导等严格保存路径继续保持保存失败不发布内存状态。

issue #2的空pid判空、三协议非500回归，以及已实现的Responses接口保持原样，本版不删除或重做这些已完成改动。
