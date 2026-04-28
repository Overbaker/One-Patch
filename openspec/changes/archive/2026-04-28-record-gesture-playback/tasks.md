# Tasks: record-gesture-playback

## 1. Data model + GestureRepository (P1, P5, P7)

- [x] 1.1 Create `app/src/main/java/com/ccg/screenblocker/model/RecordedGesture.kt` with: `data class RecordedGesture(val savedDisplayWidthPx: Int, val savedDisplayHeightPx: Int, val strokes: List<Stroke>)` and `data class Stroke(val points: List<Point>) { val durationMs: Long get() = max(1L, points.last().tMs - points.first().tMs) }` and `data class Point(val xPx: Float, val yPx: Float, val tMs: Long)`
- [x] 1.2 Create `app/src/main/java/com/ccg/screenblocker/data/GestureRepository.kt` with companion: `PREFS_NAME="gesture_prefs"`, `SCHEMA_VERSION=1`, keys `KEY_SCHEMA_VERSION="schema_version"`, `KEY_SAVED_DISPLAY_W="saved_w"`, `KEY_SAVED_DISPLAY_H="saved_h"`, `KEY_STROKE_COUNT="stroke_count"`, `KEY_STROKES_PAYLOAD="strokes"`
- [x] 1.3 Implement `fun has(): Boolean = prefs.contains(KEY_STROKES_PAYLOAD) && prefs.getInt(KEY_SCHEMA_VERSION, -1) == SCHEMA_VERSION`
- [x] 1.4 Implement `fun save(g: RecordedGesture)`: validates via `validate(g)`; throws `IllegalArgumentException` on failure; writes 5 keys atomically via `edit { ... }`
- [x] 1.5 Implement `private fun validate(g: RecordedGesture)`: assert `strokes.size in 1..10`; assert `strokes.sumOf(Stroke::durationMs) <= 60000L`; assert each `stroke.points.size >= 2`; assert each `stroke.durationMs >= 1L`; assert `savedDisplayWidthPx > 0 && savedDisplayHeightPx > 0`
- [x] 1.6 Implement `fun load(): RecordedGesture?`: returns null if `!has()` OR schema mismatch; on parse exception → `clear()` and return null
- [x] 1.7 Implement `fun clear()`: `edit { remove(*all 5 keys) }`
- [x] 1.8 Implement payload encoding `private fun encode(strokes: List<Stroke>): String` = strokes joined by `";"`; points by `","`; per-point `"x:y:t"` (Float.toString × 2 + Long.toString)
- [x] 1.9 Implement payload decoding `private fun decode(payload: String): List<Stroke>` = inverse of encode; throws on malformed

## 2. RecordGestureActivity (P6, P8, P9, P10, P11)

- [x] 2.1 Create `app/src/main/java/com/ccg/screenblocker/RecordGestureActivity.kt` extending `AppCompatActivity`; theme `Theme.TouchBlocker.Fullscreen`; orientation portrait via manifest
- [x] 2.2 Define `enum class Phase { COUNTDOWN, RECORDING, REVIEW, ABORTED }` as `private @Volatile var phase`
- [x] 2.3 In `onCreate`: inflate layout `R.layout.activity_record_gesture` via ViewBinding; setup immersive flags (mirror FullscreenEditorActivity); set Activity title `R.string.record_gesture_title`; `announceForAccessibility(R.string.a11y_record_announce)`; start countdown
- [x] 2.4 In `onResume`: re-apply immersive flags; do NOT auto-restart recording
- [x] 2.5 In `onPause`: cancel countdown handler; reset phase to ABORTED; clear in-memory session; do NOT mutate repository
- [x] 2.6 Override `onBackPressed` (via `OnBackPressedDispatcher`): `setResult(RESULT_CANCELED); finish()`
- [x] 2.7 Implement countdown: 3→2→1, each 1000 ms via `Handler.postDelayed`; update countdown TextView; `announceForAccessibility(number.toString())`; transition to RECORDING when 0 reached; show "录制中..." pill
- [x] 2.8 Override `dispatchTouchEvent(event)`: if `phase == RECORDING` AND `event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)` → handle; else if non-touch + IDLE → show Snackbar `toast_keyboard_rejected`; else `super.dispatchTouchEvent(event)`
- [x] 2.9 Implement event handler: track `activeStroke: MutableList<Point>?` and `completedStrokes: MutableList<Stroke>`; handle ACTION_DOWN (start new stroke, append first point), ACTION_MOVE (append OR coalesce by 16ms+2dp threshold), ACTION_UP (finalize stroke, append to completedStrokes, transition to REVIEW if user taps 完成), ACTION_POINTER_DOWN (abort + Snackbar `toast_multitouch_rejected`), ACTION_CANCEL (abort)
- [x] 2.10 Implement decimation: keep DOWN/UP unconditionally; for MOVE, compute `dt = event.eventTime - lastPoint.tEvent`, `dDist = hypot(rawX - lastPoint.x, rawY - lastPoint.y) in dp`; if `dt < 16 && dDist < 2 dp` → replace last; else append
- [x] 2.11 Custom view `GestureTrailView` (inner class or sibling): renders `Path` from completedStrokes + activeStroke; color `R.color.rect_handle`; stroke width `dp(3)`; uses `Choreographer.getInstance().postFrameCallback` to coalesce invalidate; persists until phase reset
- [x] 2.12 Bottom buttons: `btn_retry` (重新开始) clears in-memory session + restarts countdown; `btn_save` (完成) validates → on success persists via `repo.save()` + `setResult(RESULT_OK)` + finish; on validation fail → Snackbar with reason
- [x] 2.13 Top-end cancel button: `setResult(RESULT_CANCELED); finish()`
- [x] 2.14 Compute `tMsFromGestureStart` using gesture-relative offset: `gestureStartMs = first ACTION_DOWN.eventTime`; per point `tMs = event.eventTime - gestureStartMs`
- [x] 2.15 Build final `RecordedGesture(savedDisplayWidthPx=DisplayHelper.getRealDisplaySizePx(this).first, ..., strokes=completedStrokes)` before save

