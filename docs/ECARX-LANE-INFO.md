# ECARX 车道 API（updateLaneInfo / ILaneInfo）现状

> 2026-10-21 · 领克 05 / DCY11 · 依据实车探针报告与 `0x5204` 解析代码整理

## 1. 接口与结构

正式入口：

```
INaviInteraction.updateLaneInfo(ILaneInfo[])
```

`ILaneInfo` 是 `com.ecarx.xui.adaptapi.diminteraction.INaviInteraction$ILaneInfo`，纯 interface，没有父接口，也没有继承 `Parcelable`。实车反射结果只有 3 个方法：

| 方法 | 返回 | 推测含义 | 探针里是否被读 |
| --- | --- | --- | --- |
| `getLaneBackIcon()` | `Bitmap` | 车道背景图标（自定义绘制） | **否** |
| `getLaneBackIconId()` | `int` | 车道背景/箭头图标 ID | **是**，每个元素一次 |
| `getLaneNumber()` | `int` | 车道序号/数量 | **否** |

## 2. 实车探针结果

`EcarxLaneProbe` 现在跑 4 轮直接 `updateLaneInfo`，每轮 3 条车道：

| 轮次 | ID | Bitmap | laneNumber | 结果 | 平台读取 |
| --- | --- | --- | --- | --- | --- |
| 1 | -1 | 64×64 彩色块 | 1..3 | accepted | 只读 ID，3 次，值 -1 |
| 2 | 0 | 64×64 彩色块 | 1..3 | accepted | 只读 ID，3 次，值 0 |
| 3 | 102/103/109 | 64×64 彩色块 | 1..3 | accepted | 只读 ID，3 次，值 102/103/109 |
| 4 | 102/103/109 | 无 | 0/1/2 | 待实车 | 预期只读 ID |

第 4 轮最后执行，且探针结束**不再立即清空车道**，所以仪表会保留 102/103/109 + laneNumber 0/1/2 的最终状态，方便观察；下一次正式导航帧或会话结束会覆盖/清理它。

之前还有一轮 `INavigationInfo.getLaneInfo()` 聚合测试，结果为 **没有任何调用**；它还会推一张 fake `LANE PROBE` 卡片遮住车道区域，所以已从按钮里移除。以后用 Apple Maps 复测时可以单独加回。

结论：

- `updateLaneInfo` 接受 `ILaneInfo[]`，但当前客户端只消费 `getLaneBackIconId()`；
- `getLaneBackIcon()` 和 `getLaneNumber()` 在这条路径上完全没被读取；
- `INavigationInfo.getLaneInfo()` 聚合路径在这次测试里没有被消费；
- 仪表对 `updateLaneInfo` 有反应，但没有看到箭头（测试时使用高德，高德不发 `0x5204`）。

## 3. 输入侧：0x5204 / 0x5201

`0x5204 RouteGuidanceLaneGuidanceInformation`：

| TLV | 含义 |
| --- | --- |
| `0x0001` | lane guidance index（对应 `0x5201` 的 `0x0010`） |
| `0x0002` | 嵌套 LaneInformations |
| `0x0003` | 描述文本 |

`0x0002` 内层：

| 子 TLV | 含义 |
| --- | --- |
| `0x0000` | 车道位置 |
| `0x0001` | 状态：0 不可用 / 1 可用 / 2 推荐 |
| `0x0002` | 角度数组（u16，哨兵 1000） |
| `0x0003` | 高亮角度（u16，哨兵 1000） |

`0x5201` 还有车道汇总字段：

| TLV | 含义 |
| --- | --- |
| `0x0010` | 当前车道指引索引 |
| `0x0011` | 车道指引总数 |
| `0x0012` | 当前显示状态（原始值，语义未完全确认） |

`0x5202` 的 `0x000C` 是“关联车道指引索引”，目前 parser 没有读。

实车观察：高德导航不发 `0x5204`（`laneFrames=0`）；要拿真实车道样本需要 Apple Maps。

## 4. 当前代码路径

- 生产路径只有 `endLocked()` 里的 `updateLaneInfoEmpty()`（空数组清理），见
  `EcarxHudBridge.kt`、`EcarxDimApi.kt`。
- `Guidance.laneInfo` 默认 `null`，因此 `INavigationInfo.getLaneInfo()` 在正式导航里返回 `null`；只有 `EcarxLaneProbe` 会临时注入 spy 数组。
- `RouteGuidanceState` 解析 `0x5204` 和 `0x5201` 的车道字段，但当前只用于诊断（`laneSnapshot()`、`laneFrames/laneHex/laneMethods`）。
- `EcarxLaneProbe` 是唯一的非空 `updateLaneInfo` 调用者。

## 5. 已确认

