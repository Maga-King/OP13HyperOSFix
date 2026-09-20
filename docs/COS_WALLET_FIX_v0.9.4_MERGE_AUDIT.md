# COS_WALLET_FIX v0.9.4 完整合并审计

## 输入与成品

- 输入源码包 `C:\Users\a1510\Videos\COS_WALLET_FIX_Source_v0.9.4_20260830.zip`
- 输入源码包 SHA-256 `496BA9254D3E674B4F5E4A7DE3F8FFA34301500944EA62B87A55B2B837EF6264`
- 钱包核心源码 `local/mio/coloroswalletcompat/HookEntry.kt`
- 核心源码 SHA-256 `E4B181CCEAEDDC5CCB0D9C29AB0BCD55B9B0985F0F6664894CE6002BB66AF3BA`
- 综合模块版本 `1.58.0-coloros-wallet` versionCode 75
- Release APK SHA-256 `912808BD54505B1F2C090AAFD52930CA3DD4B8DD0B2C64567930A1A8A6234933`

合并后的 `HookEntry.kt` 与输入源码逐字节一致。没有把 2846 行实现拆开重写，也没有删减任何兼容分支。

## 接入方式

综合模块仍只保留一个 Xposed 入口 `local.mio.op13hyperosfix.HookEntry`。该入口持有并调用 `local.mio.coloroswalletcompat.HookEntry`，调用外层带异常隔离，钱包某个进程的适配失败不会打断综合模块的其他 Hook。

没有复制独立模块的 `assets/xposed_init` 和第二套 Xposed 元数据。若同时加载两个入口，同一方法会被重复 Hook。这两项属于独立 APK 的启动外壳，不属于钱包修复功能；完整实现已经由综合入口调用一次。

## 作用域与权限

已合入全部七个原始推荐作用域：

1. `com.finshell.wallet`
2. `com.heytap.tas`
3. `com.unionpay.tsmservice`
4. `com.oplus.eid`
5. `com.android.se`
6. `com.android.nfc`
7. `com.miui.tsmclient`

已合入原模块声明的 `oplus.permission.OPLUS_COMPONENT_SAFE` dangerous 权限。Release APK 的资源表显示总作用域 19 个，其中上述七个均存在。

## 功能映射

### ColorOS 钱包进程

- 仅在 FinShell、HeyTap TAS 与银联 TSM 进程内提供 OnePlus 13 `PJZ110 / OP5D0DL1` 软件身份和对应 ColorOS CN 属性。
- 不修改证明属性、CPLC、证书、TEE 数据、设备密钥或真实安全元件身份。
- FinShell 和 TAS 均包含 eID 服务选择、厂商门槛与门禁卡参数开关兼容。
- FinShell 额外包含 OPlus framework classpath、OSense 安全回退及锁屏卡包 60 秒连续会话。

### OPlus eID

- 仅在 `com.oplus.eid` 内把 `vendor.oplus.hardware.eid.IEidDevice/default` 的 `ServiceManager.isDeclared` 探测修正为已声明。
- 后续 `waitForService` 和 Binder 调用保持原样，不伪造不存在的 HAL。

### NFC 与 eSE 路由

- 将小米残留 eSE 路由 `0x01` 转换为 OnePlus 13 SN220T 的 NFCEE `0xC0`。
- 覆盖 Android 16 和 Android 17 的 RoutingOptionManager、AidRoutingManager、CardEmulationManager 差异。
- 保存系统原始 ISO-DEP、off-host 与 Felica 路由，退出兼容模式时可恢复。
- 使用系统原生队列、AID 缓存、commitRouting 和 applyRouting 链路提交，不循环轮询。
- 修复 Android 17 内部 TechListChooserActivity 被错误判定为不可启动的问题。

### 门禁卡运行时桥

- 接收 ColorOS 城市配置、刷卡页和智能卡广播。
- 同步当前卡 AID，避免旧动态 AID 与新 eSE 卡状态混合。
- 只对 off-host eSE 门禁卡应用 RF profile、DH85、SAK 和 NXP transit 配置。
- DH/HCE 卡不修改该组参数。
- NFC 关闭、卡片切换、模式切换和失败路径均保留快照并做精确恢复。
- 通过事件和系统 Handler 工作，没有常驻轮询。

### SecureElement 权限

- 仅对包名和原厂签名 SHA-256 同时匹配的 FinShell 与 HeyTap TAS 提供 ColorOS 等价的 ChannelAccess fallback。
- 未加入通配授权，也没有绕过其他调用者的 ARA-M 规则。

### 小米交通卡入口

- 仅在 ColorOS 钱包模式下拦截 Mi TSM 自动 RF/HCI 拉前台行为。
- 用户主动双击电源键进入交通卡时，重定向到 FinShell 卡包页。
- 锁屏连续会话只允许页面显示在锁屏上方，不解锁设备，也不跳过身份验证。

## 成品反向检查

- 原始核心源码与合并核心源码 SHA-256 完全一致。
- 原始独立工程和综合工程均使用 Temurin 17 构建成功。
- 源码共提取 71 个具名函数，独立 APK 与合并 APK 均匹配 70 个。
- 唯一没有独立 DEX 方法名的 `updateActivity` 是函数内局部函数，两边均被 Kotlin 编译器降级到三个 Activity 回调中，结果一致。
- 合并 DEX 中存在 32 个 `local/mio/coloroswalletcompat` 类，核心类、Companion、门禁卡桥和所有 Hook 回调均存在。
- 36 项关键常量和目标类检查全部通过，包括两个签名摘要、七个进程、eID HAL、NXP AIDL、路由类、SE 类、广播、AID、配置文件和锁屏入口。
- DEX 反汇编确认综合入口的委托方法实际调用了钱包 `handleLoadPackage`。
- Release manifest 确认 versionCode 75、OPlus 权限和统一 Xposed 入口。
- Release 资源表确认全部七个新增推荐作用域。

结论：v0.9.4 的运行时功能已完整合入，未发现漏函数、漏作用域、漏权限、漏常量或重复 Hook 入口。
