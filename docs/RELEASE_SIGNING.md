# All In 正式签名构建

更新：2026-11-12。私密签名材料不得提交到本仓库或打包进 APK。

本机的持久项目记忆：`~/.config/alma/memory/projects/all-in-signing.md`。该文件只记录私密资料位置、使用方法和公开证书指纹，不含密码或私钥。

签名包在源码仓库之外，含已审阅的 `build-signed.py` 和 `START-HERE.md`。在受信任的本机环境运行：

```text
python <私密签名目录>/build-signed.py <All-in源码目录> --android-sdk <Android SDK目录> --offline
```

要求 JDK 17、Python 3、Android SDK 37。脚本读取私密密码文件，仅将 MAGPIE_KEYSTORE / MAGPIE_KEYSTORE_PASS / MAGPIE_KEY_PASS / MAGPIE_KEY_ALIAS 传给本次 Gradle 子进程；不要在命令行、源码、记忆或日志粘贴这些值。正式构建不要传 `-PunsignedRelease` 或使用隔离调试包。

正式包名：`com.gaeul.allin`。预期签名证书公开 SHA-256：`34618e7f2f748fb81c4a524f445233a35344c29e39f289f4f4e8e9e9e8442443`。

先检查 APK 签名、包名、版本号，再做 `adb install -r`。禁止先卸载或清数据，禁止用降级参数掩盖版本错误。签名一致仅证明覆盖安装前提，不代表所有供应商或设备已完整验收。
