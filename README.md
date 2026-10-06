# Device Searcher

Android 近场设备搜寻 App。装了本 App 的设备之间互相发现，界面上用一组**箭头圆环**表达每台设备的远近与方位，不用在列表里一行行比对 RSSI 数字。

> **目录名与工程名不符**：本目录叫 `App_Direction_Founder`，而工程名（`settings.gradle.kts` 的 `rootProject.name`）是 `DirectionFinder`，应用名是 `Device Searcher`，包名是 `com.proximity.finder`。

---

## 它做什么

解决"东西/人在我附近吗、大概多远"这件事。每台设备同时做**广播**与**扫描**：广播把自己（昵称 + 信号强度）发出去，扫描收别人的。收到之后按信号强度估算距离，在屏幕上画成一组圆环。

三种发现方式可以手动切换，各自独立计时、互不干扰。切换时会把上一种停掉。

---

## 三种发现方式

| 模式 | 机制 | 关键参数 | 前提 |
|---|---|---|---|
| **BLE** | 同时 `startAdvertising` + `startScan`，两者都按自定义 Service UUID 过滤 | 广播 UUID `0000FEED-0000-1000-8000-00805F9B34FB`；1 米参考 RSSI `-59 dBm`；路径损耗指数 `2.5`；15 秒无更新即移除；**每 30 秒整套重启一次**广播+扫描；连续找不到设备时按 `2/4/8/12/20` 秒退避重试 | 蓝牙开启 + 定位权限 |
| **WiFi Direct** | `WifiP2pManager` 发现 + DNS-SD 服务注册 | 服务类型 `_device-searcher._tcp.`，实例名 `DeviceSearcher`；30 秒超时 | WiFi 开启 |
| **局域网 UDP** | 自建 UDP 广播协议，周期广播 + 监听 | 端口 `19876`；每 3 秒广播一次；20 秒超时；1 米参考 RSSI `-40 dBm`；路径损耗指数 `2.5` | 已连 WiFi 或热点 |

昵称的传递方式三种各不相同：BLE 是把 `BluetoothAdapter.name` 设成昵称（**注意这会改掉设备本身的蓝牙名**，属于副作用）；WiFi Direct 走 DNS-SD 的 TXT 记录；局域网则直接写在 UDP 报文里。

BLE 那一套每 30 秒整体重启，是因为部分机型长时间扫描会静默失效（扫不到也报不出错），重启是最省事的兜底。

---

## 距离与方位是怎么算的，以及它们不成立的地方

**距离**用对数路径损耗模型估算：

```
d = 10 ^ ((RSSI_1m - RSSI) / (10 * n))
```

`RSSI_1m` 与 `n` 按模式取值（见上表），结果钳在 `0.5–50 m`（局域网模式是 `1–50 m`）。

**这个距离只能当"远近感"，不能当尺子用。** RSSI 受遮挡、朝向、人体、机壳影响极大，同一条公式在不同环境下误差可以有数倍。代码里 `DeviceEntry.rssiHistory` 已经在保留最近 20 帧的历史（用于识别同名设备轮换 MAC 的场景），但**当前没有做多帧平滑，距离取的是最新一帧的原始 RSSI** —— 也就是说数字会跳。这是可以改进的地方。

**方位不是真实测向。** 这是本项目最容易误解的一点，源码里也写了注释：

```
// 角度仅用于散点图布局，简单均匀分布，由 RadarView 的 layoutAngle 覆盖
```

`NearbyDevice.angle` 字段按列表序号铺开（BLE 是 `index * 72°`，WiFi 与局域网是 `index * 90° + 45°`），而画的时候 `RadarView.layoutAngle(index, total)` 又用 `index * (360 / total) - 90°` 完全覆盖了它 —— **最终就是"有几台设备就把圆周等分成几份"**。圆环的指向只用来避免重叠，**不代表设备真的在那个方向**。

真要做测向得靠 UWB、蓝牙测向（AoA/AoD）或天线阵列，这台设备没有这些硬件。

---

## 近距离反馈（≤ 10 m）

距离进入 10 米以内时，切到一套强化反馈。`NearbyDevice.NEAR_THRESHOLD = 10f`。

