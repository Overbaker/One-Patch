# Tasks: fix-fab-bugs

## 1. State machine refactor (G4 + A1)

- [x] 1.1 Add `enum class RunState { STOPPED, ARMING, RUNNING }` at the top of `OverlayService.kt`
- [x] 1.2 Replace `private var isOverlayActive: Boolean` and `private var isArming: Boolean` with `@Volatile private var runState: RunState = STOPPED`
- [x] 1.3 Update all read sites (`LocalBinder.isRunning`, `notifyStateChanged`, `onConfigurationChanged`, `onStartCommand`) to derive boolean from `runState`
- [x] 1.4 Replace `private var isBypassing: Boolean` with `private @Volatile var manualBypass: Boolean = false` and `private @Volatile var autoBypass: Boolean = false`
- [x] 1.5 Add `private val effectiveBypass: Boolean get() = manualBypass || autoBypass`
- [x] 1.6 Implement `private fun applyBypassState()` as the sole sink that mutates: `OverlayView.bypassRuntime`, `LayoutParams.flags` (FLAG_NOT_TOUCHABLE), FAB drawable, FAB contentDescription, FGS notification
- [x] 1.7 Reset both `manualBypass` and `autoBypass` to `false` in `handleStop()`
- [x] 1.8 In `detectDisplacementAndBypass()`: early-return when `manualBypass == true`; otherwise set `autoBypass` and call `applyBypassState()`

## 2. Action API refactor (G2 + A2)

- [x] 2.1 Add constants in `OverlayService.companion`:
  - `ACTION_TOGGLE_BYPASS_AND_GESTURE = "com.ccg.screenblocker.action.TOGGLE_BYPASS_AND_GESTURE"`
  - `EXTRA_SOURCE = "com.ccg.screenblocker.extra.SOURCE"`
  - `ACTION_FAB_SET_VISIBLE = "com.ccg.screenblocker.action.FAB_SET_VISIBLE"`
  - `EXTRA_VISIBLE = "com.ccg.screenblocker.extra.VISIBLE"`
- [x] 2.2 Delete constants `ACTION_TOGGLE_BYPASS` and `ACTION_FAB_TOGGLE_VISIBILITY`
- [x] 2.3 Delete the function `OverlayService.toggleFabVisibility()` (the one that did `!sp.getBoolean(...)`)
- [x] 2.4 Implement `private fun handleToggleBypassAndGesture(intent: Intent)`:
  1. If `runState != RUNNING` → Toast `R.string.toast_blocker_not_running` + `stopSelf()` if started solely by this command + return
  2. If `overlayView == null` → `Log.e + Toast(R.string.toast_overlay_not_attached_yet)` + return
  3. Otherwise: `manualBypass = !manualBypass` → `applyBypassState()` → `BlockerAccessibilityService.get()?.triggerOneHandedGesture() ?: Toast(R.string.fab_long_press_a11y_required)`
- [x] 2.5 Implement `private fun handleFabSetVisible(intent: Intent)`:
  1. If `!intent.hasExtra(EXTRA_VISIBLE)` → `Log.e` + return (do NOT default)
  2. Read `desired = intent.getBooleanExtra(EXTRA_VISIBLE, false)`
  3. If `desired && runState == RUNNING && !fabAttached` → call `attachFabIfNeeded()`
  4. If `!desired && fabAttached` → call `detachFabIfAttached()`
  5. Otherwise: no-op
- [x] 2.6 Update `onStartCommand` switch to route the two new actions; remove old action branches

## 3. FAB lifecycle binding (G4)

- [x] 3.1 Remove the `if (fabPref()) attachFabIfNeeded()` line from `handleEnable()`
- [x] 3.2 In `addOrUpdateOverlay()` success branch (where `attachedViaA11y = useA11y` is set), add: `runState = RunState.RUNNING; if (fabPref()) attachFabIfNeeded()`
- [x] 3.3 In `handleStop()`, ensure FAB is detached BEFORE overlay (move `detachFabIfAttached()` to top of `handleStop`, before `removeOverlayIfAttached()`)
- [x] 3.4 In `attachFabIfNeeded()`, guard with `if (runState != RunState.RUNNING) { Log.w(TAG, "refusing FAB attach in state $runState"); return }`

## 4. FAB single-tap atomic + debounce (G1)

- [x] 4.1 Remove `longPressHandler`, `longPressRunnable`, and `longPressFired` variables from `attachFabIfNeeded()`
- [x] 4.2 Add `private var lastFabClickAtMs: Long = 0L` field in `OverlayService`
- [x] 4.3 In FAB `onTouchListener` ACTION_UP non-drag branch: replace `handleToggleBypass()` with debounce + dispatch:
  ```
  val now = SystemClock.uptimeMillis()
  if (now - lastFabClickAtMs < 300L) return@setOnTouchListener
  lastFabClickAtMs = now
  startService(Intent(this@OverlayService, OverlayService::class.java)
      .setAction(ACTION_TOGGLE_BYPASS_AND_GESTURE)
      .putExtra(EXTRA_SOURCE, "fab"))
  iv.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
  ```
- [x] 4.4 Replace `iv.setImageResource(...)` calls with `TransitionDrawable` cross-fade (200 ms): build a `TransitionDrawable(arrayOf(currentDrawable, newDrawable))`, set as image, call `startTransition(200)`
- [x] 4.5 Update `iv.contentDescription` on every drawable change to either `R.string.fab_cd_pause` or `R.string.fab_cd_resume`
- [x] 4.6 Move `updateFabIcon()` to be called only from `applyBypassState()` (no other call sites)

## 5. MainActivity switch & status (G2 + A1 surfacing)

