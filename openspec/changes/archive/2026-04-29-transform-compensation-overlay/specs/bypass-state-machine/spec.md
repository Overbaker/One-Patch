# Spec Delta: bypass-state-machine — transform-compensation-overlay

## ADDED Requirements

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