1. 类名、interface 属性、3 个 getter 的签名。
2. `updateLaneInfo(ILaneInfo[])` 可以被第三方 App 反射调用，返回 accepted。
3. 当前客户端在 `updateLaneInfo` 路径上只读 `getLaneBackIconId()`。
4. Bitmap 和 laneNumber 不是这条路径的必填字段（至少没有导致拒绝）。
5. `ILaneInfo` 不是 `Parcelable`，动态 Proxy 可以工作。
6. 数组长度 3 可以接受；元素顺序/数量没有报错。
7. 仪表对调用有反应（具体表现待补充照片）。

## 6. 未确认

1. `getLaneBackIconId()` 的语义：是转向箭头 ID（100–143）、车道背景图标 ID，还是另一套资源 ID？
2. 数组是“一条车道一个元素”还是“整个车道条的若干背景图标”？数组下标是否等于车道位置？
3. `getLaneNumber()` 是车道序号还是车道数量；为什么完全没被读？
4. `getLaneBackIcon()` 在什么条件下才会被读（不同 sentinel、不同固件、active 0x5204）？
5. `INavigationInfo.getLaneInfo()` 是否只在真实 `0x5204` / 车道指引激活时才被消费？
6. 仪表“有反应但没箭头”的原因：高德没有 0x5204、ID 表不对、还是车道 API 只画背景不画箭头？
7. 车道图标表是否和 turn ID 表（101–143）共用；131–139 是否是环岛出口 11–19。

## 6.5 真实道路观察：逐车道箭头从哪来

2026-10-21 实车反馈：**路口每个车道的箭头指引是有的，但没有“推荐车道”高亮**；而且 **HUD 也显示同样的车道箭头**。HUD 不可能是中控高德 UI 的镜像，所以可以排除“投屏/高德原生 UI”，基本确定是车机自己的独立车道数据（原车导航/仪表地图）在喂 HUD 和仪表。DiPlay 正式路径从未发送非空车道数据——所以这些基础箭头不是 `updateLaneInfo` 画的。

可能来源（按当前证据排序）：

1. **原车自带导航 / 仪表地图**（最可能）：车机导航在后台运行或仪表地图本身知道当前道路的车道，直接把车道箭头送到仪表和 HUD。
2. **仪表按 turn ID / 机动类型自己画**：仪表根据 `updateTurnByTurnArrow(int)` 的机动图标生成一套通用车道箭头。
3. **仪表投屏镜像**：HUD 也显示后基本排除；仅作为对照保留。
4. **0x5204**：只有 `laneFrames > 0` 才成立；高德通常不发，但需要看诊断报告确认。

如果基础箭头来自 1/2，那么 `ILaneInfo` 很可能只负责**推荐车道的高亮/背景层**，不是整条车道箭头。102/103/109 是转向图标，不是“推荐”图标，所以不会出现高亮——这与“有箭头、没推荐”完全吻合。

快速判定：

| 实验 | 若箭头消失/变化 | 说明 |
| --- | --- | --- |
| 退出原车导航的路线 | 箭头消失 | 来源是原车导航路线 |
| 关闭原车导航/仪表地图（若可关） | 箭头消失 | 来源是原车导航/地图 |
| 关闭“仪表盘同步投屏” | 箭头不变 | 排除投屏镜像 |
| 诊断报告 `laneFrames` | 0 或 >0 | 0 则排除 0x5204 |
| 同一路口用 Apple Maps | 出现推荐高亮 | 0x5204 是缺失的推荐层 |
| 用 100–200 turn ID 测试 | 车道箭头跟着变 | 来源是仪表按 turn ID 绘制 |
| 点 `updateLaneInfo` 探针 | 只有背景/高亮变化 | `ILaneInfo` 是推荐/背景层 |

## 6.6 读取探针（2026-10-22）

导航开始后（`lastPushed != null`），`EcarxHudBridge` 每秒调用一次 `EcarxLaneProbe.readLaneInfo(navi)`：

1. 先找 `navi.getLaneInfo()`；
2. 没有再找 `navi.getNavigationInfo().getLaneInfo()` / `getCurrentNavigationInfo().getLaneInfo()`；
3. 返回 `ILaneInfo[]` 时，按 `[i] number=... id=... icon=...` 打印所有非空字段；
4. 如果 getter 存在但为空，只记一次 `=empty`；
5. 如果 getter 不存在，打印 `navi` 的所有无参方法名并停止轮询。

日志 tag 是 `DiPlay-ECARX-Lane`；诊断报告里新增 `lanePoll=`。

注意：`INavigationInfo.getLaneInfo()` 是平台调用**我们**的 getter，不是平台提供的读取接口；这里是在赌 `navi` 对象自身有同名读取方法。若日志显示 `no lane getter`，说明 DIM 这条路读不到原车导航的车道数据，需要另找原车导航的 service/broadcast。

