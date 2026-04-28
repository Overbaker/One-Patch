# Tasks: physical-display-overlay

## 1. OverlayBackend interface (P1, P2)

- [x] 1.1 Create `app/src/main/java/com/ccg/screenblocker/service/OverlayBackend.kt` with: `interface OverlayBackend { fun attach(area: BlockArea): Boolean; fun update(area: BlockArea); fun detach(); fun isAttached(): Boolean; fun id(): String }`
- [x] 1.2 Define backend id string constants: `"PHYSICAL_DISPLAY"`, `"ACCESSIBILITY"`, `"APPLICATION_OVERLAY"`, `"NONE"`

## 2. WindowManagerBackend (existing logic extraction, no behavior change)

- [x] 2.1 Create `app/src/main/java/com/ccg/screenblocker/service/WindowManagerBackend.kt` implementing `OverlayBackend`
- [x] 2.2 Move `OverlayService.buildOverlayParams` body verbatim into `WindowManagerBackend.buildParams(area, useA11y)`
- [x] 2.3 Implement `attach(area)`: choose `useA11y = BlockerAccessibilityService.get() != null`; build LayoutParams; call `windowManager.addView(overlayView, params)`; on success return true + record `attachedViaA11y`; catch any exception → log + return false
- [x] 2.4 Implement `update(area)`: `windowManager.updateViewLayout(overlayView, buildParams(area, attachedViaA11y))`
- [x] 2.5 Implement `detach()`: `windowManager.removeViewImmediate(overlayView)`; clear internal references; reset `attachedViaA11y = false`
- [x] 2.6 Implement `isAttached()`: returns whether the view is currently in the WM hierarchy (track via internal flag)
- [x] 2.7 Implement `id()`: returns `"ACCESSIBILITY"` if `attachedViaA11y`, else `"APPLICATION_OVERLAY"`
- [x] 2.8 Constructor accepts `OverlayService` reference (for windowManager + overlayView access); does not own OverlayView lifecycle

## 3. DisplayAttachedBackend (@RequiresApi 34) (P3, P6, P7)

- [x] 3.1 Create `app/src/main/java/com/ccg/screenblocker/service/DisplayAttachedBackend.kt` annotated `@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)` (API 34) implementing `OverlayBackend`
- [x] 3.2 Constructor: `(service: OverlayService, overlayView: OverlayView)`
- [x] 3.3 Internal state fields: `private var sc: SurfaceControl? = null; private var host: SurfaceControlViewHost? = null; private var attached: Boolean = false`
- [x] 3.4 Implement `attach(area)`:
  ```
  runCatching {
    val newSc = SurfaceControl.Builder()
      .setName("OnePatchOverlay")
      .setBufferSize(area.widthPx, area.heightPx)
      .setFormat(PixelFormat.TRANSLUCENT)
      .setOpaque(false)
      .build()
    val newHost = SurfaceControlViewHost(service, Display.DEFAULT_DISPLAY, null /* hostToken */)
    newHost.setView(overlayView, area.widthPx, area.heightPx)
    val a11y = BlockerAccessibilityService.get() ?: throw IllegalStateException("a11y not bound")
    val ok = a11y.attachOverlayToDisplay(newSc)
    if (!ok) throw IllegalStateException("attachOverlayToDisplay returned false")
    SurfaceControl.Transaction()
      .setPosition(newSc, area.leftPx.toFloat(), area.topPx.toFloat())
      .apply()
    sc = newSc; host = newHost; attached = true
  }.getOrElse { e -> Log.e(TAG, "attach failed", e); detach(); false } != null
  ```
- [x] 3.5 Implement `update(area)`:
  ```
  val s = sc ?: return
  SurfaceControl.Transaction()
    .setPosition(s, area.leftPx.toFloat(), area.topPx.toFloat())
    .setBufferSize(s, area.widthPx, area.heightPx)
    .apply()
  host?.relayout(area.widthPx, area.heightPx, ...)
  ```
- [x] 3.6 Implement `detach()`: `host?.release(); sc?.let { SurfaceControl.Transaction().remove(it).apply(); it.release() }; sc = null; host = null; attached = false`
- [x] 3.7 Implement `isAttached()`: returns `attached`
- [x] 3.8 Implement `id()`: returns `"PHYSICAL_DISPLAY"`

## 4. BlockerAccessibilityService extension (P6)

- [x] 4.1 In `BlockerAccessibilityService`, add `@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)` method:
  ```
  fun attachOverlayToDisplay(sc: SurfaceControl): Boolean = runCatching {
    attachAccessibilityOverlayToDisplay(Display.DEFAULT_DISPLAY, sc)
    true
  }.getOrDefault(false)
  ```
- [x] 4.2 Override `onUnbind(intent)`: if previously bound and `OverlayService` reachable via Intent broadcast, send `ACTION_A11Y_UNBOUND` so service can fallback. Reuse existing `instance = null` cleanup.
- [x] 4.3 Add new constant in `OverlayService.companion`: `const val ACTION_A11Y_UNBOUND = "com.ccg.screenblocker.action.A11Y_UNBOUND"`
- [x] 4.4 Register receiver for `ACTION_A11Y_UNBOUND` alongside existing `ACTION_A11Y_AVAILABLE` in `OverlayService.onCreate`

## 5. OverlayService refactor (P1, P4, P5)

- [x] 5.1 Add fields: `private var activeBackend: OverlayBackend? = null; private var fallbackTriggered: Boolean = false; private val driftFrameCount = AtomicInteger(0)`
- [x] 5.2 Implement `private fun selectBackend(): OverlayBackend`:
  - If `Build.VERSION.SDK_INT >= 34 && BlockerAccessibilityService.get() != null && !fallbackTriggered` → return `DisplayAttachedBackend(this, overlayView!!)`
  - Else → return `WindowManagerBackend(this, overlayView!!)`
