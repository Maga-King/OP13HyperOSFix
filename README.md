# 一加13澎湃综合修复

OP13HyperOSFix 是面向一加13移植澎湃系统的 LSPosed 综合修复模块。

这个仓库保存现有工程的 **1.58.0-coloros-wallet** 版本，versionCode 为 **75**，包含完整的 ColorOS 钱包修复。它不是 1.56.3 的旧版备份，也不是删掉钱包后的精简版。

## 包含内容

- SystemUI 指纹动画、指纹速启、AOD 和输入事件适配
- 传感器、抬起亮屏、步数与防误触相关适配
- 触感、RTP 播放与强度调节
- 充电识别和显示、手机管家电池页面适配
- 蓝牙 LHDC 兼容处理
- 通知设置、设置页面和核心框架相关修复
- 游戏触控、旁路供电、内置调度与性能监视
- IFAA 指纹支付、下载服务 MTP 防杀、CNE DataCall 崩溃保护
- 云备份与换机兼容
- ColorOS 钱包、NFC 和安全元件兼容

上面是本版本的代码范围，不代表适用于所有 ROM 或内核。项目包含 system_server Hook、Root 辅助程序和内核模块，换系统或 OTA 后仍需核对运行状态。不要在其他机型上直接套用。

## 编译

需要 JDK 17、Android SDK 37、Build Tools 37.0.0 和 NDK 28.2.13676358。Gradle Wrapper 已随仓库提供，版本固定为 9.3.1。

先配置 `JAVA_HOME` 和 `ANDROID_HOME`，然后在仓库根目录执行：

```powershell
.\gradlew.bat :app:assembleRelease --no-daemon
```

Linux 或 macOS：

```bash
./gradlew :app:assembleRelease --no-daemon
```

APK 输出到 `app/build/outputs/apk/release/app-release.apk`。

首次构建需要联网下载 Gradle 和 Maven 依赖。本地 JAR、AAR、SO、KO、辅助程序、RTP 波形和界面资源均随仓库提供，不需要原电脑的工作目录。

现有构建配置使用当前电脑的 debug 签名生成 Release APK。仓库不包含原作者电脑的签名密钥，因此其他电脑编出的 APK 不保证能直接覆盖已经安装的版本。不要为了解决签名冲突把私钥上传到仓库。

详细步骤见 [构建说明](docs/构建说明.md)。

## 目录

| 目录 | 内容 |
| --- | --- |
| `app/src/main/java` | Java、Kotlin、Hook 和界面源码 |
| `app/src/main/cpp` | LHDC 原生补丁和监视器采样源码 |
| `app/src/main/assets` | 辅助程序、KO、RTP 和内置配置 |
| `app/src/main/jniLibs` | 随 APK 打包的预编译库 |
| `app/libs` | 本地编译依赖 |
| `native-src` | 指纹和双击辅助程序源码 |
| `kernel-src` | 指纹事件桥和调度内核模块源码 |
| `upstream` | 已保留的来源工程快照 |
| `tools` | 调试和资源生成工具 |
| `docs` | 构建说明与合并审计 |

APK 构建会编译 `Android.mk` 声明的原生目标，其余随包程序和 KO 使用现成预编译文件。能够编译 APK，不等于这些预编译文件已经可以仅凭本仓库全部从零重建，内核模块还需要匹配的内核构建环境。

## 文档与来源

- [历史综合修复记录](一加13澎湃OS4综合修复方法.md)
- [钱包合并审计](docs/COS_WALLET_FIX_v0.9.4_MERGE_AUDIT.md)
- [第三方来源说明](THIRD_PARTY_NOTICES.md)
- [许可证](LICENSE)

历史记录中的本机路径和 APK 哈希用于记录当时的工作，不是本仓库的构建路径或本次编译产物哈希。项目保留 HyperCeiler 的来源说明和 AGPLv3 许可证；其他依赖与预编译资源的权利归各自权利人，不应把它们统一解释为已获重新授权。
