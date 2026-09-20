# 一加13澎湃震动增强

适用于一加13、OnePlus ODM/vendor、移植版小米澎湃OS的 LSPosed 模块。

v0.2.0-test 作用域：

- `android`：在 System Framework 中补报小米私有效果支持，并在下发 HAL 前映射到一加13已有波形；
- `com.android.systemui`：修复通知长按、清空通知、指纹成功和指纹失败的触发链。

全局桥覆盖返回手势、趣味拟物、FOD 动画、正负反馈、锁定、待办完成、截图、NFC、通知/进程清理、充电、卸载和常用控件反馈等 UI 效果。未声明支持小米铃声和游戏武器效果，避免向一加 HAL 下发没有稳定替代物的长效果。

若配套安装震动桥 v0.2.5-test，159、162、163、167、169、171、192、210 会直接播放小米原始 RTP；APK 会检测 ODM 文件，文件不存在时自动使用一加近似波形，不会向 HAL 下发空 ID。

趣味拟物页面经 Settings smali 与 SRT 时间轴确认会使用 159、162、163、167、169、171，新版资源还使用 210。设置应用本身无需加入 LSPosed 作用域，它通过 system_server 返回的能力表即可看到这些效果。

模块不覆盖 `MiuiSystemUI.apk`，也不替换现有 vibrator HAL。安装或升级后必须完整重启，确保 `android` 作用域在 system_server 启动时生效。
