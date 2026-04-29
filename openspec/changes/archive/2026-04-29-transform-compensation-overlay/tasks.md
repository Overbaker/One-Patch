# Tasks: transform-compensation-overlay

## 1. OverlayService 状态字段 (P3, P4, P5)

- [x] 1.1 In `OverlayService.kt` companion，新增 `private const val DRIFT_HYSTERESIS_DP = 2f` (替换或并列既有 dp(50))
- [x] 1.2 In `OverlayService.kt` 字段：新增 `private var compensatedDeltaX: Int = 0` 和 `private var compensatedDeltaY: Int = 0`（已在之前提交中预留，确认仍存在或重新声明）

## 2. detectDisplacementAndBypass 重构 (P1, P2, P6, P7, P8, P9, P10, P11)

- [x] 2.1 在 `OverlayService.detectDisplacementAndBypass()` 函数顶部，保留：
  - `runState != RUNNING` → 返回（既有早返回保留）
  - `manualBypass` → 返回（既有早返回保留）
  - `!v.isAttachedToWindow` → 返回
- [x] 2.2 计算 `driftX = locBuf[0] - expectedX; driftY = locBuf[1] - expectedY`
- [x] 2.3 PHYSICAL_DISPLAY 路径不变（既有 fallback 触发逻辑）
- [x] 2.4 增加新分支：`activeBackend is WindowManagerBackend && autoBypassPref()` →
  ```
  val hysteresisPx = DisplayHelper.dp(this, DRIFT_HYSTERESIS_DP)
  if (kotlin.math.abs(driftX) <= hysteresisPx && kotlin.math.abs(driftY) <= hysteresisPx) return
  compensatedDeltaX -= driftX
  compensatedDeltaY -= driftY
  val area = currentArea ?: return
  val compensatedArea = area.copy(
      leftPx = area.leftPx + compensatedDeltaX,
      topPx = area.topPx + compensatedDeltaY
  )
  activeBackend?.update(compensatedArea)
  Log.i(TAG, "compensate drift=($driftX,$driftY) delta=($compensatedDeltaX,$compensatedDeltaY)")
  ```
- [x] 2.5 保留 v1 让步分支：`activeBackend is WindowManagerBackend && !autoBypassPref()` → 既有 `autoBypass = displaced; applyBypassState()` 行为；阈值仍是 `dp(50)`
- [x] 2.6 提取 helper 函数 `applyCompensationDelta(driftX: Int, driftY: Int): Pair<Int, Int>` 返回新累积值（便于纯 JVM 测试）

## 3. Compensation 生命周期重置 (P4, P5)

- [x] 3.1 In `addOrUpdateOverlay()` 的 fresh-attach 分支，紧接着 `expectedY = area.topPx` 之后：`compensatedDeltaX = 0; compensatedDeltaY = 0`
- [x] 3.2 In `handleStop()`，已有 `manualBypass = false; autoBypass = false`，紧接着追加：`compensatedDeltaX = 0; compensatedDeltaY = 0`
- [x] 3.3 In `onConfigurationChanged()`，在 `repository.save(rescaled)` 之后、`activeBackend?.update(rescaled)` 之前：`compensatedDeltaX = 0; compensatedDeltaY = 0`
- [x] 3.4 In `onDestroy()`，重置 `compensatedDeltaX = 0; compensatedDeltaY = 0`

## 4. WindowManagerBackend.update 验证

- [x] 4.1 验证 `WindowManagerBackend.update(area)` 用 `area.leftPx / area.topPx` 构造 LayoutParams.x/y；无需修改（既有签名已支持任意 area）
- [x] 4.2 grep 验证：`WindowManagerBackend.kt` 内 `buildParams` 无硬编码 `currentArea` 引用

## 5. strings.xml 文案重塑

