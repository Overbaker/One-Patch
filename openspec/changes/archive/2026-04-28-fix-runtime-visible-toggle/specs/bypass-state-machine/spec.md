# Spec Delta: bypass-state-machine — runtime-visible live sync

## ADDED Requirements

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
