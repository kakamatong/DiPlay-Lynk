# CarPlay 路线指引（RGI）协议说明

本文整理 iAP2 Route Guidance Information（RGI）消息族 0x5200–0x5204 的字段定义与取值含义，供 DiPlay 的仪表/HUD 输出对照使用。字段表来自 luka-dev/mib2q-carplay-rgi 的实测定义（hook/routeguidance/rgd_tlv.h），并结合本项目 RouteGuidanceState.kt 的实际解析范围。

## 0. 术语

- **机动（maneuver）**：路线指引中的一条独立导航动作/事件，对应一条 0x5202 记录，用 index 标识。例子：左转、右转、直行、沿路继续、进入环岛、驶出环岛、靠左、靠右、变换高速、到达目的地。它不是车辆的驾驶动作，也不是一段道路，而是“下一步该做什么”的一条指令。
- **机动列表（maneuver list）**：0x5201 的 0x000D，按执行顺序排列的机动 index 列表；第一个是当前要执行的机动。
- **当前机动（current maneuver）**：机动列表的第一个 index，配合 0x5202 的 index 取类型、转向侧和后续道路。
- **路线状态（route guidance state）**：0x5201 的 0x0001，表示有没有路线、是否在导航、是否到达等。
- **车道指引（lane guidance）**：0x5204 的逐车道信息，描述每条车道是否可用或推荐。
- **到下一机动的距离（distance to maneuver）**：0x5201 的 0x000A，通常显示在转向箭头旁。

## 1. 消息族

| ID | 名称 | 方向 | 作用 |
| --- | --- | --- | --- |
| 0x5200 | StartRouteGuidanceUpdates | 车机 → 手机 | 请求开始推送路线指引 |
| 0x5201 | RouteGuidanceUpdate | 手机 → 车机 | 路线状态、道路、ETA、距离、当前机动索引、车道索引 |
| 0x5202 | RouteGuidanceManeuverUpdate | 手机 → 车机 | 单条机动的类型、转向侧、路口信息 |
| 0x5203 | StopRouteGuidanceUpdates | 车机 → 手机 | 停止推送 |
| 0x5204 | RouteGuidanceLaneGuidanceInformation | 手机 → 车机 | 车道指引的逐车道信息 |

0x5201 与 0x5202 通过“机动索引”配对：0x5201 的 0x000D 给出当前机动索引，0x5202 的 0x0001 给出该索引对应的机动类型。

## 2. 编码

payload 是一串 TLV，连续排列：

    type   u16，大端
    length u16，大端
    value  length 字节

字符串为 UTF-8，通常以 NUL 结尾。数值均为大端（网络字节序）。

## 3. 0x5200 StartRouteGuidanceUpdates

车机在认证完成后发送，用来订阅路线指引。字段都是“请求”性质：

| TLV | 名称 | 类型 | 含义 |
| --- | --- | --- | --- |
| 0x0000 | component ID | u16 | 路线指引组件 ID |
| 0x0001 | source name request | 空 | 请求手机在 0x5201 里带上 0x0013 来源名 |
| 0x0002 | source supports RG request | 空 | 请求手机在 0x5201 里带上 0x0014 |
| 0x0003 | supports exit info request | 空 | 请求手机在 0x5202 里带上 0x000D 出口信息 |

## 4. 0x5201 RouteGuidanceUpdate

路线状态的汇总帧，不含具体转向图标，也不含逐车道推荐状态。TLV 是**可选增量**：某一帧可以只带部分字段，接收方要和缓存 merge。

