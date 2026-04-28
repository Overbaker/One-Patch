# Design: fix-fab-bugs

## Multi-Model Synthesis

| Source | Headline conclusion |
|---|---|
| Codex (backend) | Replace toggle-style commands with declarative `SET_*` commands; runtime bypass state lives only in memory; expose Quick Toggle via `<activity-alias>` rather than `PackageManager` enable flips |
| Gemini (frontend) | Atomic single-tap is mandatory; cross-fade icon ⏸↔▶; main UI surfaces "why blocker is paused"; alias gets distinct icon (`ic_fab_pause` glyph + secondary tint) |
| Convergence | Both agree on: action `ACTION_TOGGLE_BYPASS_AND_GESTURE`; in-memory `@Volatile` bypass fields; static alias; eliminate ALL `!` re-negation; `RunState` enum |

## Architecture Decision

### Rationale
The four bugs share one root cause: **shared mutable state with multiple writers**.

- **State writers** today: `MainActivity` (writes pref), `OverlayService.toggleFabVisibility()` (writes pref again with negation), `handleToggleBypass` (mutates `isBypassing`), `detectDisplacementAndBypass` (also mutates `isBypassing`).
- **State readers** today: `attachFabIfNeeded`, `addOrUpdateOverlay`, `applyAntiTransform`, FAB onClick.
- **Net result**: any input can leave the system in an undefined state.

The fix collapses all writers into typed commands and one in-memory state machine.

### Rejected Alternatives
1. **Persist bypass state in `SharedPreferences`** — rejected: bypass is transient; persisting would resurrect "Paused" after a process restart.
2. **`StateFlow` for bypass** — rejected: no UI consumer subscribes to bypass live; adds lifecycle complexity for zero payoff.
3. **`PackageManager.setComponentEnabledSetting` to dynamically expose alias** — rejected: HyperOS launcher pickers cache resolved activities at package-scan time; runtime enablement is OEM-flaky.
4. **Separate `ACTION_FAB_SHOW` / `ACTION_FAB_HIDE`** — rejected: doubles public-API surface; inconsistent with single typed command.
5. **Long-press for one-handed gesture** — rejected: 600ms gate kills atomic UX; user shouldn't need to know two interaction modes.

### Assumptions
- Single-process app (UI + service share JVM); `@Volatile` is sufficient for cross-thread bypass-state visibility.
- All `WindowManager.add/update/removeView` calls execute on the main thread.
- HyperOS app pickers (敲击背部 / 悬浮球) enumerate `ACTION_MAIN + CATEGORY_LAUNCHER` activities at package-scan time.
- `BlockerAccessibilityService.dispatchGesture` is best-effort; success is **not** guaranteed and **not** required for guarantee G1 to hold.
- A second home-screen icon for the alias is acceptable (documented in README).

### Potential Side Effects
- Existing bound back-tap configurations (if user already bound to current `MainActivity`) continue to work — main entry unchanged.
- A second launcher icon "DeadZone 快捷切换" appears after upgrade.
- `ic_fab_pause` glyph reused for alias icon → drawable size budget unaffected.

## Resolved Constraints (Step-3 Audit Output)