| 反馈 | 实现 |
|---|---|
| 震动 | `HapticUtil.vibrateNear()` 用 `VibrationEffect.createWaveform` 编**按距离分四档**的脉冲串：`<2m` 四连震、`<5m` 三连震、`<8m` 双震、其余单震；振幅同时随距离缩放（`65–255`）。重复触发的间隔由 `MainActivity` 控制，收紧到 `250–3000 ms`（约 1m→300ms、5m→1200ms、10m→3000ms） |
| 箭头冲刺 | 箭头沿指向做 `4–10 px` 的前后往复 |
| 箭头发光 | 与设备色一致的呼吸发光 |
| 圆呼吸 | 在偏移方向上 `scale(1) → scale(1.1)` |
| 脉冲扩散环 | 两圈同心扩散环，像声呐 |
| 屏幕微震 | 主视觉区 ±2px 水平抖动（导航 Tab 切走时不抖） |

距离同时驱动视觉编码：`intensity = 1 - d / 50`，映射到圆的填充不透明度（`0.08–0.60`）、描边不透明度（`0.25–1.00`）、圆半径（`22–38 dp`）。**越近的圆越大、越实、越往外跳**，目的是让人一眼锁定最近的那台。

---

## 界面

主界面只有**两个 Tab**：「指示」与「我的」。`ui-design-spec-v2.md` 的设计稿里画了三个（指示 / 历史 / 我的），**历史页没有实现**。

「指示」页顶部是模式切换（BLE / WiFi Direct / 局域网）与状态摘要，中间是 `RadarView` 画布，点任意圆环弹出底部详情卡（头像、名称、大号距离数字，≤10m 时多一条震动强度条）。

「我的」页放昵称设置（持久化到 `SharedPreferences`，键 `proximity_prefs` / `nickname`）与运行日志。日志走内存环形缓冲（`LogBuffer`，上限 200 条），支持清空与一键复制到剪贴板。

---

## 技术栈与构建

| 项 | 值 |
|---|---|
| 语言 / UI | Kotlin + Jetpack Compose + Material 3（Compose BOM `2024.06.00`） |
| 最低 / 目标 SDK | `minSdk 26`（Android 8.0）/ `targetSdk 34` |
| 版本 | `versionCode 1` / `versionName "1.0"` |
| 依赖 | `core-ktx 1.13.1`、`activity-compose 1.9.0`、`lifecycle 2.8.2`、`accompanist-permissions 0.34.0`、`kotlinx-coroutines-android 1.8.1` |
| 仓库 | 腾讯云镜像优先，回退 google / mavenCentral |
| JVM 目标 | 1.8 |

构建需要 JDK 17（用 Android Studio 自带的 JBR 即可）。根目录的 `build_apk.ps1` 已经把 `JAVA_HOME` 指过去并把输出重定向到 `build_log.txt`：

```powershell
$env:JAVA_HOME = 'E:\Android Studio\jbr'
.\gradlew.bat assembleDebug
```

两点关于打包的提醒。其一，`app/build.gradle.kts` 的 `release` 块**没有配 `signingConfig`**，所以 `assembleRelease` 产出的是 `app-release-unsigned.apk`，装不上，需要先配签名或用 debug 构建。其二，`local.properties` 里是本机 SDK 绝对路径，**不应该进版本库**（该文件自己也在注释里写明了）。

---

## 通信协议

三种模式各有一套"怎么把昵称带出去"的约定，且**版本号是三套独立的**（BLE 用 Service UUID 隐含版本，WiFi Direct 的 TXT 里写 `2.0`，局域网报文里写 `4.0`）。改协议时必须同时改发送与解析两端。

| 模式 | 标识与版本 | 昵称怎么带 |
|---|---|---|
| BLE | 广播 Service UUID `0000FEED-0000-1000-8000-00805F9B34FB` | 把设备蓝牙名设为昵称，扫描侧从 `scanRecord.deviceName` 读 |
| WiFi Direct | DNS-SD 服务类型 `_device-searcher._tcp.`；TXT 记录 `app=DeviceSearcher` `name=<昵称>` `ver=2.0` | TXT 的 `name` 字段 |
| 局域网 UDP | 端口 `19876`；报文首段固定 `DeviceSearcher`，版本段 `4.0` | 报文第二段 |

局域网报文是一行竖线分隔的 UTF-8 文本：

```
DeviceSearcher|昵称|4.0|rssi
```

接收侧会校验第一段等于 `DeviceSearcher` 才认，第二段为空时回退成 `LAN_<发送方 IP 末四位>`。`rssi` 段是可选的（兼容旧版本），缺失时按 `Int.MIN_VALUE` 处理。广播目标不是只发全网广播地址，而是遍历所有网络接口逐个计算广播地址，这样在同时连着 WiFi 与热点时两边都能收到。

