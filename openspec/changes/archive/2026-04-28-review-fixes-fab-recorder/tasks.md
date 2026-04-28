# Tasks: review-fixes-fab-recorder

## 1. A11y custom action for FAB (G1, Critical)

- [x] 1.1 In `OverlayService.attachFabIfNeeded()` after `iv.setOnTouchListener { ... }`, register custom a11y action via `ViewCompat.addAccessibilityAction(iv, getString(R.string.fab_a11y_action_trigger_gesture)) { _, _ -> startService(Intent(...).setAction(ACTION_FORCE_BYPASS_AND_GESTURE).putExtra(EXTRA_SOURCE, "a11y")); true }`
- [x] 1.2 Add string `R.string.fab_a11y_action_trigger_gesture` = "触发单手模式手势" to `app/src/main/res/values/strings.xml`

## 2. Wall-clock gesture validation (G2, Major)

- [x] 2.1 In `GestureRepository.validate()`, after existing `Σduration ≤ 60_000` check, add wall-clock check: `val wallClockMs = strokes.last().points.last().tMs - strokes.first().points.first().tMs; require(wallClockMs <= MAX_TOTAL_DURATION_MS) { "wall-clock duration $wallClockMs ms exceeds $MAX_TOTAL_DURATION_MS ms" }`
- [x] 2.2 Update `GestureRepositoryRoundTripTest`: add scenario where Σduration ≤ 60_000 but wall-clock > 60_000 → assert `validate` throws

## 3. Runtime_visible dual-path (G3, Major)

- [x] 3.1 In `MainActivity.switchRuntimeVisible.setOnCheckedChangeListener`, after `serviceBinder?.setRuntimeVisible(isChecked)`, also `startService(Intent(this, OverlayService::class.java).apply { action = OverlayService.ACTION_RUNTIME_VISIBLE_SET; putExtra(OverlayService.EXTRA_VISIBLE, isChecked) })`
- [x] 3.2 In `OverlayService.onStartCommand` `else` branch (the catch-all), keep existing `stopSelf` for STOPPED state; verify `ACTION_RUNTIME_VISIBLE_SET` early-returns when overlayView==null without spawning extra work — already correct

## 4. FAB description + multi-stroke hint (G4 + G9, Major + Minor)

- [x] 4.1 Update `R.string.fab_desc` in `strings.xml` to: "屏幕边缘悬浮按钮：单击切换屏蔽暂停/恢复；双击触发已录制的单手模式手势；可拖动到任意位置。"
- [x] 4.2 Update `R.string.record_gesture_instruction` in `strings.xml` to: "请单指演示单手模式触发手势（可多次抬起重按）"

## 5. Edge-to-edge layout safety (G5, Major)

- [x] 5.1 In `app/src/main/res/layout/activity_record_gesture.xml`, change bottom button `LinearLayout` `android:layout_marginBottom="64dp"` → `android:layout_marginBottom="96dp"`

## 6. TapDetector drag cancel (G6, Minor)

- [x] 6.1 In `OverlayService.attachFabIfNeeded()` ACTION_MOVE branch, when `!dragging` becomes `dragging` (the transition point), inject ACTION_CANCEL into tapDetector:
  ```
  if (!dragging && (...)) {
      dragging = true
      val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
      tapDetector.onTouchEvent(cancel)
      cancel.recycle()
  }
  ```

## 7. Trail coordinate system unification (G7, Minor)

- [x] 7.1 In `RecordGestureActivity.handleDown / handleMove / handleUp`, store `event.x`/`event.y` (view-local) into `activePoints` (Point.xPx/yPx)
- [x] 7.2 Remove `toScreenX` / `toScreenY` from per-event hot path; keep `trailLocOnScreen` for save-time conversion only
- [x] 7.3 In `RecordGestureActivity.saveAndFinish`, before `RecordedGesture(...)` construction, map view-local strokes to screen-absolute: each `Point(p.xPx + trailLocOnScreen[0], p.yPx + trailLocOnScreen[1], p.tMs)`
- [x] 7.4 Refresh `trailLocOnScreen` in `enterRecording()` (already done)

## 8. WCAG-compliant scrim (G8, Minor)

- [x] 8.1 In `app/src/main/res/layout/activity_record_gesture.xml`, change root `android:background="#80000000"` → `android:background="#B3000000"`

## 9. Dead code removal (G10, Suggestion)

- [x] 9.1 In `OverlayService.companion`, remove `const val FAB_CLICK_MAX_DURATION_MS = 600L`

## 10. Compile + smoke verification

- [x] 10.1 `QEMU_LD_PREFIX=/usr/x86_64-linux-gnu ./gradlew :app:assembleDebug` succeeds
- [x] 10.2 `QEMU_LD_PREFIX=/usr/x86_64-linux-gnu ./gradlew :app:testDebugUnitTest` — 26 existing tests + 1 new wall-clock boundary test all pass
- [x] 10.3 `QEMU_LD_PREFIX=/usr/x86_64-linux-gnu ./gradlew :app:assembleRelease` succeeds; APK ≤ 1.6 MB