- [x] 5.1 In `strings.xml`，更新 `anti_transform_label` = "屏蔽区物理坐标锁定"
- [x] 5.2 更新 `anti_transform_desc` = "开启后屏蔽区在小米单手模式下保持物理屏幕原位（反向补偿）；关闭后屏蔽区随单手模式平移并自动让步。建议保持开启。"

## 6. PBT property tests

- [x] 6.1 Create `app/src/test/java/com/ccg/screenblocker/service/CompensationArithmeticTest.kt` 覆盖 P1（纯算术）、P2（hysteresis 边界）、P3（累积单调）：
  - `applyCompensationDelta(0, 0, 0, 0)` → `(0, 0)`
  - hysteresis: drift ∈ {1dp, 2dp, 3dp} 边界
  - 累积: 5 次 drift=+5dp 后 delta = -25dp
- [x] 6.2 Create `app/src/test/java/com/ccg/screenblocker/service/CompensationStateLifecycleTest.kt` 覆盖 P4（reset on stop） + P5（reset on config change）通过 stub fake 状态机
- [x] 6.3 Create `app/src/test/java/com/ccg/screenblocker/service/CompensationGuardsTest.kt` 覆盖 P6（manualBypass 屏蔽）、P7（PHYSICAL_DISPLAY 不补偿）、P8（anti_transform=false fallback）、P10（runState gate）
- [x] 6.4 Create `app/src/test/java/com/ccg/screenblocker/service/DetectionSignalSourceTest.kt` 覆盖 P13（信号源唯一性）：grep `detectDisplacementAndBypass` 函数体确认仅引用 `getLocationOnScreen` / `expectedX` / `expectedY` / `compensatedDelta` / area 字段；无 Configuration / WindowInsets / DisplayListener / Settings / matrix 引用

## 7. Compile + smoke verification

- [x] 7.1 `QEMU_LD_PREFIX=/usr/x86_64-linux-gnu ./gradlew :app:assembleDebug` 成功
- [x] 7.2 `QEMU_LD_PREFIX=/usr/x86_64-linux-gnu ./gradlew :app:testDebugUnitTest` — 既有 33 测试 + 新增 PBT 测试全 green
- [x] 7.3 `QEMU_LD_PREFIX=/usr/x86_64-linux-gnu ./gradlew :app:assembleRelease` — APK ≤ 1.6 MB
- [x] 7.4 grep 验证：`OverlayService.kt` 中 `detectDisplacementAndBypass` 的早返回顺序保持：runState → manualBypass → isAttachedToWindow → 计算 drift → 分支逻辑
- [x] 7.5 grep 验证（信号源唯一性 P13）：`detectDisplacementAndBypass` 函数体内**不出现** `Configuration` / `onApplyWindowInsetsListener` / `DisplayManager` / `ContentObserver` / `Settings.System.getInt` / `View.getMatrix()` / `getRootWindowInsets`
- [x] 7.6 grep 验证（无反射 / @hide 访问 P16）：项目源码内 0 命中 `mTranslator` / `mInvCompatScale` / `Class.forName.*ViewRootImpl` / `getDeclaredField.*ViewRoot`

## 8. Spec compliance verification

- [x] 8.1 grep `compensatedDeltaX|compensatedDeltaY` 仅出现在 OverlayService.kt 与新 PBT 测试中
- [x] 8.2 grep 确认 DisplayAttachedBackend / FAB / 编辑器 / 录制 / quick-toggle 路径无任何变动
- [x] 8.3 spec 字段验证：runState / manualBypass / autoBypass / runtime_visible / fab_visible 含义不变；新增 compensatedDeltaX/Y 不进 SharedPreferences（瞬态）

## 9. Archive

- [x] 9.1 标记 tasks.md 全 [x]
- [x] 9.2 `openspec validate transform-compensation-overlay --strict` 通过
- [x] 9.3 `openspec archive transform-compensation-overlay --yes` 合并 spec deltas 到 `openspec/specs/bypass-state-machine/`