| TLV | 名称 | 类型 | 含义 |
| --- | --- | --- | --- |
| 0x0000 | component ID | u16 数组 | 组件 ID，可重复，最多 8 个 |
| 0x0001 | route guidance state | u8 | 路线状态，见下表 |
| 0x0002 | maneuver state | u8 | 机动更新状态；项目里只存不解释 |
| 0x0003 | current road name | UTF-8 | 当前道路名，NUL 结尾 |
| 0x0004 | destination name | UTF-8 | 目的地名称 |
| 0x0005 | ETA | u64 | 预计到达时间，Unix 秒 |
| 0x0006 | time remaining | u64 | 剩余时间，秒 |
| 0x0007 | distance remaining | u32 | 到目的地剩余距离，单位见 0x0009 |
| 0x0008 | distance remaining string | UTF-8 | 手机格式化好的剩余距离文本 |
| 0x0009 | distance remaining units | u8 | 0=km，1=miles，2=m，3=yards，4=feet |
| 0x000A | distance to maneuver | u32 | 到下一机动的距离，单位见 0x000C |
| 0x000B | distance to maneuver string | UTF-8 | 格式化好的“到下一机动距离”文本 |
| 0x000C | distance to maneuver units | u8 | 同 0x0009 |
| 0x000D | maneuver list | u16 数组 | 机动索引列表；第一个是当前机动，最多 16 个 |
| 0x000E | maneuver count | u16 | 列表中的机动数量 |
| 0x000F | visible in app | u8 | 是否在手机端可见；0/1，项目里只存 |
| 0x0010 | lane guidance index | u16 | 当前车道指引事件索引，对应 0x5204 的 0x0001 |
| 0x0011 | lane guidance total | u16 | 车道指引事件总数，**不是车道条数** |
| 0x0012 | lane guidance showing | u8 | 车道指引显示状态；**不是推荐车道索引** |
| 0x0013 | source name | UTF-8 | 导航来源名（高德 / Apple Maps 等） |
| 0x0014 | source supports RG | u8 | 该来源是否支持路线指引；0 会触发 hard clear |
| 0x0015 | destinationTimeZoneOffsetMinutes | — | iOS 17+，项目未消费 |
| 0x0016 | stopType | — | iOS 17+，项目未消费 |
| 0x0017 | electricVehicleDestination | — | iOS 17+，项目未消费 |
| 0x0018–0x001A | reserved | — | iOS 未来扩展，跳过 |

**重要：0x5201 不携带“推荐哪条车道”。** 推荐车道在 0x5204 的 `0x0002` LaneInformations 里：

- 每条车道 `0x0001 status`：`0=NOT_GOOD`、`1=GOOD`、`2=PREFERRED`
- `0x0003 angleHighlight`：高亮角度

`0x0010` 只是把当前 0x5204 事件和 0x5201 关联起来；`0x0012` 是显示状态，不是推荐车道索引。0x5204 事件可能**先于**激活它的 0x5201 到达，所以接收方要按 index 缓存，等 0x5201 再激活。

路线状态（0x0001）：

| 值 | 名称 | 含义 |
| --- | --- | --- |
| 0 | NoRouteSet | 未设置路线 |
| 1 | RouteSet | 导航中 |
| 2 | Arrived | 已到达 |
| 3 | Loading | 路线加载中 |
| 4 | Locating | 定位中 |
| 5 | Rerouting | 重新规划路线 |
| 6 | ProceedToRoute | 前往路线起点 |