## 6.7 0.3.25 日志结论与 callback 探针（2026-10-23）

0.3.25 实车报告：

- `laneFrames=0`、`laneBytes=0`、`laneHex=none`：没有收到 `0x5204`；
- `laneEvent=0`、`laneShowing=0`、`laneTotal=0`、`laneLanes=`、`laneTlvDump=`：没有逐车道内容；
- `lanePoll=no lane getter; ...`：1 秒读取探针跑了，但 `navi` 上没有 `getLaneInfo` / `getNavigationInfo`。

同一份方法列表暴露了两个新入口：

- `registerNavigationInteractionCallback(INavigationInteractionCallback)`
- `updateNavigationAppState(boolean)`

因此 `Probe ILaneInfo` 现在还会：

1. dump `navi` 的**全量方法**；
2. 找到 `registerNavigationInteractionCallback` 的参数类型，dump 这个 callback 接口的所有方法；
3. 注册一个 Proxy，180 秒内把每次回调按 `cb.<method>(args)` 打到 `DiPlay-ECARX-Lane`；
4. 如果参数是 `INavigationInfo` / `ILaneInfo[]`，格式化成 `number/id/icon`；
5. 诊断报告新增 `navCallback=`，保留最近 12 条回调。

Proxy 对非 void 返回值一律返回类型默认值（false/0/null/""），这是诊断用，不是正式实现。再次点探针会先注销旧 callback 再注册新的。

如果 180 秒内一条 `cb.*` 都没有，说明这条回调也不推送导航/车道数据；下一步再试 `updateNavigationAppState(true)`，或者确认原车导航的数据只在车机内部。

## 7. 当前假设（按可能性排序）

**E. 箭头早已由别的层画出（2026-10-21 新观察）。** 真实路口已有逐车道箭头，但 DiPlay 正式路径没发非空车道数据；基础箭头可能来自投屏镜像、车机原生导航或仪表按 turn ID 自绘。`ILaneInfo` 只负责推荐车道的高亮/背景层。这条目前最符合现场现象。

**A. ID-only 方案。** 客户端把 `ILaneInfo[]` 当成“每条车道一个背景图标 ID”，数组下标就是车道位置；Bitmap 和 laneNumber 在这个固件里是死字段。实现时只需要给每条车道一个正确的 icon ID。

**B. 背景 + 独立箭头。** `updateLaneInfo` 只负责车道背景/车道条，转向箭头由 `updateTurnByTurnArrow(int)` 单独画。二者需要同时存在，才会出现“有车道也有箭头”。

**C. 高德/0x5204 条件。** 仪表只有在真实 `0x5204` 车道指引事件激活时才进入车道显示状态；高德不发 0x5204，所以只看到“有反应”但没有完整车道/箭头。需要 Apple Maps 复测。

**D. 独立车道 ID 表。** 车道背景图标 ID 不是 turn ID 的 100–143，而是另一段（例如 200+）；102/103/109 只是被接受但没有对应车道图标。

## 8. 下一步验证

1. **Apple Maps + 真实 0x5204**：跑一段有车道指引的路，再点探针，拍仪表车道区域；确认 `getLaneBackIcon`/`getLaneNumber` 是否开始被读。
2. **车道 ID sweep**：用 `updateLaneInfo` 每次只发 1 条车道，ID 从 100 扫到 200，每 2 秒一步，观察哪段 ID 会在车道区域画出图标/箭头。
3. **数组语义**：分别发 1/2/3 条车道，调换顺序，改 laneNumber（0-based vs 1-based），看车道位置和数量怎么变。
4. **聚合路径**：在真实 0x5204 激活时，把 spy 数组放进 `updateNavigationInfo.getLaneInfo()`，确认这条路径是否被消费。
5. **静态确认**：在 `tools-apktool` 的 smali 里 grep `getLaneBackIcon`、`getLaneBackIconId`、`getLaneNumber`、`updateLaneInfo`，看原厂应用实际填什么。

## 9. 代码位置

- `EcarxDimApi.kt`：`updateLaneInfo()`、`updateLaneInfoEmpty()`、`getLaneInfo`
- `EcarxLaneProbe.kt`：4 轮 spy + `readLaneInfo()` 读取探针 + 全量方法 dump
- `EcarxNavCallbackProbe.kt`：`registerNavigationInteractionCallback` 的 Proxy 探针
- `EcarxHudBridge.kt`：`endLocked()` 的空数组清理、`probeLaneInfo()`、每秒 `pollLaneInfoLocked()`、`navCallback`
- `RouteGuidanceState.kt`：`0x5204` / `0x5201` 车道解析
- `docs/ROUTE_GUIDANCE_PROTOCOL.md`：0x5204 / 0x5201 协议
