# Design: record-gesture-playback

## Multi-Model Synthesis

| Source | Headline conclusion |
|---|---|
| Codex (backend) | dedicated `RecordGestureActivity` + dispatchTouchEvent capture; `GestureRepository` follows `SharedPrefsBlockAreaRepository` schema-key pattern; multi-stroke `StrokeDescription` chain with preserved inter-stroke timings; rescale on replay; reject (don't auto-fallback) on dispatchGesture==false |
| Gemini (frontend) | full-screen scrim + countdown 3000 ms + Choreographer-coalesced trail; immediate overwrite inside activity / dialog confirmation at MainActivity entry; 19 strings (zh-CN+en); test-gesture Snackbar action post-save |
| Convergence | recording isolation from OverlayService state; absolute-px + saved-display rescaling; hard-reject out-of-bounds with Snackbar; lineTo (no quadTo smoothing); single-pointer mandatory; payload encoded as compact string in SharedPreferences (no JSON framework) |

## Architecture Decision

### Rationale
The OEM one-handed-mode trigger gesture is non-portable across ROMs. Hardcoding any geometry will fail on at least one common device. The only deterministic source of truth is **the user's own demonstrated gesture on their actual device**. Recording captures this once at setup time; playback faithfully reproduces it.

Recorder lives in a dedicated Activity (not OverlayService) because:
- Recording is setup-time, runtime-state-irrelevant
- Activity provides natural lifecycle isolation (`onPause` = abort)
- No coupling to `RunState` / `manualBypass` / FAB
- Reuses `FullscreenEditorActivity` immersive pattern

Playback path remains in `BlockerAccessibilityService.triggerOneHandedGesture()`. The function's caller contract (Boolean return, called from `OverlayService.handleToggleBypassAndGesture`) is unchanged — this is a transparent internal refactor.

### Rejected Alternatives
1. **Trusted overlay recording** — rejected: unnecessary coupling to OverlayService lifecycle; Activity isolation is sufficient and simpler.
2. **`View.setOnTouchListener` on root** — rejected: child views could intercept; `Activity.dispatchTouchEvent()` is canonical for whole-window capture.
3. **Persist every `ACTION_MOVE`** — rejected: 240 Hz digitizers generate 14 400 events / 60 s; produces unwieldy SharedPrefs payloads. 16 ms / 2 dp decimation preserves shape, bounds size.
4. **Smooth replay path with `quadTo`** — rejected: distorts demonstrated geometry; OEM bottom-edge gestures are angular and short.
5. **JSON / Room serialization** — rejected: project convention is flat SharedPreferences keys + manual encoding (see `SharedPrefsBlockAreaRepository`); no new dependency.
6. **Auto-fallback to hardcoded if recorded `dispatchGesture` returns false** — rejected: false = scheduling failure, not geometry failure; double-injection risks contradictory ROM responses.
7. **Trusted overlay capture** for navbar inclusion — rejected: even trusted overlay can be filtered by ROM in nav-gesture region; UX accepts navbar exclusion via "start above the system bar" instruction.

### Assumptions
- Activity orientation locked to `portrait` (consistent with `FullscreenEditorActivity` + the rest of the app)
- `DisplayHelper.getRealDisplaySizePx` returns logical screen size; `MotionEvent.rawX/rawY` are display-absolute
- `StrokeDescription.startTime` is gesture-relative (per Android docs)
- Single-process app: `GestureRepository` writes from MainActivity Activity Result, reads from `BlockerAccessibilityService` — same JVM, SharedPrefs read-after-write coherency guaranteed
- `dispatchGesture()` on API 26+ supports up to 10 strokes / 60 s total (documented; verified via local SDK android.jar API surface)

### Potential Side Effects
- **APK size**: < 5 KB (one Activity + Repository + layout + drawable + 19 strings)
- **Storage**: gesture_prefs ≤ 32 KB (capped by validation: 10 strokes × 500 points × ~30 bytes/point worst case)
- **No state machine impact**: `OverlayService` source unchanged
- **No FAB / spec impact**: existing `floating-button` ban on long-press unaffected
- **MainActivity layout**: one new TonalButton + status TextView in existing card

## Resolved Constraints

| # | Ambiguity | Required Constraint |
|---|---|---|
| C1 | Recording UI form | **Dedicated full-screen `RecordGestureActivity`** (portrait, immersive, theme=`Theme.TouchBlocker.Fullscreen`). Not trusted overlay. |
| C2 | Touch capture mechanism | **`Activity.dispatchTouchEvent()`** + `MotionEvent.rawX/rawY`. Not `View.setOnTouchListener`. |
| C3 | Sampling/decimation | **Keep DOWN+UP unconditionally; coalesce MOVE if Δt < 16 ms AND Δdist < 2 dp** |
| C4 | Replay path construction | **`Path.moveTo(first); lineTo(each subsequent)`**. No `quadTo` smoothing. |
| C5 | Multi-stroke representation | **One `StrokeDescription` per ACTION_DOWN..ACTION_UP segment**; `startTime` = first-point timestamp relative to gesture start |
| C6 | Stroke duration calculation | **`max(1, lastPoint.t - firstPoint.t)` ms** (StrokeDescription requires ≥ 1 ms) |
| C7 | Coordinate storage | **Absolute px (Float) + saved display size (Int)**; `(x_px, y_px, t_ms_from_gesture_start)` tuples |
| C8 | Replay rescaling | **`xReplay = xRec * (currentW / savedW)`**; identity when `currentW == savedW` (skip multiplication) |
| C9 | Validation thresholds | **strokes ≤ 10 ∧ Σ(strokeDuration) ≤ 60 000 ms ∧ each stroke duration ≥ 1 ms ∧ each stroke ≥ 2 points ∧ no second pointer ever** |
| C10 | Out-of-bounds reaction | **Hard reject + Snackbar `R.string.toast_gesture_too_long` / `..._too_short` / `..._multitouch_rejected`**; do NOT silently truncate |
| C11 | Multi-touch detection | **Abort on `ACTION_POINTER_DOWN`** (any second pointer) |
| C12 | Non-touch input source | **Reject events not from `InputDevice.SOURCE_TOUCHSCREEN`** with `R.string.toast_keyboard_rejected` Snackbar |
| C13 | Persistence format | **`gesture_prefs` SharedPreferences**, schema_version=1, flat keys: `KEY_SCHEMA_VERSION`, `KEY_SAVED_DISPLAY_W`, `KEY_SAVED_DISPLAY_H`, `KEY_STROKE_COUNT`, `KEY_STROKES_PAYLOAD` |
| C14 | Payload encoding | **Custom compact string**: strokes joined by `;`; points within stroke joined by `,`; per-point `x:y:t` (Float×3). No JSON. |
| C15 | Empty-state fallback | **Hardcoded 4 px / 80 ms downward stroke** (existing implementation) when GestureRepository.has() == false OR load() returns null |
| C16 | dispatchGesture replay returns false | **Log + return false; do NOT auto-fallback** to hardcoded |
| C17 | Countdown timing | **3000 ms total (3-2-1, each 1000 ms)** with `View.announceForAccessibility` per number |
| C18 | Trail rendering | **`Choreographer`-coalesced invalidate** (≤ 60 fps); color=`R.color.rect_handle`; persists until user clicks 完成 / 重新开始 |
| C19 | Re-record entry confirmation | **AlertDialog at MainActivity entry** when prior recording exists; **immediate overwrite inside Activity** on 重新开始 |
| C20 | Test gesture access | **Post-save Snackbar with 测试 action** (5 s) → invokes `BlockerAccessibilityService.triggerOneHandedGesture()` |
| C21 | Activity orientation | **`portrait`** (manifest + Activity attribute) |
| C22 | onPause behavior | **Discard transient session, no save**; user returning resumes idle state |
| C23 | onBackPressed behavior | **Cancel + finish** without saving; uses `OnBackPressedDispatcher` |
| C24 | Floating-button spec compliance | **No FAB long-press / double-tap introduced**; FAB single-tap unchanged |
| C25 | bypass-state-machine spec compliance | **Recorder must NOT mutate `runState`, `manualBypass`, `autoBypass`, FAB visibility, block-area** |
| C26 | MainActivity entry placement | **TonalButton under `switch_anti_transform` card**, label=`R.string.btn_record_gesture` |
| C27 | Status text rendering | **TextView under entry button**: "已录制 / 未录制 · 使用默认手势" |
| C28 | Recorder ↔ FAB interaction during foreground | **No special handling**; if user taps FAB while recorder foreground, OverlayService behavior unchanged; `onPause` discards transient session |

## PBT Properties

| ID | Name | Definition | Boundary | Falsification |
|---|---|---|---|---|
| **P1** | GestureRepository round-trip | `repo.save(g); repo.load() == g` modulo encoding normalization | strokeCount ∈ [1, 10], pointsPerStroke ∈ [2, 500], displaySize > 0 | Generate valid gesture, save, reload; assert deep equality on display fields, stroke count, point order, all coordinates and timestamps |
| **P2** | Replay scaling identity | `currentDisplay == savedDisplay` → every replay point equals recorded point exactly | savedW=currentW, savedH=currentH for arbitrary valid gestures | Build replay path with equal display sizes; assert point-for-point equality (no float drift) |
| **P3** | Replay scaling proportionality | `currentDisplay ≠ savedDisplay` → `xReplay == xRec * (currentW/savedW)` (and y analogously) | scale ratios ∈ [0.5×, 2.0×] per axis independently | Generate gestures + display pairs; replay; assert each axis matches expected ratio within `1e-3` |
| **P4** | Multi-stroke timeline preservation | For each stroke i, `StrokeDescription[i].startTime == strokes[i].points[0].t` (gesture-relative) | 1 to 10 strokes with arbitrary non-negative inter-stroke gaps | Generate gesture with known gaps, build GestureDescription, assert each stroke startTime |
| **P5** | Validation boundary | Validator accepts the inclusive legal region; rejects first value outside | strokes 10/11; Σduration 60000/60001 ms; strokeDuration 1/0 ms; pointCount 2/1 | Construct gestures on each threshold; assert accept/reject matches definition |
| **P6** | Single-pointer hard reject | Any `ACTION_POINTER_DOWN` (pointerCount → 2) before save → no persistence | second pointer introduced during DOWN, MOVE, near UP of any stroke | Feed MotionEvent sequences with pointerCount transitions; assert repository unchanged |
| **P7** | Fallback selection determinism | `triggerOneHandedGesture()` uses recorded iff `repo.load()` returns valid; otherwise hardcoded | repository states: valid, absent, corrupt schema, corrupt payload, invalid display | Stub repo for each state; assert which builder path is invoked before dispatchGesture |
| **P8** | Recorder isolation | Recorder lifecycle (launch/cancel/save) does NOT mutate `OverlayService.runState`, `manualBypass`, `autoBypass`, FAB visibility, block-area | OverlayService in STOPPED, ARMING, RUNNING | Snapshot service+pref state before/after recorder lifecycle; assert equality |
| **P9** | Single-pointer event-level | At any event, `event.pointerCount == 1` while recording is active | event stream with `actionMasked == ACTION_POINTER_DOWN` triggers session abort | Inject event sequences with pointer count transitions; assert recording state == ABORTED |
| **P10** | Duration upper bound | `lastEventTime - firstEventTime ≤ 60 000 ms` for any saved gesture | t_diff = 60 000 vs 60 001 | Construct gesture at threshold, attempt save, assert reject path triggered for over-limit |
| **P11** | Non-touch input rejection | `!event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)` → no event recorded | mouse, keyboard, stylus | Inject MotionEvent with non-touchscreen source; assert recording state unchanged + Snackbar emitted |

## Implementation Plan (high-level → maps to tasks.md)

1. **Data model + Repository** (P1, P5, P7) — `model/RecordedGesture.kt`, `data/GestureRepository.kt`, encoding, validation
2. **RecordGestureActivity** (P6, P8, P9, P10, P11) — phase machine, dispatchTouchEvent capture, decimation, trail view, validation gates, Snackbar errors
3. **BlockerAccessibilityService refactor** (P2, P3, P4, P7) — loader / replay builder / fallback builder / rescale
4. **MainActivity entry** — TonalButton, status text, ActivityResultLauncher, re-record dialog, post-save test Snackbar
5. **AndroidManifest registration** — RecordGestureActivity exported=false, portrait, fullscreen theme
6. **Strings (i18n)** — 19 zh-CN + en strings
7. **Compile + smoke verification** — `assembleDebug` + `assembleRelease ≤ 1.6 MB` + manifest dump
8. **PBT tests** — RepositoryTest, ReplayBuilderTest, ValidatorTest, FallbackTest

## Considerations

| Aspect | Note |
|---|---|
| Performance | Decimation 16 ms / 2 dp ⇒ ≤ 60 points/s/stroke; payload < 32 KB worst case; trail render Choreographer-coalesced ≤ 60 fps |
| Accessibility | Activity title set; `announceForAccessibility` on enter + each countdown number; non-touch rejected with explicit Snackbar |
| Maintainability | Repository follows existing `SharedPrefsBlockAreaRepository` pattern; Activity follows `FullscreenEditorActivity` template |
| Testing | Pure-Kotlin JUnit for Repository + Validator + ReplayBuilder; Robolectric optional (project doesn't have it; not introduced) |
| Compatibility | All APIs are SDK 26+ (project minSdk=26); `GestureDescription`, `StrokeDescription`, `dispatchGesture` all available |
| Rollback | Pure additive: revert MainActivity layout + `triggerOneHandedGesture` + delete new files = previous behavior |
| Privacy | Gesture stored locally; no network; no PII |
| Security | Activity exported=false; no third-party broadcast |
