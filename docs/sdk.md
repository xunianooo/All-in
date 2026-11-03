# Android SDK 与前台服务

Gradle 构建使用 JDK 17、Android Gradle Plugin 9.1.1、Gradle 9.3.1 和稳定版 Android SDK Platform 37.0（`compileSdk 37`）。应用仍支持 Android 7.0 起（`minSdk 24`），目标版本为 Android 14（`targetSdk 34`）。API 37.1/37.2 超出 AGP 9.1.1 的支持范围；不要把 beta 平台当作稳定平台。

Android 13 及以上的通知权限由权限面板中的通知操作触发系统运行时申请；已授权、低版本或调用失败时会退回系统设置。前台服务当前声明 `dataSync` 和相应权限，Android 14 要求的类型与权限均已声明。`BootReceiver` 在网关启用时尝试启动服务，但实际启动仍受系统后台限制、电池策略及用户设置影响；不保证所有设备开机自启。锁屏启动广播需要 direct-boot 适配，目前接收器没有声明 direct-boot aware，不能保证解锁前启动。

未来若要将 targetSdk 升到 35 或以上，须先处理 Android 15 的限制：后台 `dataSync` 在 24 小时内有累计六小时限额，且不能由 `BOOT_COMPLETED` 接收器启动。不能仅提高 targetSdk 后保持现状。备选设计是在确认网关实际用途符合系统和发布渠道政策后，显式切换到 `specialUse`：同时更改 manifest 服务类型、声明 `FOREGROUND_SERVICE_SPECIAL_USE` 和解释用途的 subtype 属性，并将 `GatewayService.startForegroundWithType` 传入的类型从 `dataSync` 改为 `specialUse`；验证开机启动、后台限制及用户可见行为。该方案需单独实现和测试，当前未启用，也不保证所有后台启动获准。否则应重新设计为适合的调度任务，并处理超时回调与启动失败。

参考：

- [AGP 9.1 兼容版本](https://developer.android.com/build/releases/agp-9-1-0-release-notes)
- [通知运行时权限](https://developer.android.com/develop/ui/views/notifications/notification-permission)
- [前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Android 15 目标版本行为变更](https://developer.android.com/about/versions/15/behavior-changes-15)
- [前台服务超时](https://developer.android.com/develop/background-work/services/fgs/timeout)
