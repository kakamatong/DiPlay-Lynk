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

## 7. 当前假设（按可能性排序）

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
- `EcarxLaneProbe.kt`：4 轮 spy
- `EcarxHudBridge.kt`：`endLocked()` 的空数组清理、`probeLaneInfo()`
- `RouteGuidanceState.kt`：`0x5204` / `0x5201` 车道解析
- `docs/ROUTE_GUIDANCE_PROTOCOL.md`：0x5204 / 0x5201 协议
