# bypass-state-machine Specification

## Purpose
TBD - created by archiving change fix-fab-bugs. Update Purpose after archive.
## Requirements
### Requirement: Decomposed bypass state with single sink

`OverlayService` SHALL expose three private fields:

- `@Volatile private var manualBypass: Boolean = false`
- `@Volatile private var autoBypass: Boolean = false`
- `private val effectiveBypass: Boolean get() = manualBypass || autoBypass`

All mutations to bypass-related view state (OverlayView.bypassRuntime, LayoutParams.flags, FAB icon, contentDescription, FGS notification text) SHALL flow through one private method `applyBypassState()`. No other code path SHALL mutate these view fields directly.

The two boolean fields SHALL NOT be persisted to `SharedPreferences`. Both fields SHALL be reset to `false` whenever `runState` transitions to `STOPPED`.

#### Scenario: Manual sets manualBypass

- **WHEN** the FAB tap or alias triggers atomic action
- **THEN** `manualBypass = !manualBypass`, `applyBypassState()` is called exactly once
- **AND** `autoBypass` is not mutated by this path

#### Scenario: Displacement detector sets autoBypass

- **WHEN** `detectDisplacementAndBypass()` observes `|deltaY| > dp(50)`
- **THEN** `autoBypass = true`, `applyBypassState()` is called
- **AND** `manualBypass` is not mutated by this path

#### Scenario: Both flags true

- **GIVEN** `manualBypass = true`
- **WHEN** displacement also triggers `autoBypass = true`
- **THEN** `effectiveBypass == true`
- **AND** view state remains "bypassed" (no re-render needed because state is unchanged after the second call → must be idempotent)

### Requirement: Idempotent applyBypassState

`applyBypassState()` SHALL be safe to call any number of times in succession; given unchanged `(manualBypass, autoBypass)` inputs, all observable outputs (FAB icon resource ID, OverlayView.bypassRuntime flag, LayoutParams.flags bitmask, NotificationManager.notify call payload) SHALL be byte-for-byte identical.

#### Scenario: Repeated calls converge

- **WHEN** `applyBypassState()` is called 100 times with identical state
- **THEN** all 100 invocations produce identical view state mutations (or zero — both acceptable)
- **AND** at most one `WindowManager.updateViewLayout` is needed (subsequent calls may short-circuit if state matches last write)

### Requirement: Atomic action when service stopped

When `OverlayService` receives `ACTION_TOGGLE_BYPASS_AND_GESTURE` with `runState != RUNNING`, the service SHALL:

1. Emit a `Toast(R.string.toast_blocker_not_running)`.
2. NOT mutate `manualBypass`.
3. NOT call `triggerOneHandedGesture()`.
4. NOT auto-transition to ARMING/RUNNING.
5. Call `stopSelf()` if the service was started solely by this command.

#### Scenario: Alias triggered while blocker stopped

- **GIVEN** `runState == STOPPED`
- **WHEN** atomic action arrives
- **THEN** Toast appears, no state mutation, service may stop

### Requirement: RunState single-source-of-truth

`OverlayService` SHALL declare `enum class RunState { STOPPED, ARMING, RUNNING }` and expose a private `@Volatile var runState: RunState = STOPPED`.

The legacy `isOverlayActive: Boolean` and `isArming: Boolean` fields SHALL be removed. All public APIs (`LocalBinder.isRunning()`) SHALL be derived from `runState`.

#### Scenario: State transitions

- `STOPPED → ARMING` on `handleEnable()` after permission check passes
- `ARMING → RUNNING` after `addOrUpdateOverlay()` returns successfully
- `ARMING → STOPPED` if `addOrUpdateOverlay()` throws (recovery path)
- `RUNNING → STOPPED` on `handleStop()` or `onDestroy()`

### Requirement: Click handler explicit failure feedback

`OverlayService.handleToggleBypassAndGesture()` SHALL NOT silently return when the runtime overlay view is unexpectedly absent. Instead it SHALL:

