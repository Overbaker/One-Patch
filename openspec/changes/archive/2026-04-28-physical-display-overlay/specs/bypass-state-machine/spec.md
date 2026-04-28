# Spec Delta: bypass-state-machine — physical-display-overlay

## ADDED Requirements

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
