# floating-button Specification

## Purpose
TBD - created by archiving change fix-fab-bugs. Update Purpose after archive.
## Requirements
### Requirement: Atomic single-tap action

The floating action button (FAB) SHALL execute, on every single user tap that completes within 300 ms of `ACTION_DOWN` and within the 300 ms global debounce window, the following two operations as one user-perceived action:

1. Flip `manualBypass` (in-memory state inside `OverlayService`).
2. Invoke `BlockerAccessibilityService.triggerOneHandedGesture()` if and only if the accessibility service instance is bound; otherwise emit a `Toast` advising the user to enable the accessibility service.

The FAB SHALL NOT support long-press, double-tap, or gesture-based alternative behaviors.

#### Scenario: User taps FAB while blocker is RUNNING and accessibility service is bound

- **WHEN** the user single-taps the FAB
- **THEN** `manualBypass` flips and `triggerOneHandedGesture()` is called once
- **AND** FAB icon cross-fades (200 ms, `DecelerateInterpolator`) between `ic_fab_pause` and `ic_play`
- **AND** the FGS notification text refreshes to reflect the new bypass mode

#### Scenario: User taps FAB while blocker is RUNNING but accessibility service is unbound

- **WHEN** the user single-taps the FAB
- **THEN** `manualBypass` flips
- **AND** a `Toast` appears with text `R.string.fab_long_press_a11y_required`
- **AND** no gesture dispatch is attempted

#### Scenario: User taps FAB twice within 300 ms

- **WHEN** two `ACTION_UP` events fire within 300 ms of each other
- **THEN** only the first tap performs the atomic action
- **AND** the second is silently dropped (debounce)

### Requirement: Lifecycle bound to OverlayService RunState

The FAB SHALL exist (be added to `WindowManager`) **if and only if** all three conditions hold:

1. `OverlayService.runState == RunState.RUNNING`
2. `SharedPreferences.getBoolean("fab_visible", true) == true`
3. The runtime overlay view has been successfully attached (`addOrUpdateOverlay` returned without throwing)

Whenever any of these three conditions becomes false, the FAB SHALL be removed from `WindowManager` immediately.

#### Scenario: User starts blocker

- **GIVEN** `runState == STOPPED`, `fab_visible == true`
- **WHEN** the user taps "启用屏蔽"
- **THEN** `runState` transitions STOPPED → ARMING → RUNNING
- **AND** FAB is attached only after the RUNNING transition completes

#### Scenario: User stops blocker

- **GIVEN** `runState == RUNNING` and FAB attached
- **WHEN** the user taps "暂停屏蔽" or notification "立即停止"
- **THEN** FAB is detached **before** the runtime overlay is detached

#### Scenario: User flips visibility switch while service stopped

- **GIVEN** `runState == STOPPED`
- **WHEN** the user toggles the main-UI FAB switch
- **THEN** only `SharedPreferences` is updated
- **AND** no FAB widget is attached
- **AND** a snackbar shows `R.string.hint_fab_appears_when_running`

### Requirement: Visibility command via declarative SET intent

The OverlayService SHALL accept `ACTION_FAB_SET_VISIBLE` with required boolean extra `EXTRA_VISIBLE`. The service SHALL NOT accept any toggle-style action for FAB visibility.

#### Scenario: Valid SET visible=true

- **WHEN** OverlayService receives `Intent(ACTION_FAB_SET_VISIBLE).putExtra(EXTRA_VISIBLE, true)` AND `runState == RUNNING`
- **THEN** the FAB is attached if not already attached
- **AND** no `SharedPreferences` write occurs from the service

#### Scenario: Valid SET visible=false

- **WHEN** OverlayService receives `Intent(ACTION_FAB_SET_VISIBLE).putExtra(EXTRA_VISIBLE, false)`
- **THEN** the FAB is detached if attached, otherwise no-op
- **AND** no `SharedPreferences` write occurs from the service

#### Scenario: Malformed SET (missing extra)