1. Log via `Log.e(TAG, "atomic action requested but overlay not attached, runState=$runState")`
2. Show `Toast(R.string.toast_overlay_not_attached_yet)`
3. Return without mutating state

#### Scenario: Click before overlay attached

- **GIVEN** `runState == ARMING` (within the 500 ms attach delay)
- **WHEN** atomic action arrives
- **THEN** Log.e is emitted with the runState
- **AND** Toast appears
- **AND** no state mutation

### Requirement: Runtime-Visible Declarative Set Command

`OverlayService` SHALL expose a declarative intent action that updates the live `OverlayView.runtimeVisible` field without restarting the overlay.

#### Scenario: Service publishes ACTION_RUNTIME_VISIBLE_SET

- **WHEN** an external caller starts `OverlayService` with action `com.ccg.screenblocker.action.RUNTIME_VISIBLE_SET`
- **AND** the intent carries extra `com.ccg.screenblocker.extra.VISIBLE` of type Boolean
- **THEN** the service SHALL set `overlayView.runtimeVisible` to the extra value
- **AND** the service SHALL NOT mutate `runState`, `manualBypass`, `autoBypass`, FAB attachment, or notification

#### Scenario: Missing EXTRA_VISIBLE is rejected

- **WHEN** `ACTION_RUNTIME_VISIBLE_SET` arrives without extra `EXTRA_VISIBLE`
- **THEN** the service SHALL log an error and drop the command
- **AND** SHALL NOT mutate any view state

#### Scenario: View not attached is silent no-op

- **WHEN** `ACTION_RUNTIME_VISIBLE_SET` arrives while `overlayView == null` (e.g., `RunState.ARMING` or `STOPPED`)
- **THEN** the service SHALL silently no-op
- **AND** the next fresh `addOrUpdateOverlay()` SHALL read the pref `runtime_visible` to seed the new view

### Requirement: Single-Writer Persistence for runtime_visible

The SharedPreferences key `runtime_visible` SHALL have exactly one writer: `MainActivity.switchRuntimeVisible.setOnCheckedChangeListener`.

#### Scenario: Switch toggle writes pref and pushes command

- **WHEN** the user toggles `switchRuntimeVisible` to value `b`
- **THEN** MainActivity SHALL write `settings_prefs.runtime_visible = b`
- **AND** if `isServiceRunning == true`, MainActivity SHALL start `OverlayService` with action `ACTION_RUNTIME_VISIBLE_SET` and extra `EXTRA_VISIBLE = b`
- **AND** if `isServiceRunning == false`, MainActivity SHALL NOT start the service

### Requirement: Backend Selection by Capability

The runtime overlay attachment SHALL select the highest-capability backend available at fresh-attach time, falling back transparently when prerequisites fail.

#### Scenario: API 34+ with a11y service bound selects DisplayAttachedBackend

- **GIVEN** `Build.VERSION.SDK_INT >= 34`
- **AND** `BlockerAccessibilityService.get() != null`
- **WHEN** `OverlayService.selectBackend()` is invoked at fresh attach
- **THEN** the function SHALL return `DisplayAttachedBackend`
- **AND** the resulting overlay SHALL be attached via `AccessibilityService.attachAccessibilityOverlayToDisplay(Display.DEFAULT_DISPLAY, surfaceControl)`

#### Scenario: API < 34 always selects WindowManagerBackend

- **GIVEN** `Build.VERSION.SDK_INT < 34`
- **WHEN** `selectBackend()` is invoked
- **THEN** the function SHALL return `WindowManagerBackend`
- **AND** the `DisplayAttachedBackend` class SHALL NOT be loaded by the class loader

#### Scenario: a11y service unbound selects WindowManagerBackend

- **GIVEN** `Build.VERSION.SDK_INT >= 34`
- **AND** `BlockerAccessibilityService.get() == null`
- **WHEN** `selectBackend()` is invoked
- **THEN** the function SHALL return `WindowManagerBackend` (TYPE_APPLICATION_OVERLAY path)

