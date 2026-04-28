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