- **WHEN** OverlayService receives `Intent(ACTION_FAB_SET_VISIBLE)` without `EXTRA_VISIBLE`
- **THEN** the service logs an `Log.e` line containing the action name
- **AND** no widget mutation occurs
- **AND** the command does not crash the service

### Requirement: Drag persistence

The FAB SHALL be draggable. After `ACTION_UP` ending a drag (movement > `ViewConfiguration.scaledTouchSlop`), the new (x, y) coordinates SHALL be persisted to `SharedPreferences` keys `fab_x` and `fab_y`. Subsequent FAB attachments SHALL read from these keys.

#### Scenario: User drags FAB to new position

- **WHEN** the user drags the FAB with a final displacement > touchSlop
- **THEN** drag is recognized (no click is fired)
- **AND** new x, y are saved to prefs
- **AND** the next service start places the FAB at saved (x, y)

### Requirement: Visual state reflects bypass

The FAB ImageView's drawable SHALL reflect the current `effectiveBypass` (`manualBypass || autoBypass`) value:

- `effectiveBypass == false` → drawable = `ic_fab_pause`, contentDescription = `R.string.fab_cd_pause`
- `effectiveBypass == true` → drawable = `ic_play`, contentDescription = `R.string.fab_cd_resume`

Changes between drawables SHALL animate via cross-fade for 200 ms.

#### Scenario: Bypass flips false → true

- **GIVEN** FAB shows `ic_fab_pause` and `effectiveBypass == false`
- **WHEN** `manualBypass` flips to `true` via FAB tap
- **THEN** drawable cross-fades from `ic_fab_pause` to `ic_play` over 200 ms
- **AND** contentDescription updates to `R.string.fab_cd_resume`

#### Scenario: Bypass flips true → false

- **GIVEN** FAB shows `ic_play` and both `manualBypass == true`, `autoBypass == false`
- **WHEN** `manualBypass` flips to `false` via second FAB tap
- **THEN** drawable cross-fades from `ic_play` to `ic_fab_pause` over 200 ms
- **AND** contentDescription updates to `R.string.fab_cd_pause`

### Requirement: Accessibility Custom Action Parity

The FAB SHALL expose a TalkBack-accessible custom action equivalent to double-tap behavior.

#### Scenario: TalkBack user invokes custom action

- **GIVEN** TalkBack is enabled and FAB is attached
- **WHEN** the user navigates to FAB and selects the custom action labeled `R.string.fab_a11y_action_trigger_gesture`
- **THEN** the action SHALL invoke `ACTION_FORCE_BYPASS_AND_GESTURE` on `OverlayService`
- **AND** the resulting state SHALL be identical to a sighted user's double-tap (`manualBypass=true` + `triggerOneHandedGesture()`)

#### Scenario: Custom action does not interfere with single-tap semantics

- **WHEN** TalkBack is enabled
- **THEN** TalkBack's standard single-tap mapping (focus + click) SHALL still invoke FAB's `onSingleTapConfirmed` path (toggle manualBypass)
- **AND** the custom action SHALL be the sole accessible path to double-tap behavior

### Requirement: Drag State Releases Tap Detector

When FAB drag begins, the in-flight `GestureDetector` state SHALL be cancelled to prevent stale tap classification on subsequent press.

#### Scenario: Drag transition cancels tap detector

- **GIVEN** `ACTION_DOWN` was forwarded to `tapDetector`
- **WHEN** `ACTION_MOVE` exceeds touch slop and `dragging` flips from `false` to `true`
- **THEN** the system SHALL inject one synthetic `ACTION_CANCEL` event into `tapDetector` at that transition point
- **AND** subsequent fresh `ACTION_DOWN` after `ACTION_UP` SHALL be classified by `tapDetector` from a clean state

### Requirement: Description Reflects Actual Tap Semantics

The user-facing description string for the FAB SHALL accurately describe single-tap and double-tap actions.

#### Scenario: fab_desc string describes both actions

- **GIVEN** the user reads the FAB description in MainActivity
- **THEN** the description SHALL describe single-tap as bypass toggle
- **AND** SHALL describe double-tap as gesture replay
- **AND** SHALL NOT mention long-press as an active interaction