| # | Ambiguity | Required Constraint |
|---|---|---|
| C1 | FAB icon transition mode | **Cross-fade animation, 200ms duration, `DecelerateInterpolator`** (instant swap looks like glitch on HyperOS per Gemini) |
| C2 | FAB rapid-tap protection | **300ms debounce window**: subsequent taps within 300ms of last successful action are ignored |
| C3 | `EXTRA_VISIBLE` validation | **Service must call `intent.hasExtra(EXTRA_VISIBLE)` before reading**; missing → log error, drop command, do not default |
| C4 | RunState representation | **`enum class RunState { STOPPED, ARMING, RUNNING }`** in `OverlayService` companion or top-level |
| C5 | Alias icon drawable | **`ic_fab_pause`** (existing pause-glyph vector); tinted via `colorSecondary` adaptive icon background |
| C6 | Alias label (i18n) | **zh-CN: "DeadZone 快捷切换"** / **en: "DeadZone Quick Toggle"** |
| C7 | Atomic action name | **`com.ccg.screenblocker.action.TOGGLE_BYPASS_AND_GESTURE`** (drops legacy `ACTION_TOGGLE_BYPASS`) |
| C8 | EXTRA_SOURCE allowed values | **`"fab"`, `"quick_toggle"`, `"unknown"`** (default for missing) — used only for log/telemetry |
| C9 | Atomic-action when STOPPED | **Service: `Toast(R.string.toast_blocker_not_running)` + `stopSelf()`**; never auto-start the overlay |
| C10 | Bypass state storage | **Two private `@Volatile Boolean`** in `OverlayService`: `manualBypass`, `autoBypass`; reset on `handleStop()` |
| C11 | Main-UI status text mapping | When effective bypass = true → `R.string.status_paused_manual` if `manualBypass`, else `R.string.status_paused_auto`; both → `R.string.status_paused_manual` (manual wins) |
| C12 | Displacement listener while manualBypass | **Keep listener attached**; in `detectDisplacementAndBypass`, early-return when `manualBypass == true` (CPU saving negligible vs add/remove churn) |
| C13 | Notification refresh on toggle | **Yes**: any `applyBypassState()` invocation rebuilds the FGS notification via `NotificationManager.notify(NOTIFICATION_ID, …)` |
| C14 | FAB position with multiple displays/landscape | **Out of scope**; activity is `screenOrientation="portrait"`; single-display assumed |
| C15 | Click handler when overlayView is null | **Replace silent `return`** with `Log.e(TAG, …) + Toast(R.string.toast_overlay_not_attached_yet)`; do not exception-throw |
| C16 | When user toggles main-UI FAB switch while service stopped | **Allowed**: write pref + show snackbar `R.string.hint_fab_appears_when_running`; do not start service |
| C17 | Removal of `ACTION_FAB_TOGGLE_VISIBILITY` | **Yes**: replaced by `ACTION_FAB_SET_VISIBLE` + `EXTRA_VISIBLE`; legacy const deleted |
| C18 | TalkBack contentDescription | FAB `contentDescription` updates on bypass change: `R.string.fab_cd_pause` or `R.string.fab_cd_resume` |

## PBT Properties (Step-4 Output)

| ID | Name | Definition | Boundary | Falsification |
|---|---|---|---|---|
| **P1** | Idempotency of `applyBypassState` | Calling `applyBypassState()` N≥1 times with unchanged `(manualBypass, autoBypass)` produces identical observable view state (FAB icon, OverlayView.bypassRuntime, LayoutParams.flags, notification.contentText) | N ∈ [1, 100] | Snapshot view-state after first call vs N-th call; assert identical bytes |
| **P2** | Commutativity of bypass sources | For any sequence of `setManual(b1); setAuto(b2)` vs `setAuto(b2); setManual(b1)`, final `effectiveBypass == b1 ‖ b2` | b1, b2 ∈ {true, false} (4 combos × 2 orderings) | Run all 8 sequences; final view-state must match across each pair |
| **P3** | Toggle round-trip | After N FAB clicks (no other events): `manualBypass == (N mod 2 == 1)` AND total `triggerOneHandedGesture` invocations == N | N ∈ [0, 50] | Mock click; assert two integer invariants |
| **P4** | FAB lifecycle invariant | Always: `fabAttached == (runState == RUNNING && fabPrefValue == true)` | All 9 combos of (RunState × fabPref ∈ {T,F,unset}) | Construct each state programmatically; assert bool |
| **P5** | Switch single-writer determinism | After any sequence of `MainActivity.fabSwitch.setChecked(b1, b2, …, bN)`, `prefs.getBoolean("fab_visible") == bN` | N ∈ [1, 20]; sequence has random b values | Replay sequence; assert final pref equals last write |
| **P6** | Atomic-action equivalence | `FAB.click()` and `QuickToggleActivity.onCreate()` both lead to exactly one invocation of `OverlayService.performAtomicToggleAndGesture(source)` with `source ∈ {"fab","quick_toggle"}` | 2 entry points × manual/auto/quick_toggle source values | Trace method calls via test double; assert one entry → one call |
| **P7** | No implicit enable | When `runState == STOPPED`, sending `ACTION_TOGGLE_BYPASS_AND_GESTURE` results in: (a) `runState` unchanged; (b) `fabView == null`; (c) Toast emitted; (d) `stopSelf()` called | Single state | Drive intent; verify post-state |
| **P8** | LAUNCHER-alias discoverability | `PackageManager.queryIntentActivities(MAIN+LAUNCHER)` for `com.overbaker.deadzone` returns ≥ 2 ResolveInfo entries: one for `MainActivity`, one for the alias `QuickToggleAlias` | Single query | Assert size ≥ 2 and component names contain both |
| **P9** | EXTRA_VISIBLE strictness | Sending `ACTION_FAB_SET_VISIBLE` without extra → no FAB attach/detach + Log.E; with `EXTRA_VISIBLE=true` → attach if RUNNING; with `=false` → detach if attached | 3 inputs (no-extra, true, false) × 2 RunStates (RUNNING, STOPPED) = 6 cases | For each case, verify post-state of `fabAttached` |
| **P10** | Notification ↔ bypass sync | After every `applyBypassState()` call, `NotificationManager.notify(NOTIFICATION_ID, n)` is called with `n.extras.getString(EXTRA_TEXT)` containing the bypass-mode token (`"manual"` / `"auto"` / `"none"`) | All 4 combos of (manualBypass, autoBypass) | Spy on NotificationManager; assert text token |
| **P11** | Debounce 300ms | If two FAB clicks arrive within < 300ms, the second is silently dropped (no extra `manualBypass` flip, no extra gesture dispatch) | timing ∈ [0, 600ms] in 50ms steps | Inject timed clicks; assert flip-count |
| **P12** | Cross-fade transition completeness | After bypass state change, FAB ImageView's drawable transitions from old to new; transition completes within 250ms (animation 200ms + safety margin) | Single change | Mock `Choreographer`; advance time; assert final drawable.constantState matches expected |