### Requirement: Backend Interface Contract

All overlay backends SHALL implement a uniform interface that hides attach mechanism while preserving runtime behavior.

#### Scenario: Backend interface exposes 4 methods

- **WHEN** any class implements `OverlayBackend`
- **THEN** it SHALL provide: `attach(area: BlockArea): Boolean` / `update(area: BlockArea)` / `detach()` / `isAttached(): Boolean`

#### Scenario: attach is idempotent for the same area

- **GIVEN** `backend.attach(area)` returned true
- **WHEN** `backend.attach(area)` is called again with the same area
- **THEN** the resulting state SHALL be observationally equivalent (same `isAttached()`, same on-screen geometry)

#### Scenario: detach releases all resources

- **WHEN** `detach()` returns
- **THEN** `isAttached()` SHALL be false
- **AND** for `DisplayAttachedBackend`, the underlying `SurfaceControl`, `SurfaceControlViewHost`, and any `Transaction` references SHALL be released
- **AND** subsequent `dumpsys SurfaceFlinger` SHALL not list the overlay surface

### Requirement: Drift-Triggered Fallback

When the active backend is `DisplayAttachedBackend` and the embedded `OverlayView` reports persistent positional drift, the system SHALL fall back to `WindowManagerBackend`.

#### Scenario: Persistent drift triggers fallback

- **GIVEN** `activeBackend == DisplayAttachedBackend`
- **AND** `OverlayView.viewTreeObserver.OnPreDrawListener` reports `getLocationOnScreen()[1] - currentArea.topPx > dp(50)` for ≥ 2 consecutive frames
- **WHEN** the second drift frame is observed
- **THEN** `triggerFallbackToWindowManager("displaced")` SHALL be invoked
- **AND** the active backend SHALL detach the SurfaceControl
- **AND** SHALL switch to `WindowManagerBackend.attach(currentArea)`
- **AND** state machine fields (`runState`, `manualBypass`, `autoBypass`, `runtime_visible`, `fab_visible`) SHALL remain unchanged

#### Scenario: Single-frame drift does not trigger fallback

- **WHEN** drift exceeds threshold for exactly 1 frame, then returns to baseline
- **THEN** fallback SHALL NOT be triggered (hysteresis guard)

#### Scenario: No backend ping-pong within session

- **GIVEN** `triggerFallbackToWindowManager` was previously invoked in the current `RunState.RUNNING` epoch
- **WHEN** a subsequent transient drift is detected
- **THEN** the system SHALL NOT attempt re-promoting to `DisplayAttachedBackend`
- **AND** SHALL only re-evaluate backend selection on the next `RunState.STOPPED → RUNNING` transition

### Requirement: a11y Service Disconnection Triggers Fallback

If the active backend depends on the accessibility service and that service unbinds, the overlay SHALL fall back rather than become orphan.

#### Scenario: a11y onUnbind with active DisplayAttachedBackend

- **GIVEN** `activeBackend == DisplayAttachedBackend`
- **WHEN** `BlockerAccessibilityService.onUnbind()` fires
- **THEN** the service SHALL signal `OverlayService` (via existing `BlockerAccessibilityService.get()` reset semantics or a new in-process callback)
- **AND** `OverlayService` SHALL invoke `triggerFallbackToWindowManager("a11y_unbound")`

### Requirement: Backend Failure Transparency

A failure to attach via `DisplayAttachedBackend` SHALL never leave the user without a working overlay.

#### Scenario: SurfaceControl construction throws

- **GIVEN** `DisplayAttachedBackend.attach(area)` is called
- **WHEN** any of `SurfaceControl.Builder.build()` / `SurfaceControlViewHost.setView()` / `attachAccessibilityOverlayToDisplay()` throws or returns failure
- **THEN** the exception SHALL be caught
- **AND** `DisplayAttachedBackend.attach` SHALL return `false`
- **AND** `OverlayService` SHALL invoke `WindowManagerBackend.attach(area)` immediately
- **AND** the user SHALL see the overlay rendered via the legacy path within ≤ 1 ENABLE_DELAY_MS cycle

