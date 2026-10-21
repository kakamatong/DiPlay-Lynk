# ECARX turn-ID 图标表

> 2026-10-21 · 领克 05 / DCY11 · 来源：实车 `updateTurnByTurnArrow(int)` 100–130 全有图标，结合原厂 turn 名称整理。

`EcarxHudBridge` 的正式箭头通道使用这张表；`turnArrowSvg()` 只保留给 Settings 的 SVG 标定测试。

## 图标 ID

| ID | turn 名称 | 含义 |
| --- | --- | --- |
| 101 | turn_start | 出发 |
| 102 | turn_left（含 2/3 岔左变体） | 左转 |
| 103 | turn_right（含 2/3 岔右变体） | 右转 |
| 104 | turn_left_front / side / branch_left / lf* / rf_2branch_left | 左前方 |
| 105 | turn_right_front / _side / branch_right / lf_2branch_right | 右前方 |
| 106 | turn_left_back | 左后方 |
| 107 | turn_right_back | 右后方 |
| 108 | turn_back（含变体） | 掉头 |
| 109 | turn_front / branch_center / side_main / branch_straight | 直行 |
| 110 | turn_via_1..4 | 途经点 |
| 111 | turn_ring | 环岛 |
| 112 | turn_ring_out / turn_ring_front | 环岛出口/前方 |
| 114 | turn_tollgate | 收费站 |
| 115 | turn_dest | 到达 |
| 120 | turn_along | 沿路 |
| 121–130 | turn_ring 1..10 | 环岛出口 1–10 |
| 131–139 | turn_ring 11..19 | 环岛出口 11–19（131–140 块；140 未用） |
| 141 | turn_ring_left / leftback / leftfront | 环岛左 |
| 142 | turn_ring_right / rightback / rightfront | 环岛右 |
| 143 | turn_ring_back | 环岛掉头 |

## Apple maneuver type → ECARX ID

`turnArrowId(type, drivingSide)` 的实现：

| Apple type | ECARX ID | 说明 |
| --- | --- | --- |
| 0, 3 | 109 | 无转向 / 直行 |
| 1, 20, 47 | 102 | 左转 / 末端左转 / 左急转 |
| 2, 21, 48 | 103 | 右转 / 末端右转 / 右急转 |
| 13, 49, 52 | 104 | 靠左 / 左前 / 向左变换高速 |
| 14, 50, 53 | 105 | 靠右 / 右前 / 向右变换高速 |
| 8, 9 | 104 / 105 | 匝道；右侧通行取 105，左侧通行取 104 |
| 4, 18, 26 | 108 | 掉头 / 掉头后出发 / 允许时掉头 |
| 19 | 143 | 环岛内掉头 |
| 5, 15, 16, 17, 51 | 120 | 沿路 / 渡口 / 变换高速 |
| 6 | 111 | 进入环岛 |
| 7 | 112 | 驶出环岛 |
| 28–37 | 121–130 | 环岛出口 1–10 |
| 38–46 | 131–139 | 环岛出口 11–19（131–140 块，140 未用） |
| 10, 12, 24, 25, 27 | 115 | 终点 / 到达 / 指引结束 |
| 11 | 101 | 路线起点 |
| 22 | 104 | 高速左出口 |
| 23 | 105 | 高速右出口 |

未使用：106/107（左/右后方）、110（途经点）、114（收费站）、141/142（环岛左/右）——当前 Apple maneuver type 里没有能可靠区分的对应值。
