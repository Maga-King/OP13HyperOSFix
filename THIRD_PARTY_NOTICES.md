# 第三方与合并来源说明

## HyperCeiler

本项目的通知渠道设置和 Android 包管理器相关核心修补行为参考并改编自 HyperCeiler。

- 项目地址：https://github.com/ReChronoRain/HyperCeiler
- 使用的提交：`d92559339e772f1c153c19f0da48a04502f050c3`
- 原版权声明：HyperCeiler Contributions, 2023-2026
- 许可证：GNU Affero General Public License v3.0

改编实现根据一加13测试系统上的 Android 17 和澎湃 OS4 类结构进行过调整。完整 AGPLv3 文本保留在根目录 `LICENSE`，不以中文概述替代原许可证。

## 下载服务防杀

来自用户提供的 `DownloadProvider_BootGuard_LSPosed_v1.1_Source_20260820.zip`。

来源快照保留在 `upstream/downloadprovider_bootguard_v1.1`。运行副本保留各层防护，并收窄最终崩溃上下文判定，避免拦截真正的崩溃处理退出。

## 一加13澎湃触感

小米私有效果桥、RTP 播放器、Root 辅助程序、波形转换工具和资源来自用户提供的 `一加13澎湃震动增强-OS4模块和源码.zip`，原模块版本为 `0.8.8-os4-test`。

来源快照保留在 `upstream/oneplus13_hyper_haptics_0.8.8`。综合工程中的运行副本使用本模块包名和 APK 定位逻辑，并包含后续兼容与调校修改。具体历史见综合修复记录，不能把运行副本视为未修改的上游文件。

## ColorOS 钱包兼容

来自用户提供的 `COS_WALLET_FIX_Source_v0.9.4_20260830.zip`。完整核心代码保留在 `app/src/main/java/local/mio/coloroswalletcompat/HookEntry.kt`，由综合模块入口委托调用。

原始包哈希、源码哈希、作用域和完整合并检查见 `docs/COS_WALLET_FIX_v0.9.4_MERGE_AUDIT.md`。

## 其他合并代码与依赖

设置页面、WLAN 兼容、CNE 防护等已有合并代码继续保留，来源与改动以源码注释和历史修复记录为准。用户提供的工程来源和外部开源项目引用是不同概念，本说明不替来源不明的资源追加许可证。

构建还使用 AndroidX、Compose、Xposed API、DexKit 和 FlatBuffers 等依赖。本地依赖版本见 `app/build.gradle.kts` 与 `gradle/libs.versions.toml`，许可证仍以相应项目和分发包原声明为准。

厂商 SO、KO、RTP 波形和其他预编译资源按现有工程保留供构建使用，其版权归原权利人。保留这些文件不代表本项目拥有其版权，也不代表 AGPL 重新授权了这些资源；若后续改为公开分发，应逐项核实授权范围。