### Requirement: API Level Class Loading Safety

Classes referencing API 34-only types SHALL not be loaded on API < 34 devices.

#### Scenario: API 26 device does not crash on app start

- **GIVEN** `Build.VERSION.SDK_INT == 26`
- **WHEN** the app launches and `OverlayService.onCreate` runs
- **THEN** no `NoClassDefFoundError` / `VerifyError` SHALL occur
- **AND** `DisplayAttachedBackend` class SHALL NOT be loaded
- **AND** `selectBackend()` SHALL return `WindowManagerBackend` without referencing API 34 types

### Requirement: Channel Status Tri-State

The MainActivity status text SHALL reflect which backend is currently active when the service is running.

#### Scenario: PHYSICAL_DISPLAY backend active

- **WHEN** `serviceBinder.isRunning() == true` AND `serviceBinder.getActiveBackend() == "PHYSICAL_DISPLAY"`
- **THEN** `tv_channel_status` SHALL display `R.string.channel_status_physical_display` ("运行中 · 通道：PHYSICAL_DISPLAY (display-attached)")

#### Scenario: ACCESSIBILITY trusted backend active

- **WHEN** `serviceBinder.getActiveBackend() == "ACCESSIBILITY"`
- **THEN** `tv_channel_status` SHALL display `R.string.channel_status_a11y` (existing string)

#### Scenario: APPLICATION_OVERLAY backend active

- **WHEN** `serviceBinder.getActiveBackend() == "APPLICATION_OVERLAY"`
- **THEN** `tv_channel_status` SHALL display `R.string.channel_status_app` (existing string)

### Requirement: FAB Path Unchanged

The floating action button attachment path SHALL be unaffected by the backend selection.

#### Scenario: FAB always attached via WindowManager

- **GIVEN** any `selectBackend()` outcome
- **WHEN** `attachFabIfNeeded()` runs
- **THEN** the FAB SHALL use `WindowManager.addView(fabView, TYPE_APPLICATION_OVERLAY)` regardless of overlay backend
- **AND** FAB single-tap / double-tap / drag / accessibility custom action behavior SHALL remain identical to floating-button spec

### Requirement: Configuration Change Continuity

`onConfigurationChanged` SHALL preserve user-perceptible overlay continuity across rotation / display-size changes.

#### Scenario: Rotation rebuilds backend within 1 frame

- **GIVEN** `runState == RUNNING` and `activeBackend == DisplayAttachedBackend`
- **WHEN** `onConfigurationChanged` fires (e.g., rotation)
- **THEN** the service SHALL invoke `activeBackend.detach()` then `activeBackend.attach(rescaledArea)` on the same main-thread frame
- **AND** the overlay SHALL be visible at the rescaled area within ≤ 1 frame

### Requirement: Reverse-Compensation Mode

When the runtime overlay is on `WindowManagerBackend` and the `auto_bypass_displaced` preference is `true`, the service SHALL detect SurfaceFlinger transform displacement and apply reverse `LayoutParams.x/y` adjustments to keep the blocker rectangle pinned at physical screen coordinates.

#### Scenario: Compensation triggers on persistent drift

- **GIVEN** `runState == RUNNING`
- **AND** `activeBackend` is `WindowManagerBackend`
- **AND** SharedPreferences `auto_bypass_displaced` is `true`
- **AND** `manualBypass == false`
- **WHEN** `OverlayView.viewTreeObserver.OnPreDrawListener` reports `getLocationOnScreen()` whose `(x, y)` differs from `(area.leftPx, area.topPx)` by more than `dp(2)` in either axis
- **THEN** the service SHALL update `compensatedDeltaX -= driftX` and `compensatedDeltaY -= driftY`
- **AND** SHALL invoke `WindowManagerBackend.update(area)` with effective LayoutParams positions `(area.leftPx + compensatedDeltaX, area.topPx + compensatedDeltaY)`
- **AND** SHALL NOT set `autoBypass = true`