参考：luka-dev/mib2q-carplay-rgi 的 [rgd_tlv.h](https://github.com/luka-dev/mib2q-carplay-rgi/blob/main/hook/routeguidance/rgd_tlv.h)、[rgd_tlv.c](https://github.com/luka-dev/mib2q-carplay-rgi/blob/main/hook/routeguidance/rgd_tlv.c)、[rgd_hook.c](https://github.com/luka-dev/mib2q-carplay-rgi/blob/main/hook/routeguidance/rgd_hook.c)。该项目的 bus 写掩码里有 lane index/showing，但没有 lane total；如果直接拿它的 bus 数据，total 可能看不到。

注意：空机动列表（0x000D 有列表但没有首项）表示“当前没有下一条机动”，不等于导航结束。只有 0 或 2 才是结束信号。

## 5. 0x5202 RouteGuidanceManeuverUpdate

单条机动的详细信息。

| TLV | 名称 | 类型 | 含义 |
| --- | --- | --- | --- |
| 0x0000 | component ID | u16 | 组件 ID |
| 0x0001 | index | u16 | 机动索引，对应 0x5201 的 0x000D |
| 0x0002 | description | UTF-8 | 机动描述文本 |
| 0x0003 | type | u8 | 机动类型，见下表 |
| 0x0004 | after road name | UTF-8 | 完成该机动后进入的道路名 |
| 0x0005 | distance between | u32 | 与下一机动之间的距离，单位见 0x0007 |
| 0x0006 | distance string | UTF-8 | 格式化距离文本 |
| 0x0007 | distance units | u8 | 同 0x5201 的单位枚举 |
| 0x0008 | driving side | u8 | 0=右侧通行，1=左侧通行 |
| 0x0009 | junction type | u8 | 路口类型；原始值，具体枚举未恢复 |
| 0x000A | junction angles | u16 数组 | 路口各分支角度（JunctionElementAngle） |
| 0x000B | exit angle | u16 | 出口角度 |
| 0x000C | linked lane guidance | u16 | 关联的车道指引索引；偶见 component+index 紧凑形式 |
| 0x000D | exit info | 字符串/字节 | 高速出口信息；原厂按字符串处理 |

机动类型（0x0003）：

| 值 | 含义 | 值 | 含义 |
| --- | --- | --- | --- |
| 0 | 无转向 | 27 | 指引结束 |
| 1 | 左转 | 28–37 | 环岛出口 1–10 |
| 2 | 右转 | 38–46 | 环岛出口 11–19 |
| 3 | 直行 | 47 | 左急转 |
| 4 | 掉头 | 48 | 右急转 |
| 5 | 沿路继续 | 49 | 左前 |
| 6 | 进入环岛 | 50 | 右前 |
| 7 | 驶出环岛 | 51 | 变换高速 |
| 8 | 驶离匝道 | 52 | 向左变换高速 |
| 9 | 驶入匝道 | 53 | 向右变换高速 |
| 10 | 导航终点 | | |
| 11 | 路线起点 | | |
| 12 | 到达目的地 | | |
| 13 | 靠左 | | |
| 14 | 靠右 | | |
| 15 | 进入渡口 | | |
| 16 | 驶出渡口 | | |
| 17 | 更换渡口 | | |
| 18 | 掉头后出发 | | |
| 19 | 环岛内掉头 | | |
| 20 | 末端左转 | | |
| 21 | 末端右转 | | |
| 22 | 高速左出口 | | |
| 23 | 高速右出口 | | |
| 24 | 目的地左侧到达 | | |
| 25 | 目的地右侧到达 | | |
| 26 | 允许时掉头 | | |

## 6. 0x5203 StopRouteGuidanceUpdates

车机发给手机，停止路线指引推送。payload 只有一个 TLV：

| TLV | 名称 | 类型 | 含义 |
| --- | --- | --- | --- |
| 0x0000 | component ID | u16 | 组件 ID |

## 7. 0x5204 RouteGuidanceLaneGuidanceInformation

车道指引帧。

| TLV | 名称 | 类型 | 含义 |
| --- | --- | --- | --- |
| 0x0000 | component ID | u16 | 组件 ID |
| 0x0001 | lane guidance index | u16 | 车道指引索引 |
| 0x0002 | lane informations | 嵌套 TLV | 逐车道信息，见下 |
| 0x0003 | lane guidance description | UTF-8 | 车道指引描述 |

0x0002 内部 TLV：

| TLV | 名称 | 类型 | 含义 |
| --- | --- | --- | --- |
| 0x0000 | index / position | u16 | 车道位置 |
| 0x0001 | lane status | u8 | 0=不可用，1=可用，2=推荐 |
| 0x0002 | lane angles | u16 数组 | 车道角度向量，哨兵值 1000 有效 |
| 0x0003 | highlighted angle | u16 | 高亮角度，1000 有效 |

## 8. 时序

1. 车机发送 0x5200，手机开始推送。
2. 手机持续发送 0x5201；机动变化时发送 0x5202；有车道信息时发送 0x5204。
3. 0x5201 的 state 变为 0 或 2，或车机发送 0x5203，导航结束。
4. 空机动列表只表示“当前没有下一条机动”，不应结束导航。

## 9. 本项目的实现范围

RouteGuidanceState.kt 实际解析：

- 0x5201：0x0001、0x0003、0x0005、0x0006、0x0007、0x000A、0x000D、0x0010、0x0011、0x0012
- 0x5202：0x0001、0x0003、0x0004、0x0008
- 0x5204：0x0001、0x0002（含 0x0000–0x0003 嵌套）、0x0003

暂未使用：目的地名、距离字符串/单位、机动数量、visible in app、来源名/来源能力、机动描述、路口类型/角度/出口信息、关联车道索引。

实车观察：

- 0x5201 在本车机上大约每 40–70 秒来一帧。
- 0x5202 在机动变化时到来，比 0x5201 频繁。
- 高德导航不发 0x5204（laneFrames=0）；需要 Apple Maps 取车道样本。
- ECARX 仪表箭头使用 ECARX turn ID 码表：出发 101、左转 102、右转 103、左前 104、右前 105、掉头 108、直行 109、环岛 111、环岛出口 112、收费站 114、到达 115、沿路 120、环岛 1–10 = 121–130、环岛 11–19 = 131–139、环岛左 141、环岛右 142、环岛掉头 143。

## 10. 参考

- luka-dev/mib2q-carplay-rgi：hook/routeguidance/rgd_tlv.h、rgd_tlv.c、rgd_hook.c
- 本项目：shared/src/main/java/com/shilapi/xcertplay/hud/RouteGuidanceState.kt、EcarxHudBridge.kt
