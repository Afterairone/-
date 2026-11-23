# 浮光时钟

浮光时钟是一款 Android 悬浮时钟，支持毫秒显示、NTP 和 UTC 校时、倒计时提醒，以及通过系统无障碍服务执行定时单击。当前版本为 **666.666（隐藏后台版）**。

## 安装

从 [GitHub Releases](https://github.com/Afterairone/-/releases/latest) 下载 APK，支持 Android 8.0 及以上版本。

- 开启悬浮时钟时，按系统提示授予显示在其他应用上层的权限。
- 定时单击需要在系统设置里开启「浮光时钟 · 定时单击」无障碍服务。
- 「定时单击」页面底部的「隐藏后台」开关可以从最近任务中隐藏应用卡片，桌面图标仍可打开应用。

## 构建

准备 JDK 17 和 Android SDK Platform 37，在 Android Studio 中打开此仓库，或在项目根目录运行 Gradle Wrapper。

```sh
./gradlew :app:assembleDebug :app:lintDebug
```

在 Windows PowerShell 中运行 `.\gradlew.bat :app:assembleDebug :app:lintDebug`。调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

Release 下载包使用原构建环境的 Android 调试证书签名。如果重新构建时使用了不同的证书，Android 会拒绝覆盖安装。