#### Scenario: Hysteresis dead zone prevents oscillation

- **GIVEN** `runState == RUNNING` and reverse-compensation is active
- **WHEN** drift |Δx| ≤ `dp(2)` AND |Δy| ≤ `dp(2)`
- **THEN** `compensatedDeltaX/Y` SHALL remain unchanged
- **AND** `WindowManagerBackend.update` SHALL NOT be invoked

#### Scenario: Cumulative compensation across continuous transform

- **GIVEN** SurfaceFlinger applies a one-handed-mode transform that incrementally shifts the display-area downward
- **WHEN** N consecutive frames each report drift `δy`
- **THEN** `compensatedDeltaY` SHALL converge to `-Σ δy` such that the next frame's drift falls within hysteresis dead zone

### Requirement: anti_transform Switch Semantic Bifurcation

The `auto_bypass_displaced` preference SHALL bifurcate runtime behavior between reverse-compensation and legacy step-aside.

#### Scenario: anti_transform = true → reverse compensation

- **GIVEN** `auto_bypass_displaced == true`
- **WHEN** drift > `dp(2)` is detected on `WindowManagerBackend`
- **THEN** reverse compensation triggers (per Requirement above)
- **AND** `autoBypass` SHALL remain `false` (not set by the detector)

#### Scenario: anti_transform = false → legacy step-aside

- **GIVEN** `auto_bypass_displaced == false`
- **WHEN** drift > `dp(50)` is detected (legacy threshold preserved)
- **THEN** the service SHALL set `autoBypass = true` and call `applyBypassState()` (legacy v1 behavior)
- **AND** `compensatedDeltaX/Y` SHALL remain `0`

### Requirement: Compensation State Lifecycle

`compensatedDeltaX` and `compensatedDeltaY` SHALL be reset to zero at well-defined lifecycle transitions.

#### Scenario: handleStop resets compensation

- **WHEN** `OverlayService.handleStop()` runs
- **THEN** `compensatedDeltaX == 0 && compensatedDeltaY == 0` after the function returns

#### Scenario: Fresh attach starts from zero

- **WHEN** `addOrUpdateOverlay(area)` enters fresh-attach branch
- **THEN** `compensatedDeltaX = 0; compensatedDeltaY = 0` BEFORE installing the displaceDetector

#### Scenario: onConfigurationChanged resets compensation

- **WHEN** `onConfigurationChanged` rescales `currentArea`
- **THEN** `compensatedDeltaX = 0; compensatedDeltaY = 0`
- **AND** subsequent detector invocations measure drift relative to the new `currentArea` (not the pre-rotation values)

### Requirement: Backend-Specific Compensation

Reverse-compensation SHALL apply only to `WindowManagerBackend`. `DisplayAttachedBackend` continues its prior behavior (drift triggers fallback to `WindowManagerBackend`, after which compensation engages).

#### Scenario: DisplayAttachedBackend drift triggers fallback then compensation

- **GIVEN** `activeBackend` is `DisplayAttachedBackend`
- **WHEN** drift > `dp(50)` is detected for ≥ 2 consecutive frames
- **THEN** `triggerFallbackToWindowManager("displaced")` runs (per physical-display-overlay spec, unchanged)
- **AND** the new `WindowManagerBackend` is installed
- **AND** subsequent detector invocations on `WindowManagerBackend` apply reverse compensation

#### Scenario: DisplayAttachedBackend does not directly compensate

- **GIVEN** `activeBackend` is `DisplayAttachedBackend`
- **WHEN** any drift is detected
- **THEN** `compensatedDeltaX / compensatedDeltaY` SHALL remain `0`
- **AND** no `LayoutParams` mutation SHALL occur on the SurfaceControl-based path

### Requirement: Position Coordinate Semantic Layers

The system SHALL operate within Android's **three-layer position coordinate model**, recognizing that only ViewRootImpl-reported position is queryable via public API.

#### Scenario: Three layers explicitly distinguished