## 3. Layout: activity_record_gesture.xml

- [x] 3.1 Create `app/src/main/res/layout/activity_record_gesture.xml` with FrameLayout root (match_parent, background `#80000000`)
- [x] 3.2 Add `GestureTrailView` (custom view, match_parent)
- [x] 3.3 Add top instruction LinearLayout (vertical, gravity top|center_horizontal, marginTop=safe_area): TextView `record_gesture_instruction` + countdown/pill TextView (id=`tv_countdown_pill`)
- [x] 3.4 Add ImageButton id=`btn_cancel` (top|end, ic_close, contentDescription=`record_gesture_btn_cancel`, transparent)
- [x] 3.5 Add bottom LinearLayout (horizontal, gravity bottom|center_horizontal): MaterialButton id=`btn_retry` (style=`Widget.Material3.Button.OutlinedButton`, text=`record_gesture_btn_retry`, disabled in COUNTDOWN) + MaterialButton id=`btn_save` (style=`Widget.Material3.Button`, text=`record_gesture_btn_save`, disabled until phase==REVIEW)

## 4. BlockerAccessibilityService refactor (P2, P3, P4, P7)

- [x] 4.1 Add lazy `private val gestureRepository by lazy { GestureRepository(this) }`
- [x] 4.2 Add `private fun loadRecordedGestureOrNull(): RecordedGesture? = gestureRepository.load()`
- [x] 4.3 Add `private fun buildRecordedGestureDescription(rec: RecordedGesture, currentW: Int, currentH: Int): GestureDescription`: per stroke build Path with moveTo(rescale(p0)) + lineTo(rescale(pi)); StrokeDescription(path, startTime=points[0].tMs, duration=max(1L, points.last().tMs - points[0].tMs)); chain via Builder.addStroke per stroke
- [x] 4.4 Add `private fun rescale(p: Point, currentW: Int, currentH: Int, savedW: Int, savedH: Int): Pair<Float, Float>`: identity when sizes equal; else `p.xPx * (currentW.toFloat() / savedW)` and same for y
- [x] 4.5 Add `private fun buildFallbackGestureDescription(currentW: Int, currentH: Int): GestureDescription`: existing 4px / 80ms stroke (preserve current behavior)
- [x] 4.6 Refactor `triggerOneHandedGesture()`:
  ```
  val (w, h) = DisplayHelper.getRealDisplaySizePx(this)
  val recorded = loadRecordedGestureOrNull()
  val gesture = recorded?.let { buildRecordedGestureDescription(it, w, h) }
                ?: buildFallbackGestureDescription(w, h)
  val ok = dispatchGesture(gesture, null, null)
  Log.i(TAG, "triggerOneHandedGesture recorded=${recorded != null} dispatched=$ok")
  return ok
  ```
- [x] 4.7 Verify no caller signature changes (still `Boolean` return, still called from `OverlayService.handleToggleBypassAndGesture`)

## 5. MainActivity entry + status

