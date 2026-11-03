# 上游同步技术方案：v0.2.22 → 领克分支 0.3.x

> 调研时间：2026-11-03 · 基线 `merge/lynk-main` @ `f969fddd`（0.3.30 / versionCode 71）
> 上游：`origin/main` @ `615b32ce`（v0.2.22 后 3 个站点提交）

## 结论

上游确实更新了，已按 **merge upstream 到同步分支** 执行，没有 rebase 81 个领克提交。

**执行结果（2026-11-03）**

- 同步分支：`sync/upstream-0.2.22`
- 合并提交：`8c39b992`（17 个冲突文件、18 个冲突块）
- 发布提交：`28f736b9`（0.3.31 / versionCode 72，补齐韩语、土耳其语）
- 完整 CI：`BUILD SUCCESSFUL`（16m 8s，含 shared/common/home 单测与三模块 lint/assemble）
- release APK：`/root/diplay-out/DiPlay-lynk-0.3.31.apk`（19,130,297 bytes）
- SHA-256：`7f387700208020c4999a34eb14d76fda37e7108749be9b1686ea932e222f2e0d`
- 签名：与 0.3.30 相同的 platform 证书，可直接覆盖升级
- 尚未合并回 `merge/lynk-main`，等待实车回归

## 差距数据

- 合并基：`6d3b8a8c`（0.2.21 发布，2026-10-17）
- 领克侧：81 个提交，65 个文件，+5378 / −2042
- 上游侧：919 个提交，306 个文件
- 上游新版本：v0.2.22（2026-10-28），随后 3 个站点翻译提交
- 上游主要变化：无线 CarPlay/热点回归修复、音频与 USB 麦克风、连接失败提示、安全加固（输入边界/权限/日志脱敏）、韩语+土耳其语、Geely/Lynk `geely_fx11` 仪表地图、ECARX 轮速与挡位、iAP2 车道导引声明（0x5204）

## 冲突清单与处理口径

代码冲突只有 3 处：

1. `DiPlayActivity.kt`（3 块）
   - 上游新增 `adbCluster` / `fullPanelCluster` / Geely 全屏仪表判断。
   - 领克侧曾隐藏 BYD 的 ADB/仪表设置，合并后 `adbCluster` 会变成未定义；试合并里已补回 `val adbCluster = AdbClusterRouter.enabled(this)`，但没有恢复被隐藏的 BYD 设置 UI。
   - 第三块是 `AMAP_PACKAGE` 与上游两个间距常量“双方新增”，两边都保留。
2. `ClusterMapPresentation.kt`（1 块）
   - 先走上游 `GeelyClusterDisplay` 的早返回，再走领克侧 BYD/ECARX 的屏幕选择逻辑。
3. `ClusterSpeedometerSettingsTest.kt`（1 块）
   - 保留领克侧 `saveClusterMapEnabled(context, false)` 前置，再用上游带默认参数的新签名。

非代码冲突：

- 9 个 `strings.xml`：双方都在文件末尾追加。试合并采用“上游全量 + 补入领克独有的 18 个 key”，没有重复 key。
- `mobile/build.gradle.kts`：版本号取领克侧（合并时不要被上游 0.2.22/41 覆盖）。
- `BydVehicleDataSettingsTest.kt`：领克侧删除了该测试，上游修改了它；保持删除，否则测试会引用已删除的 BYD 设置。
- `README.md` / `CHANGELOG.md` / `.gitignore`：README 和 CHANGELOG 需要手工合并（试合并为了编译先取了领克侧）；`.gitignore` 取并集。

## 需要一起拍板的点

### 1. 是否借这次同步，向上游的领克支持收敛

上游 0.2.22 已经出现三块和领克相关的能力：

- `GeelyClusterDisplay` / `GeelyClusterProjection`：只认 `geely_fx11` / `CS11_A4`（领克 03 那类车机），**不覆盖领克 05 的 ECARX DCY11**，不能直接替代现有 ECARX 仪表桥。
- `EcarxWheelSpeed`：通过 ECARX `ISensor` 读轮速/挡位，`present()` 时自动挂到 iAP2，给 iPhone 做死 reckoning；领克 05 大概率直接受益，建议合并后实车验证。
- iAP2 车道导引声明：上游在 identification 里声明 0x5204 组件参数 7/8；领克侧目前只是把 0x5204 加进接收列表，手机不一定真的下发。合并后可能顺带改善“车道导航不支持”的老问题，但仍需确认 ECARX DIM 的 `ILaneInfo` 输出能接上。

**建议**：这次先保留自研 ECARX 仪表桥，不删代码；把上游的轮速和车道声明当作增量能力验证。等实车确认后再决定是否把自研车道探针/旧路径降级。

### 2. 长期分支策略：继续删 BYD，还是保留 + 开关

领克分支已经删了 8 个 BYD 相关文件/测试（`BydStarterBridge`、`StarterBridgeReceiver`、5 个 BYD 设置测试、`BydTyresTest`）。上游每个版本都在改这些文件，后续每次同步都会出现 modify/delete 冲突。

**建议**：这次同步先不改分支策略，避免把“合并”和“恢复 BYD 代码”两件事混在一起；等 0.3.31 在车上稳定后，再单独做一次“保留上游 BYD 代码 + 领克侧隐藏/开关”的重构。这样后续 merge 的冲突面会明显变小，代价是包体略大、需要维护隐藏开关。

### 3. 版本号与发布

- 建议新版本 **0.3.31 / versionCode 72**，tag `v0.3.31-lynk.1`。
- 合并提交不要直接用上游的 0.2.22/41。
- 发布前保留 `v0.3.30-lynk.1` 作为回滚点。

## 执行步骤（建议）

1. 备份：确认 `merge/lynk-main` 干净，打临时 tag `backup/lynk-0.3.30`。
2. 建同步分支：`git worktree add -b sync/upstream-0.2.22 ... origin/main` 或从 `merge/lynk-main` 建分支后 `git merge origin/main`。
3. 按上面的口径解 17 个冲突；代码冲突优先保留领克逻辑，再补上游新增变量。
4. 补 `values-ko` / `values-tr` 的 14 个 `settings_ecarx_*` 文案（共 28 条），跑 `SettingsTranslationsTest`。
5. 跑 CI：`:shared:testDebugUnitTest :common:testDebugUnitTest :home:testDebugUnitTest :mobile:lintDebug :home:lintDebug :maphost:lintDebug :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug`。
6. 打 release APK，先自测再上车。
7. 实车重点回归：无线 CarPlay 热点连接、USB/蓝牙、音频不断流、方向盘按键、仪表箭头/距离/路名、HUD、ECARX 轮速、车道 0x5204、后台返回全屏。
8. 通过后再合并回 `merge/lynk-main`，推 `mine/main`，发 0.3.31。

## 回滚

- 同步分支独立，不通过就不合并。
- 已有 `v0.3.30-lynk.1` 和本地 APK 可回退。
- 实车异常时优先回退到 0.3.30，而不是在同步分支上继续叠补丁。