- **GIVEN** the runtime overlay is attached on any backend
- **THEN** the design SHALL recognize three distinct position concepts:
  - **(a) Physical pixel position**: hardware-level pixel coordinates; **NO public API exposes this**
  - **(b) SurfaceFlinger composited position**: compositor-level layer position post-transform; **NO third-party API queries this** (only `dumpsys SurfaceFlinger` / Winscope traces, requires `adb`)
  - **(c) ViewRootImpl-reported position**: returned by `View.getLocationOnScreen()`; this is the **only signal accessible to third-party apps**

#### Scenario: Detection signal accuracy is ROM-dependent

- **GIVEN** any ROM applies a SurfaceFlinger compositor transform (e.g., HyperOS one-handed mode)
- **WHEN** the service queries `OverlayView.getLocationOnScreen()`
- **THEN** the returned coordinate may reflect the transform **partially** (HyperOS observed: ≥ dp(50) drift triggers v1 displaceDetector successfully) or **fully** (Pixel reachability mode reportedly fully reflected)
- **AND** reverse compensation targets eliminating the **reported** drift, NOT the unmeasurable hardware-pixel drift
- **AND** any residual visual mismatch (1-20% un-reflected portion) is an accepted trade-off

#### Scenario: No reflection / hidden API access

- **GIVEN** the service detects ViewRootImpl-reported drift
- **THEN** the implementation SHALL NOT use reflection on `ViewRootImpl.mTranslator`, `mInvCompatScale`, or any `@hide` / `@UnsupportedAppUsage` field
- **AND** SHALL NOT call `Class.forName("android.view.ViewRootImpl")` or any equivalent reflective access pattern

### Requirement: One-Handed Mode Detection Signal

The service SHALL detect SurfaceFlinger display-area transform via a single signal source: `OverlayView.viewTreeObserver` `OnPreDrawListener` + `getLocationOnScreen()` drift comparison against the expected `(area.leftPx, area.topPx)`.

#### Scenario: Detector signal is exclusively position drift

- **GIVEN** the overlay is attached and `runState == RUNNING`
- **WHEN** SurfaceFlinger applies a display-area transform (e.g., HyperOS one-handed mode)
- **THEN** the service SHALL observe drift via `OverlayView.getLocationOnScreen()` returning a position different from `(expectedX, expectedY)`
- **AND** SHALL NOT rely on `Configuration` changes, `WindowInsets`, `DisplayManager.DisplayListener`, `ContentObserver` on `Settings.System`, or `View.matrix` (these signals do NOT fire on HyperOS one-handed mode)

#### Scenario: Detection latency bounded by vsync

- **WHEN** an external transform changes the overlay's effective screen position
- **THEN** detection occurs at the next `OnPreDraw` callback (≤ 1 vsync, ~16ms at 60Hz / ~8ms at 120Hz)
- **AND** subsequent compensation is applied within the same callback's `WindowManagerBackend.update()` invocation

#### Scenario: Detector listens on OverlayView, not decorView

- **WHEN** installing the displaceDetector
- **THEN** it SHALL be attached to `OverlayView.viewTreeObserver` (the blocker rectangle's own ViewTreeObserver)
- **AND** SHALL NOT be attached to the activity's `decorView` or any unrelated parent view

#### Scenario: Detector lifecycle bound to overlay attachment

- **WHEN** `removeOverlayIfAttached()` runs OR `onDestroy` runs
- **THEN** `viewTreeObserver.removeOnPreDrawListener(displaceDetector)` SHALL be invoked

### Requirement: manualBypass Suppresses Compensation

`manualBypass = true` SHALL early-return from the detector before any drift evaluation.

#### Scenario: manualBypass true blocks all detector logic

- **GIVEN** `manualBypass == true`
- **WHEN** `detectDisplacementAndBypass` runs
- **THEN** the function SHALL return immediately
- **AND** SHALL NOT modify `compensatedDeltaX/Y`
- **AND** SHALL NOT modify `autoBypass`