- [x] 5.1 In `app/src/main/res/layout/activity_main.xml`: add MaterialButton id=`btn_record_gesture` under existing `switch_anti_transform` card (style=`Widget.Material3.Button.TonalButton`, text=`btn_record_gesture`, drawableStart=`ic_play` or new icon)
- [x] 5.2 Add TextView id=`tv_gesture_status` directly under `btn_record_gesture` (textAppearance Material body small, textColor on_surface_variant)
- [x] 5.3 In `MainActivity.kt`: add `private val gestureRepository by lazy { GestureRepository(this) }`
- [x] 5.4 In `setupOperationPanel()`: bind `binding.btnRecordGesture.setOnClickListener { handleRecordGestureClick() }`
- [x] 5.5 Implement `private fun handleRecordGestureClick()`: if `gestureRepository.has()` → show AlertDialog with title `dialog_rerecord_title` + message `dialog_rerecord_message` + positive button launches Activity, negative cancels; else launch Activity directly
- [x] 5.6 Add `private val recordGestureLauncher = registerForActivityResult(StartActivityForResult()) { result -> if (result.resultCode == RESULT_OK) showPostSaveSnackbar() else { /* keep status as-is */ }; refreshGestureStatus() }`
- [x] 5.7 Implement `private fun refreshGestureStatus()`: `binding.tvGestureStatus.setText(if (gestureRepository.has()) R.string.status_gesture_recorded else R.string.status_gesture_unrecorded)`
- [x] 5.8 Call `refreshGestureStatus()` in `onResume()`
- [x] 5.9 Implement `private fun showPostSaveSnackbar()`: `Snackbar.make(binding.root, R.string.hint_gesture_replaced_undo, Snackbar.LENGTH_LONG).setAction(R.string.hint_gesture_test) { BlockerAccessibilityService.get()?.triggerOneHandedGesture() ?: Toast.makeText(this, R.string.fab_long_press_a11y_required, Toast.LENGTH_SHORT).show() }.show()`

## 6. AndroidManifest registration

- [x] 6.1 In `AndroidManifest.xml` `<application>`: add `<activity android:name=".RecordGestureActivity" android:exported="false" android:screenOrientation="portrait" android:theme="@style/Theme.TouchBlocker.Fullscreen" />`

## 7. i18n strings (zh-CN + en)

- [x] 7.1 Add to `app/src/main/res/values/strings.xml`:
  - `<string name="btn_record_gesture">录制单手模式手势</string>`
  - `<string name="record_gesture_title">录制单手模式触发手势</string>`
  - `<string name="record_gesture_instruction">请单指演示一次单手模式触发手势</string>`
  - `<string name="record_gesture_recording">录制中…</string>`
  - `<string name="record_gesture_recorded">已录制</string>`
  - `<string name="record_gesture_btn_save">完成</string>`
  - `<string name="record_gesture_btn_retry">重新开始</string>`
  - `<string name="record_gesture_btn_cancel">取消</string>`
  - `<string name="a11y_record_announce">录制模式已启动，请在三秒倒计时后单指演示手势</string>`
  - `<string name="toast_gesture_too_short">手势过短或无效，请重试</string>`
  - `<string name="toast_gesture_too_long">手势超过 60 秒，已丢弃，请重试</string>`
  - `<string name="toast_multitouch_rejected">检测到多指触控，请用单指演示</string>`
  - `<string name="toast_keyboard_rejected">请用触屏演示手势</string>`
  - `<string name="hint_gesture_test">测试</string>`
  - `<string name="hint_gesture_replaced_undo">手势已更新</string>`
  - `<string name="status_gesture_recorded">已录制 · 优先使用自定义手势</string>`
  - `<string name="status_gesture_unrecorded">未录制 · 使用默认手势</string>`
  - `<string name="dialog_rerecord_title">已有录制手势</string>`
  - `<string name="dialog_rerecord_message">确定要覆盖现有的单手模式触发手势吗？</string>`

## 8. Compile + smoke verification

- [x] 8.1 `QEMU_LD_PREFIX=/usr/x86_64-linux-gnu ./gradlew :app:assembleDebug` succeeds without warnings introduced by this change
- [x] 8.2 `QEMU_LD_PREFIX=/usr/x86_64-linux-gnu ./gradlew :app:assembleRelease` succeeds; APK ≤ 1.6 MB
- [x] 8.3 `aapt2 dump xmltree --file AndroidManifest.xml app-release.apk` confirms `RecordGestureActivity` registered with `exported=false` and `screenOrientation=portrait`
- [x] 8.4 Existing 12 unit tests (BypassStateMachineTest + IntentValidationTest) remain green (no regression)

## 9. PBT Property tests

- [x] 9.1 Create `app/src/test/java/com/ccg/screenblocker/data/GestureRepositoryRoundTripTest.kt` covering P1 (round-trip) and P5 (validation boundary): generate gestures with random valid sizes, save, reload, assert deep equality; assert validation rejects each boundary violator (strokes 11, duration 60001ms, stroke 0ms, points 1)
- [x] 9.2 Create `app/src/test/java/com/ccg/screenblocker/service/GestureReplayBuilderTest.kt` covering P2 (identity scaling), P3 (proportional scaling), P4 (multi-stroke timeline): pure-Kotlin builder logic test; mock or extract a `buildRecordedGestureDescription` helper into a non-Service class for testability
- [x] 9.3 Create `app/src/test/java/com/ccg/screenblocker/service/FallbackSelectionTest.kt` covering P7: stub repository for valid/null/corrupt cases; assert which builder path is invoked

## 10. Spec compliance verification

- [x] 10.1 Inspect `OverlayService.kt` and confirm zero modifications (P8 isolation)
- [x] 10.2 Inspect `MainActivity.kt` `switchFab` listener and confirm no change to FAB single-tap semantics (floating-button spec compliance)
- [x] 10.3 Inspect `BlockerAccessibilityService.triggerOneHandedGesture()` signature unchanged (callable contract preserved)