- [x] 5.1 In `MainActivity.setupOperationPanel()`, replace the `switchFab.setOnCheckedChangeListener` body:
  - Write `settingsPrefs.edit().putBoolean("fab_visible", isChecked).apply()` (single writer; no negation anywhere else)
  - If service is running → `startService(Intent(...).setAction(ACTION_FAB_SET_VISIBLE).putExtra(EXTRA_VISIBLE, isChecked))`
  - Else → show snackbar `R.string.hint_fab_appears_when_running`
- [x] 5.2 In `MainActivity.renderState()`, when service is running and `serviceBinder?.effectiveBypass == true`:
  - If `manualBypass` → `tv_status.setText(R.string.status_paused_manual)`
  - Else if `autoBypass` → `tv_status.setText(R.string.status_paused_auto)`
- [x] 5.3 Expose `LocalBinder.isManualBypass(): Boolean` and `LocalBinder.isAutoBypass(): Boolean`

## 6. Activity alias (G3)

- [x] 6.1 In `AndroidManifest.xml` `<activity name=".QuickToggleActivity">`: REMOVE the `<intent-filter>` block entirely (drop `android.intent.category.LAUNCHER_APP`)
- [x] 6.2 In `AndroidManifest.xml` after `<activity QuickToggleActivity>`, add:
  ```xml
  <activity-alias
      android:name=".QuickToggleAlias"
      android:targetActivity=".QuickToggleActivity"
      android:enabled="true"
      android:exported="true"
      android:icon="@mipmap/ic_quick_toggle"
      android:roundIcon="@mipmap/ic_quick_toggle_round"
      android:label="@string/quick_toggle_alias_label"
      android:taskAffinity="">
      <intent-filter>
          <action android:name="android.intent.action.MAIN" />
          <category android:name="android.intent.category.LAUNCHER" />
      </intent-filter>
  </activity-alias>
  ```
- [x] 6.3 Create adaptive icon `app/src/main/res/mipmap-anydpi-v26/ic_quick_toggle.xml`:
  ```xml
  <?xml version="1.0" encoding="utf-8"?>
  <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
      <background android:drawable="@drawable/ic_quick_toggle_background" />
      <foreground android:drawable="@drawable/ic_quick_toggle_foreground" />
  </adaptive-icon>
  ```
  Plus `ic_quick_toggle_round.xml` (identical content).
- [x] 6.4 Create `app/src/main/res/drawable/ic_quick_toggle_background.xml` (vector, secondary color fill)
- [x] 6.5 Create `app/src/main/res/drawable/ic_quick_toggle_foreground.xml` (vector wrapping `ic_fab_pause`, white tint)

## 7. Strings (i18n)

- [x] 7.1 Add to `app/src/main/res/values/strings.xml`:
  - `<string name="quick_toggle_alias_label">DeadZone 快捷切换</string>`
  - `<string name="status_paused_manual">已暂停（手动）· 点击 FAB 恢复</string>`
  - `<string name="status_paused_auto">已暂停（检测到屏幕变换）· 屏幕复位后自动恢复</string>`
  - `<string name="toast_blocker_not_running">请先在主界面启用屏蔽</string>`
  - `<string name="toast_overlay_not_attached_yet">屏蔽层尚未就绪，请稍候</string>`
  - `<string name="hint_fab_appears_when_running">悬浮按钮将在屏蔽启用时显示</string>`
  - `<string name="fab_cd_pause">暂停屏蔽</string>`
  - `<string name="fab_cd_resume">恢复屏蔽</string>`

## 8. QuickToggleActivity simplification (A2)

- [x] 8.1 Remove the line `BlockerAccessibilityService.get()?.triggerOneHandedGesture()` from `QuickToggleActivity.onCreate()`
- [x] 8.2 Replace `ContextCompat.startForegroundService(...)` with `startService(...)` (atomic action is not an enable command)
- [x] 8.3 Use `ACTION_TOGGLE_BYPASS_AND_GESTURE` action and put `EXTRA_SOURCE = "quick_toggle"`

## 9. Notification refresh

- [x] 9.1 In `applyBypassState()`, after view mutations: `currentArea?.let { area -> NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, NotificationHelper.buildRunning(this, area, manualBypass = manualBypass, autoBypass = autoBypass)) }`
- [x] 9.2 Update `NotificationHelper.buildRunning(...)` signature to accept the two booleans; subtext format: empty if both false; `"已暂停（手动）"` or `"已暂停（自动）"` otherwise

## 10. Compile + smoke verification

- [x] 10.1 Build debug APK: `./gradlew :app:assembleDebug` — must succeed without warnings about deprecated symbols introduced by these changes
- [x] 10.2 Build release APK: `./gradlew :app:assembleRelease` — APK ≤ 1.6 MB
- [x] 10.3 Verify alias discovery: `adb shell pm dump com.overbaker.deadzone | grep -E "MAIN|LAUNCHER" | head -20` — should list MainActivity AND QuickToggleAlias
- [x] 10.4 Verify intent constants present: `apksigner verify` + manual `aapt2 dump xmltree` for AndroidManifest
- [x] 10.5 PBT spot-check: write a JUnit/Robolectric test exercising at least P3 (toggle round-trip) and P5 (single-writer determinism) before declaring done

## 11. PBT Property tests

- [x] 11.1 Create `app/src/test/java/com/ccg/screenblocker/service/BypassStateMachineTest.kt` covering P1, P2, P3, P11
- [x] 11.2 Create `app/src/androidTest/java/com/ccg/screenblocker/AliasDiscoverabilityTest.kt` covering P8 (PackageManager query)
- [x] 11.3 Create `app/src/test/java/com/ccg/screenblocker/service/IntentValidationTest.kt` covering P9 (EXTRA_VISIBLE strictness) and P7 (no-implicit-enable)