- [x] 5.3 Refactor `addOrUpdateOverlay(area)`:
  - Fresh attach branch: create `OverlayView`; `activeBackend = selectBackend()`; if `!activeBackend.attach(area)` → `if (activeBackend is DisplayAttachedBackend) { activeBackend = WindowManagerBackend(...); activeBackend.attach(area) }`; on success continue existing flow (set runState=RUNNING, install displaceDetector, etc.)
  - Update branch: `activeBackend?.update(area)`
- [x] 5.4 Refactor `removeOverlayIfAttached()`: `activeBackend?.detach(); activeBackend = null`
- [x] 5.5 Refactor `applyBypassState()`:
  - `OverlayView.bypassRuntime` setter (unchanged)
  - LayoutParams flags 操作仅在 `activeBackend is WindowManagerBackend` 时执行
  - `updateFabIcon()` 调用不变
  - 通知刷新不变
- [x] 5.6 Implement `private fun triggerFallbackToWindowManager(reason: String)`:
  - If `fallbackTriggered` → return（防 ping-pong）
  - `fallbackTriggered = true`; Log.w(TAG, "fallback: $reason")
  - 保存 `currentArea`；`activeBackend?.detach()`；`activeBackend = WindowManagerBackend(this, overlayView!!)`；`activeBackend.attach(currentArea!!)`；`applyBypassState()`
- [x] 5.7 Refactor `detectDisplacementAndBypass()`:
  - 旧路径（WindowManagerBackend）：行为不变
  - 新路径（DisplayAttachedBackend）：检测到漂移 → 累计 `driftFrameCount`；连续 ≥ 2 帧 → `triggerFallbackToWindowManager("displaced")`
- [x] 5.8 Refactor `onConfigurationChanged`: `activeBackend?.detach() then activeBackend?.attach(rescaledArea)` 在同一 frame 内
- [x] 5.9 Refactor `handleStop`: detach backend → reset fields；`fallbackTriggered = false`（下次 enable 重新评估）
- [x] 5.10 Add `ACTION_A11Y_UNBOUND` handler: 触发 `triggerFallbackToWindowManager("a11y_unbound")`

## 6. LocalBinder + MainActivity status (P-status)

- [x] 6.1 In `OverlayService.LocalBinder`, add `fun getActiveBackend(): String = activeBackend?.id() ?: "NONE"`
- [x] 6.2 In `MainActivity.renderState()`, when `isServiceRunning`:
  - Read `serviceBinder?.getActiveBackend()`
  - Map `"PHYSICAL_DISPLAY"` → `R.string.channel_status_physical_display`
  - Map `"ACCESSIBILITY"` → `R.string.channel_status_a11y`
  - Map `"APPLICATION_OVERLAY"` / `"NONE"` → `R.string.channel_status_app`

## 7. i18n strings

- [x] 7.1 Add to `app/src/main/res/values/strings.xml`:
  ```
  <string name="channel_status_physical_display">运行中 · 通道：PHYSICAL_DISPLAY (display-attached)</string>
  ```
- [x] 7.2 Add new toast (optional but useful for debug): `<string name="toast_fallback_to_legacy">已切换至兼容模式</string>`

## 8. Compile + smoke verification

- [x] 8.1 `QEMU_LD_PREFIX=/usr/x86_64-linux-gnu ./gradlew :app:assembleDebug` 成功
- [x] 8.2 `QEMU_LD_PREFIX=/usr/x86_64-linux-gnu ./gradlew :app:testDebugUnitTest` — 28 现有测试全 green
- [x] 8.3 `QEMU_LD_PREFIX=/usr/x86_64-linux-gnu ./gradlew :app:assembleRelease` 成功；APK ≤ 1.6 MB
- [x] 8.4 `lint :app` — 无 newApi violation（DisplayAttachedBackend 必须在 @RequiresApi 守护下）

## 9. PBT property tests

- [x] 9.1 Create `app/src/test/java/com/ccg/screenblocker/service/BackendSelectionTest.kt` 覆盖 P1（selection determinism）+ P7（API < 34 不加载新类）：参数化 sdkVersion × a11yState
- [x] 9.2 Create `app/src/test/java/com/ccg/screenblocker/service/FallbackTransparencyTest.kt` 覆盖 P4（fallback transparency）+ P5（no ping-pong）：mock OverlayBackend 实现，模拟 fallback 触发
- [x] 9.3 Create `app/src/test/java/com/ccg/screenblocker/service/BackendIdempotencyTest.kt` 覆盖 P2（attach idempotent）：用 fake backend 测调用计数

## 10. Spec compliance verification

- [x] 10.1 grep 验证：`OverlayService.kt` 不再直接调用 `windowManager.addView` / `removeViewImmediate` / `updateViewLayout`（除 FAB 路径外）— 全部由 backend 处理
- [x] 10.2 grep 验证：`DisplayAttachedBackend` 引用的 SurfaceControl / SurfaceControlViewHost / attachAccessibilityOverlayToDisplay 全部位于 `@RequiresApi(34)` 内
- [x] 10.3 grep 验证：FAB 相关代码（`attachFabIfNeeded` / `detachFabIfAttached` / FAB GestureDetector）零变更
- [x] 10.4 spec 验证：`runState`、`manualBypass`、`autoBypass`、`runtime_visible`、`fab_visible` 在 fallback 触发后字段不变（snapshot before/after）
