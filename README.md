# DiPlay · 领克 05（ECARX DCY11）移植版

这是 [DiPlay](https://github.com/shihabal3amri/DiPlay) 在**领克 05 / 亿咖通 DCY11 车机**（高通 820A / MSM8996，Android 9，API 28）上的个人移植分支。

上游 DiPlay 是面向比亚迪 DiLink 车机的 CarPlay 接收端；本仓库保留其全部通用能力，替换平台相关部分，使 CarPlay、仪表投送与车机方控在领克 05 上可用。

> 本项目不是 Apple 认证产品，也不是官方发行版。仅供个人车辆测试使用。

## 技术来源

本移植站在两个开源项目之上，特此注明：

| 来源 | 地址 | 用途 |
|---|---|---|
| DiPlay（上游） | https://github.com/shihabal3amri/DiPlay | 基线代码，v0.2.21，GPL-3.0 |
| DiPlay-ECARX（参考实现） | https://github.com/jiopia/DiPlay-ECARX | ECARX / 亿咖通平台适配参考 |

上游版权归其作者所有；本仓库仅包含平台适配与本地化改动。原始 README 保留为 `README.upstream.md`、`README.upstream.zh-CN.md`、`README.upstream.zh-TW.md`。

## 支持范围

| 车机 | 状态 | 说明 |
|---|---|---|
| 领克 05 · 高通 820A（ECARX DCY11，Android 9） | ✅ 已验证 | 本分支开发与测试所用车机 |
| 其它 ECARX / 亿咖通 820A 平台 | ⚠️ 可尝试 | 按 DIM 类是否存在于系统镜像探测，不按车型写死 |
| 非 ECARX 车机 | ❌ 不支持 | 所有 ECARX 调用都会静默跳过，只保留中控 CarPlay |

平台识别不依赖车型字符串：只要系统里有 `com.ecarx.xui.adaptapi.diminteraction.DimInteraction`，或存在 ECARX 系统包，就会启用仪表相关功能；否则自动降级。

## 功能状态

- ✅ **仪表盘导航**：转向箭头、距离、路名、ETA 已支持
- ✅ **HUD 箭头导航**：与仪表共用同一套 DIM 通道，已支持
- ⚠️ **路口车道选择**：未支持到位。高德导航本身不下发车道数据（`0x5204` laneFrames=0），推荐车道无法稳定高亮
- ⚠️ **车机自带高德**：车机的高德会干扰正常的箭头导航。可在 **设置 → 高级 → ECARX** 里点「停用高德地图」，打开系统应用页后自行停用或强行停止

## 本移植的改动

- **仪表盘导航（ECARX DIM）**：把 CarPlay 的转向箭头、剩余距离、路名、ETA 投送到仪表盘。箭头通过 ECARX turn ID 通道（`updateTurnByTurnArrow(int)`）绘制，已在实车确认可用；路口车道选择受限于高德不下发车道数据，未支持到位。全部通过反射调用车机 DIM 接口，不支持的车型上静默跳过。
- **HUD 箭头导航**：同一套 DIM 通道同时驱动车机 HUD 的导航卡片，箭头已实车确认可用；车机不提供 HUD 接口时自动降级，只保留仪表盘输出。
- **仪表投屏**：可把中控画面镜像到仪表盘，设置里单独开关。
- **领克品牌**：默认车标与车辆名改为领克；旧的 BYD 默认值会自动迁移。
- **全屏处理**：从后台返回时保持全屏，回退到与上游一致的 WindowInsetsController 方案。
- **BYD 功能裁剪**：移除 BYD 专属的 ADB 热点、仪表地图与氛围灯入口；通用能力（CarPlay、热点、仪表投屏、方控）保留。
- **通用热点与开机自启**：恢复 Android 原生的车载热点设置；开机自启不再依赖网络 ADB。

## 构建

环境：

- JDK 21
- Android SDK Platform 36.1.0 / Build-Tools 36.1.0
- NDK 28.2.13676358
- Gradle 9.5.0

MFi 身份资产**不随仓库分发**，构建时需要单独注入：

```bash
export DIPLAY_AUTH_ASSETS_DIR=/path/to/mfi-assets   # 内含 offline-mfi/
./gradlew :mobile:assembleRelease \
  -x lintVitalAnalyzeRelease -x lintVitalRelease -x lintVitalReportRelease \
  -Pandroid.injected.signing.store.file=/path/to/platform.jks \
  -Pandroid.injected.signing.store.password=android \
  -Pandroid.injected.signing.key.alias=platform \
  -Pandroid.injected.signing.key.password=android
```

- 包名：`com.kakamatong.diplay.lynk`（debug 变体后缀 `.hudtest`）
- 版本：versionCode 62 / versionName 0.3.21（领克移植线独立版本号，从 0.3.0 起与上游区分）
- 签名：AOSP platform 测试密钥；只对 `test-keys` 固件的车机有效

没有 MFi 资产时 App 能安装启动，但 CarPlay 无法完成认证。

## 安装

1. 在车机上允许安装未知来源应用
2. 安装 APK，打开 DiPlay
3. 无线 CarPlay：打开车机热点，iPhone 连接后在 CarPlay 设置里选择该车机
4. 有线 CarPlay：USB 连接并允许信任

## 已知限制

- 仅验证于领克 05 / DCY11（Android 9）
- 仪表投送依赖车机是否暴露 ECARX DIM 接口；不支持的车型只保留中控 CarPlay
- 推荐车道未支持到位：高德导航不下发车道数据；车机自带高德还可能干扰箭头，可在 设置 → 高级 → ECARX 停用高德
- MFi 身份为公开渠道恢复的实验性资产，可能被 Apple 吊销

## 常见问题：音乐每 1 秒中断

**现象**：用车载 Wi-Fi 连接 CarPlay 时，音乐播放每秒中断一次。

**原因**：车机的蓝牙 A2DP 与 CarPlay 无线音频通道互相抢占，部分固件会在蓝牙保持连接时周期性重连音频路由。

**解决思路**：让 iPhone 在 CarPlay 连接期间关闭蓝牙，断开后再恢复。用 iOS 自带「快捷指令」做两条个人自动化即可：

1. 自动化 → 当 CarPlay **连接**时 → **关闭蓝牙**
2. 自动化 → 当 CarPlay **断开**时 → **打开蓝牙**

设置路径：快捷指令 App → 自动化 → 新建个人自动化 → 选「CarPlay」→ 选连接/断开 → 添加操作「设定蓝牙」→ 关闭/打开 → 关闭「运行前询问」，这样无需每次确认。

两条自动化都建好后，无线 CarPlay 期间的音频只走 Wi-Fi 通道，1 秒中断会消失；断开 CarPlay 后蓝牙自动恢复，电话与车机蓝牙功能不受影响。

## 许可证

GPL-3.0。分发本项目的二进制时，必须同时提供对应源码，并保留上游版权声明。