## Implementation Plan (high-level → maps to tasks.md)

1. **State machine refactor** (G4 + A1):
   - Add `enum class RunState`
   - Replace `isOverlayActive` + `isArming` with single `private @Volatile var runState`
   - Replace `isBypassing` with `manualBypass` + `autoBypass` + computed `effectiveBypass`
   - Introduce single `applyBypassState()` sink
2. **Action API refactor** (G2 + A2):
   - Add `ACTION_TOGGLE_BYPASS_AND_GESTURE` + `EXTRA_SOURCE`
   - Add `ACTION_FAB_SET_VISIBLE` + `EXTRA_VISIBLE`
   - Delete `ACTION_TOGGLE_BYPASS`, `ACTION_FAB_TOGGLE_VISIBILITY`
   - Strict `hasExtra` validation
3. **FAB lifecycle binding** (G4):
   - Move `attachFabIfNeeded()` from `handleEnable()` to the success branch of `addOrUpdateOverlay()`
   - Replace `handleToggleBypass.overlayView ?: return` with explicit error path
4. **FAB single-tap atomic** (G1):
   - Remove `longPressHandler` + `longPressRunnable` from `attachFabIfNeeded()`
   - On `ACTION_UP` (non-drag, < 300ms since DOWN, > 300ms since last successful click): call `performAtomicToggleAndGesture("fab")`
   - Cross-fade `setImageDrawable` via `TransitionDrawable` (200ms)
5. **MainActivity switch wire-up** (G2):
   - Remove the `!` line in `OverlayService.toggleFabVisibility()` entirely (drop the function)
   - `setOnCheckedChangeListener` writes pref + sends `ACTION_FAB_SET_VISIBLE` with EXTRA_VISIBLE
   - Add hint snackbar for service-stopped case
6. **Activity alias** (G3):
   - Manifest: change `<activity QuickToggleActivity>` to drop `LAUNCHER_APP` filter; add `<activity-alias android:name=".QuickToggleAlias" android:targetActivity=".QuickToggleActivity" android:enabled="true" android:exported="true" android:label="@string/quick_toggle_alias_label" android:icon="@mipmap/ic_quick_toggle">` with `<intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter>`
   - Drawable: `mipmap-anydpi-v26/ic_quick_toggle.xml` adaptive icon (background = secondary container, foreground = `ic_fab_pause`)
   - Strings: `quick_toggle_alias_label` (zh + en)
7. **Status surfacing** (A1 visibility):
   - MainActivity binds `tv_status` to `(runState, manualBypass, autoBypass)` triple → render proper string
8. **QuickToggleActivity simplification** (A2 reuse):
   - Replace dual-call (start service + a11y) with single `startService(ACTION_TOGGLE_BYPASS_AND_GESTURE, EXTRA_SOURCE="quick_toggle")`

## Considerations

| Aspect | Note |
|---|---|
| Performance | All bypass-state writes are O(1); `applyBypassState()` issues at most 1 `WindowManager.updateViewLayout` + 1 `NotificationManager.notify`; no allocations in click hot-path |
| Accessibility | TalkBack reads dynamic FAB `contentDescription` reflecting next-action ("Resume blocker" vs "Pause blocker") |
| Maintainability | All bypass mutations funnel through `applyBypassState()` — single place to add future flags (`imeBypass`, `appWhitelistBypass`) |
| Testing | Pure-Kotlin unit tests for state machine + Robolectric for FAB attach/detach + integration test for `PackageManager.queryIntentActivities` (P8) |
| Compatibility | All APIs used are SDK ≥ 26 (TransitionDrawable since API 1; activity-alias since API 1) |
| Rollback | Pure additive change; the previous `ACTION_TOGGLE_BYPASS` action is removed but no external consumer (only our own activities) referenced it |