---

## 代码结构

```
app/src/main/java/com/proximity/finder/
├── MainActivity.kt              入口：建三个管理器、权限申请、搜索模式切换、状态文案、近距离震动循环
├── ble/BleProximityManager.kt   BLE 广播+扫描、设备表、距离估算、退避重试、30s 周期重启
├── wifi/WifiProximityManager.kt WiFi Direct 发现 + DNS-SD 服务注册
├── lan/LanDiscoveryManager.kt   UDP 广播/监听、报文编解码、广播地址计算
├── model/NearbyDevice.kt        数据模型 + 视觉映射（intensity / 半径 / 偏移 / 不透明度）
├── ui/MainScreen.kt             两个 Tab、模式切换、详情卡、日志页、底部导航
├── ui/RadarView.kt              Canvas 画圆环、箭头、脉冲环旋转扫描线，等分布局
└── util/                        HapticUtil（波形震动）、LogBuffer（200 条环形日志）
```

三个管理器都是 `StateFlow<List<NearbyDevice>>` 向外发布，`MainActivity` 用 `LaunchedEffect` 收集后交给 Compose，职责边界清楚。`onDestroy` 里三个都做了 `release()` / `stop()`。

---

## 权限

BLE 扫描在 Android 12 以下必须伴随定位权限，12 及以上改用 `BLUETOOTH_SCAN` 等细粒度权限；WiFi Direct 在 Android 13 以上如果不声明 `NEARBY_WIFI_DEVICES` 会被**静默拦截**（不报错，就是扫不到）。所以清单里同时声明了新旧两套，代码里按 `Build.VERSION.SDK_INT >= S` 分支申请。

| 分组 | 权限 |
|---|---|
| BLE | `BLUETOOTH(_ADMIN)`（≤API 30）、`BLUETOOTH_SCAN` / `_ADVERTISE` / `_CONNECT` |
| 位置 | `ACCESS_FINE_LOCATION`、`ACCESS_COARSE_LOCATION` |
| 附近设备 | `NEARBY_WIFI_DEVICES`、`NEARBY_DEVICES` |
| 网络 | `ACCESS_WIFI_STATE`、`CHANGE_WIFI_STATE`、`INTERNET`、`ACCESS_NETWORK_STATE`、`CHANGE_NETWORK_STATE`、`CHANGE_WIFI_MULTICAST_STATE` |
| 其它 | `VIBRATE` |

`android.hardware.bluetooth_le` 声明为 `required="true"`，没有 BLE 的设备装不上。

---

## 已知限制

方位是等分布局而非真实测向；距离是单帧 RSSI 估算、未做平滑，数值会跳；历史页未实现；release 未配置签名与混淆（`isMinifyEnabled = false`）；BLE 会把系统的蓝牙设备名改成 App 内昵称；三种模式各自独立，不会自动融合成一张统一的设备表（切换模式等于换一份列表）。

---

## 目录内的其他文件

| 文件 | 说明 |
|---|---|
| `ui-design-spec.md` / `ui-design-spec-v2.md` | 两版 UI 设计稿，v2 是"箭头圆环"方案的完整规范（含距离分档、偏移量、动画周期、配色不透明度） |
| `ui-prototype.jsx` / `ui-arrow-circles.jsx` | 设计阶段的网页原型（React），不是 App 代码 |
| `build_log.txt` | `build_apk.ps1` 上一次构建的输出，已由 `.gitignore` 排除 |
| `图片添加网络信号元素.png` | 设计素材 |
| `device-searcher` | 2,554 字节的 DER 编码 CMS/PKCS#7 结构（外层 `SEQUENCE`、`version 3`、内容类型 `id-data`，内嵌 2,445 字节二进制负载）。**来源不明**，已比对确认它**不是** `app/release/app-release.apk` 的签名块（该内容在 APK 中搜不到）。看着像某次签名/证书操作的导出物，可删 |
| `app/release/app-release.apk` | 2026-06-12 的一次构建产物副本（15.7 MB），注意它在 `app/release/` 而不是标准的 `app/build/outputs/apk/`。已被 `.gitignore` 排除（二进制不进仓库；要分发应挂到 GitHub Releases） |

---

## License

[MIT](LICENSE) © 2026 Rolling

`app/` 下的构建产物、`.gradle/`、`.idea/`、`.kotlin/` 与 `local.properties` 均已由 [.gitignore](.gitignore) 排除。`local.properties` 里是本机 Android SDK 的绝对路径，**不应该提交**。
