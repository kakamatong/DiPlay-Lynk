# ECARX（领克 05）移植参考笔记

> 建立时间：2026-10-18 · 分支 `car/lynk-ecarx` · 基线 **upstream v0.2.21**
> 2026-11-03：已同步 upstream **v0.2.22**（merge 提交见 `sync/upstream-0.2.22`）。

## 目的

本分支用于领克 05（ECARX DCY11 / 高通 820A / Android 9 / arm64）的 CarPlay 接收端二次开发。
上游 DiPlay 只面向 BYD DiLink，ECARX 适配需要自己补。

## 参考实现

**`jiopia/DiPlay-ECARX`** —— 同款车的社区移植版，已 fetch 到本地 remote `ecarx`（`ecarx/main` @ `7f783436`）。

| 项目 | 数值 |
|---|---|
| 它的基线 | upstream **0.2.15**（versionCode 34） |
| 相对当前 v0.2.21 落后 | **6 个版本**（0.2.16 → 0.2.21） |
| 变更规模 | **1217 个文件**，+83412 / −97832 |
| 删除文件 | **621 个**（大砍 BYD/DiLink 集成） |
| 包名 | `com.shihab.diplay`（与官方同包名，不可共存） |

### 它新增的 eCarX 专属模块（值得直接借鉴）

```
shared/src/main/java/com/shilapi/xcertplay/hud/EcarxCapabilities.kt
shared/src/main/java/com/shilapi/xcertplay/hud/EcarxDimApi.kt          ← 仪表(DIM)接口
shared/src/main/java/com/shilapi/xcertplay/hud/EcarxHudBridge.kt
shared/src/main/java/com/shilapi/xcertplay/hud/EcarxKeyBroadcasts.kt
shared/src/main/java/com/shilapi/xcertplay/hud/EcarxMicPolicy.kt      ← 麦克风策略
shared/src/main/java/com/shilapi/xcertplay/hud/EcarxOutputSettings.kt
shared/src/main/java/com/shilapi/xcertplay/hud/EcarxSwKey.kt          ← 方向盘按键
shared/src/main/java/com/shilapi/xcertplay/hud/EcarxSwKeyIntercept.kt
shared/src/main/java/com/shilapi/xcertplay/bluetooth/CarBluetoothProfileGuard.kt
shared/src/main/java/com/shilapi/xcertplay/hud/ClusterSongState.kt    ← 仪表歌曲状态
common/src/test/java/com/shilapi/xcertplay/EcarxSwKeyTest.kt
```

### 它新增的工程文件

`BUILD.md`、`NOTICE.md`、`.gitattributes`

## 两条基线怎么选

| | A. 官方 v0.2.21 为基（**本分支现状**） | B. DiPlay-ECARX 为基 |
|---|---|---|
| 上游同步 | ✅ 干净，rebase 容易 | ❌ 621 删除 → 每版手工合 |
| 车机可用性 | 需要自己补 eCarX 适配 | ✅ 开箱即用（但落后 6 版） |
| 风险 | 移植工作量大 | 长期维护恶化 |

**结论：走 A。** 照抄 `Ecarx*.kt` 这些**新增文件**，不要删 BYD 代码，用开关隔离：

```kotlin
// 建议：运行时/编译期开关，而不是删除代码
object EcarxConfig {
    const val ENABLED = true   // 领克分支开启；上游保持 false
}
```

## 上游同步命令

```bash
git fetch origin --tags            # origin = shihabal3amri/DiPlay
git checkout car/lynk-ecarx
git rebase origin/main             # 或 git merge v0.2.22
```

## 不可入库的东西

`.gitignore` 已加：

```
**/assets/offline-mfi/    # MFi 证书与私钥（Apple 私有）
*.p7b / *.pk8
keystore.properties / *.jks
```

## 包名与签名（待定）

- 与官方**共存** → 改 `applicationId`（如 `com.<you>.diplay.lynk`）
- **覆盖**官方 → 需要官方签名（不具备），不可行
- 自己的 keystore 固定一把、versionCode 单调递增（上游当前 38）
