# Location

基于 Xposed 的虚拟定位模块。通过 hook 系统定位服务，把定位结果替换成指定坐标，支持地图选点、摇杆移动、GNSS 信息模拟与路线巡航。

> 仅供学习与交流使用，请勿用于任何非法用途。

## 功能

| 功能 | 说明 |
|---|---|
| 地图选点 | 百度地图 POI 搜索、逆地理编码、保存常用位置 |
| 位置模拟 | 悬浮摇杆实时移动，方向由摇杆控制，速度可调 |
| GNSS 模拟 | 卫星星图、卫星数量、NMEA / AGPS 开关 |
| 路线模拟 | 按预设航点自动巡航，可暂停 / 恢复 |
| 设置 | 速度、海拔、精度、上报间隔、最小卫星数、调试日志等 |

## 环境要求

- **系统**：Android 8.0（API 26）及以上
- **框架**：已安装 LSPosed（模块声明 `xposedminversion=93`，编译依赖 Xposed API 82）
- **作用域**：`android`、`com.android.phone`、`com.android.location.fused`、`com.xiaomi.location.fused`、`com.oplus.location`
- **权限**：定位、悬浮窗（`SYSTEM_ALERT_WINDOW`），Root 可选（仅传感器 hook 需要）

## 构建

构建环境：

| 项 | 版本 |
|---|---|
| AGP | 9.3.3 |
| Gradle | 9.5.0 |
| JDK | 25 |
| compileSdk | 37 |
| minSdk | 26 |
| targetSdk | 35 |
| NDK | 26.1.10909125 |
| CMake | 3.22.1 |

**Android Studio**：`Build → Build Bundle(s) / APK(s) → Build APK(s)`

**命令行**（`java` 不在 PATH 时需要先指定 JDK）：

```powershell
$env:JAVA_HOME = 'D:\Program Files\Android\Android Studio\jbr'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat :app:assembleDebug
```

产物：

```
app/build/outputs/apk/debug/app-debug.apk
```

安装：

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

> `release` 未配置签名，`assembleRelease` 只会产出 `app-release-unsigned.apk`，需要签名请用 Studio 的 `Generate Signed Bundle / APK`，或在 `app/build.gradle.kts` 中添加 `signingConfigs`。

## 模块结构

```
app/          应用主体：UI、地图、摇杆、路线、设置
xposed/       Xposed 模块：定位/基站/WLAN/传感器 hook + native 层（Dobby）
system-api/   android.location 隐藏 API 桩（compileOnly）
nmea/         NMEA 语句模型，供 GNSS 语句注入使用
```

核心类：

- `moe.wusan.xposed.FakeLocation` — Xposed 入口，按包名分发 hook
- `moe.wusan.xposed.RemoteCommandHandler` — 接收 App 下发的命令（模拟开关、移动、配置）
- `moe.wusan.xposed.BaseLocationHook` — 坐标替换、NMEA 注入、上报速度计算
- `com.wusan.location.service.MockServiceHelper` — App 侧命令封装
- `com.wusan.location.ui.viewmodel.MockServiceViewModel` — 摇杆 / 路线两条移动循环

App 与模块之间通过 `LocationManager.sendExtraCommand("portal", ...)` 通信，使用随机 key 校验调用方。

## 使用

1. 安装 APK，在 LSPosed 中启用模块并勾选上面列出的作用域；
2. 打开 App，授权定位与悬浮窗权限；
3. 底部「功能」页选择：地图选点 / 位置模拟 / GNSS 模拟 / 路线模拟；
4. 进入对应页面启动模拟；
5. 若目标应用仍显示真实位置，强制停止目标应用后重新打开（修改的是 `system_server`，必要时重启手机）。

## 常见问题

**改了设置不生效？**
部分设置项（上报间隔、最小卫星数）需要重启 App 才生效，页面会给出提示；速度、海拔、精度为即时生效。

**模块代码改了但行为没变？**
Android Studio 默认开启部署优化，可能导致模块没有真正更新。在 `Run → Edit Configurations → Miscellaneous → Installation Options` 勾选 *Always install with package manager*，或使用 `gradlew installDebug` 安装。

**目标应用显示的速度与实际不符？**
`Location.getSpeed()` 由模块上报，移动中等于设定速度，静止时为 0；若应用自行按坐标差分计算，则受坐标抖动（精度设置）影响。

**定位没有生效？**
确认作用域是否勾选完整，尤其是 `android`；部分 ROM 需要重启设备。

## 步频模拟

设置页提供独立的“步频模拟”开关及 1～300 步/分钟参数，默认关闭、默认步频 160。
开启后按固定步频运行，与定位或路线模拟的启动、暂停分别控制。

1. 安装更新后的 APK，在 LSPosed 的模块作用域中勾选需要测试的目标应用。
2. 强制停止并重新打开目标应用，让新 Hook 加载。
3. 在 Location 设置中打开“步频模拟”，设置所需步频；配置通常在 1 秒内同步。
4. 关闭开关后停止生成事件，恢复该应用的原始计步传感器事件。

此实现覆盖 `TYPE_STEP_DETECTOR`（每步值 1）和 `TYPE_STEP_COUNTER`（进程内连续累计），
仅对目标应用成功注册的现有计步传感器生效，保留系统的运动权限检查。
没有计步传感器、未授予运动权限、仅通过加速度计或外部服务计算步频的应用不适用。
累计值不跨目标进程重启保存；切换真实/模拟数据源时建议重开目标应用，重新建立计数基线。
计时使用单调时钟；线程长时间停顿或设备休眠后不会集中补发丢失的步数。

配置通过只读 ContentProvider 同步，仅包含开关和步频，读取失败时保留原始传感器事件。
Android 11+ 首次读取受包可见性限制时，会向模块发送显式请求，由模块授予该配置 URI 的只读访问权限，再重试读取。
实现不依赖原先未完成的 native 传感器 Hook，也不改变加速度计、陀螺仪事件。
Android/OEM 内部传感器接口存在差异，需在具体设备上验证注册、取消、后台计时和开关行为。

## 已知限制

以下功能目前尚未完成或未接入：

- 原 native 传感器模拟仍未完成；新增步频模拟通过目标进程的 Java Hook 独立实现，仅覆盖现有计步传感器；
- GNSS HAL 层 hook（`IGnss$Stub`）已注释，仅框架层生效；
- 基站模拟仅保留 `getAllCellInfo` 与 `getActivePhoneType`，CDMA / GSM CellIdentity 相关 hook 已注释；
- NMEA 仅伪造 GGA / GNS / RMC，GSA / GSV / VTG 直接放行；
- GNSS 页的「随机化卫星信息」「选中卫星详情」尚未实现。

## 许可证

[Apache License 2.0](LICENSE)
