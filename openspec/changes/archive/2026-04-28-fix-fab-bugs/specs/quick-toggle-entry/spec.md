# Spec Delta: quick-toggle-entry (NEW capability)

## ADDED Requirements

### Requirement: Discoverable launcher entry via activity-alias

A `<activity-alias>` named `.QuickToggleAlias` targeting `.QuickToggleActivity` SHALL be declared in the AndroidManifest with:

- `android:targetActivity=".QuickToggleActivity"`
- `android:enabled="true"`
- `android:exported="true"`
- `android:label="@string/quick_toggle_alias_label"` (zh-CN: "DeadZone 快捷切换"; en: "DeadZone Quick Toggle")
- `android:icon="@mipmap/ic_quick_toggle"`
- An `<intent-filter>` containing `<action android:name="android.intent.action.MAIN"/>` AND `<category android:name="android.intent.category.LAUNCHER"/>`

The original `QuickToggleActivity` declaration SHALL drop its non-standard `category.LAUNCHER_APP` filter.

#### Scenario: HyperOS app picker enumerates DeadZone entries

- **WHEN** the user opens "设置 → 更多设置 → 快捷手势 → 敲击背部 → 选择应用"
- **THEN** at least two entries with package `com.overbaker.deadzone` appear: "DeadZone" (main) and "DeadZone 快捷切换" (alias)

#### Scenario: PackageManager query

- **WHEN** any app calls `PackageManager.queryIntentActivities` with `Intent(ACTION_MAIN).addCategory(CATEGORY_LAUNCHER).setPackage("com.overbaker.deadzone")`
- **THEN** the returned list size is ≥ 2
- **AND** the list contains both `com.overbaker.deadzone/com.ccg.screenblocker.MainActivity` and `com.overbaker.deadzone/com.ccg.screenblocker.QuickToggleAlias`

### Requirement: Alias trampolines to atomic action

When `QuickToggleActivity` (or any alias targeting it) is launched, it SHALL:

1. Send exactly one `Intent(OverlayService::class).setAction(ACTION_TOGGLE_BYPASS_AND_GESTURE).putExtra(EXTRA_SOURCE, "quick_toggle")` to the service via `Context.startService(...)` (NOT `startForegroundService` — atomic action is not an enable command).
2. Call `finish()` immediately.
3. Apply `overridePendingTransition(0, 0)` to suppress launch animation.
4. NOT directly invoke `BlockerAccessibilityService` (the service centralizes that path via the action handler).

#### Scenario: User binds back-tap to alias

- **GIVEN** the user has set HyperOS 敲击背部 → "DeadZone 快捷切换"
- **WHEN** the user double-taps the back of the device
- **THEN** the screen does not flash; no UI is rendered
- **AND** the service receives the atomic-action intent
- **AND** `manualBypass` flips
- **AND** the gesture dispatch is attempted (succeeds if accessibility bound)

#### Scenario: Alias launched while service stopped

- **GIVEN** `runState == STOPPED`
- **WHEN** the alias is launched
- **THEN** the service receives the action but does NOT enter RUNNING
- **AND** a `Toast` shows `R.string.toast_blocker_not_running`
- **AND** the service calls `stopSelf()` if it was started solely by this command

### Requirement: Alias does not pollute Recents

- `<activity QuickToggleActivity>` SHALL retain `android:excludeFromRecents="true"` AND `android:noHistory="true"` AND `android:taskAffinity=""`
- The alias inherits these via `targetActivity`

#### Scenario: User invokes alias and checks Recents

- **WHEN** the user launches `QuickToggleAlias` (via back-tap, quick-ball, or tapping the second launcher icon)
- **THEN** no card for "DeadZone 快捷切换" appears in the Recents (Overview) screen
- **AND** the trampoline activity is not visible at any point during the launch

#### Scenario: Repeated alias invocations

- **GIVEN** the user has invoked the alias 5 times in succession
- **WHEN** the user opens Recents
- **THEN** Recents contains zero entries from `com.overbaker.deadzone` related to `QuickToggleAlias` or `QuickToggleActivity`
