# Debug APK 构建记录

日期：2026-10-14。分支：`codex/fix-black-video-thumbnails`，基于 `origin/personal`。

## 本机环境

| 项目 | 检查及处理结果 |
| --- | --- |
| JDK | 已有 Temurin 17.0.19；构建进程使用 JDK 17，不更改系统默认 JDK 25 |
| 测试 JDK | 在工作区下载并校验便携 Temurin 11.0.32.1，仅用于旧版 Robolectric；系统 Java 配置不变 |
| Android SDK | 使用已有的 `D:/DevData/Android/SDK` 和 Android API 36 |
| Build Tools | 补装项目指定的 36.0.0，保留其他版本 |
| SDK 命令行工具 | 从 Google 官方下载，校验 SHA-256 后安装到 SDK 的 `cmdline-tools/latest` |
| Gradle | 下载并校验 9.3.1，生成本地 Gradle Wrapper，配置官方分发地址及 SHA-256 |
| Android Gradle 插件 | 由 Gradle 解析项目指定的 9.1.1 |
| Git | 已安装；构建过程中未使用强制推送、硬重置或合并上游 |
| Node.js/npm | 使用已有的 Node.js 24.20.0 / npm 11.19.0 |
| NDK | 未安装；这次 Debug 构建使用预编译播放库，成功打包，原生库调试符号保留 |
| 本地配置 | 创建忽略的 `local.properties`，设置现有 SDK 路径 |

本地 Gradle 9.3.1 位于 `build/build-setup/gradle-9.3.1/`。Gradle Wrapper 和构建缓存均不作为个人功能源码提交。

## 构建依赖

- `libvlcjni` 使用项目锁定的提交 `0b8dc65efb203c86a0476bc337ddd99ecf1c0ef6`。仅初始化依赖目录，本次未编译 VLC 内核。
- Debug 的播放库和媒体库使用项目指定的 Maven 依赖：LibVLC 3.7.7、medialibrary 0.13.24。
- Remote Access 使用项目锁定的提交 `bb1f07bbfca97c3fcff59ef5d4799ee87a618134`，执行 `npm ci`，然后设置 `VUE_APP_VLC_PORT=android` 并运行 Vue CLI 构建。Android 打包任务将生成的 `dist` 资源复制进 APK。
- Remote Access 嵌套依赖仓库加入本机 `.git/info/exclude`，防止误作为子模块提交；未改变项目的发布或签名配置。

## APK

交付文件：

```text
build/apk/VLC-Android-3.7.2-Beta-3-black-thumbnail-fix-debug.apk
```

原始构建输出：

```text
application/app/build/outputs/apk/debug/VLC-Android-3.7.2-Beta-3-debug-all.apk
```

两者内容相同，大小为 **133,907,658 字节（约 127.7 MiB）**。

```text
SHA-256: 06f72fd790df7b37b5791fabc068e8eeac999eb1e2ec978f60c970294d212f74
```

- 应用包名：`org.videolan.vlc.debug`。
- 版本：3.7.2 Beta 3，版本号 13070130。
- 最低 Android API：17；目标及编译 API：36。
- 包含 ABI：`arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64`。
- `apksigner verify` 通过，使用项目已有的 Android Debug 签名配置。
- `zipalign -c -P 16 4` 通过；这是 APK ZIP 对齐校验，不代表已在 16 KiB 页设备上验证运行。
- 检查 DEX，确认包含 `VideoThumbnailExtractor` 和 `video-thumbnail:v2:` 缓存标记。
- 检查打包内容，确认包含 Remote Access 首页以及 ARM64 的 LibVLC、媒体库原生库。
- `assembleDebug` 成功，首次构建用时 12 分 55 秒。完整日志在 `build/build-setup/assemble-debug.log`。

源码修改目前仍保留在工作区，APK 由当前工作区构建。设备播放、媒体库、文件访问及 OriginOS 6 上的黑帧样本回归仍需连接手机验证。

## 缩略图测试

- 此前独立验证环境中的 26 项逻辑回归通过，其中两项使用 FFmpeg 实际解码的合成 MP4 帧。
- 本次新增的 `VideoThumbnailExtractorTest` 和 `ThumbnailsProviderTest` 共 **12 项测试通过**，JUnit 输出 `OK (12 tests)`。日志位于 `build/build-setup/thumbnail-tests-junit11.log`。
- 使用真实项目编译结果、项目已有的 Robolectric 4.3.1 / MockK 和 Android API 28 的 Robolectric SDK。缓存图片读取边界使用受控 Bitmap，覆盖缓存判断、旧黑缓存修复、各尺寸失效、自定义封面、写盘失败及回退。测试不模拟手机的视频解码器，也不代替真机回归。
- 常规 Gradle 单元测试任务会先编译全部上游测试，当前这些旧测试有缺失辅助类和接口签名变化导致的编译错误。保留上游测试源码，使用忽略目录里的临时入口仅编译本次新增的两个文件。
- 旧版 Robolectric 在 JDK 17 上无法设置其内部静态字段；AGP 9.1.1 又固定了 Gradle 测试任务的启动 JDK。因此由 Gradle/JDK 17 编译、导出真实测试类路径，再用便携 JDK 11 的 JUnit 直接执行。没有升级项目依赖或改变 APK。
- Windows 的测试夹具保留 Android 缓存路径分隔符，并在每个用例开始时清理提供器的静态缓存目录状态；生产代码不受这些测试调整影响。

本机复现命令（已准备好的临时测试目录与 JDK 保留在 `build/build-setup`）：

```powershell
$env:JAVA_HOME = 'C:/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot'
& '.\build\build-setup\gradle-9.3.1\bin\gradle.bat' --no-daemon --max-workers=4 --init-script build/build-setup/thumbnail-tests-only.gradle :application:vlc-android:exportThumbnailTestClasspath
$testJdkPath = (Get-Content build/build-setup/test-jdk11-home.txt -Raw).Trim()
& (Join-Path $testJdkPath 'bin/java.exe') '@build/build-setup/junit11.args'
```

## 后续重新构建

在项目目录的 PowerShell 中执行：

```powershell
$env:JAVA_HOME = 'C:/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot'
.\gradlew.bat --no-daemon --max-workers=4 :application:app:assembleDebug
```

Wrapper 首次使用时可能会将已校验的 Gradle 9.3.1 下载到本机 Gradle 缓存。也可以直接使用已经下载的分发目录：

```powershell
& '.\build\build-setup\gradle-9.3.1\bin\gradle.bat' --no-daemon --max-workers=4 :application:app:assembleDebug
```

## 安装与验证

将交付 APK 传到手机并点击安装，或者在开启 USB 调试、授权电脑后执行：

```powershell
& 'D:/DevData/Android/SDK/platform-tools/adb.exe' install -r '.\build\apk\VLC-Android-3.7.2-Beta-3-black-thumbnail-fix-debug.apk'
```

此测试版使用独立包名，可与官方 VLC 同时安装。打开新安装的测试版、授权媒体访问并重新扫描视频。黑帧合成样本与具体回归步骤见 `docs/black-video-thumbnails.md`。
