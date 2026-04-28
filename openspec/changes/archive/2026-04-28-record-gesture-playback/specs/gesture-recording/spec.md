# Spec: gesture-recording

## ADDED Requirements

### Requirement: Recorded Gesture Persistence

The application SHALL provide a `GestureRepository` capable of persisting and retrieving a single user-recorded one-handed-mode gesture in `gesture_prefs` SharedPreferences with schema versioning.

#### Scenario: Save round-trips identically

- **GIVEN** a valid `RecordedGesture` `g` with strokes ∈ [1, 10], pointsPerStroke ∈ [2, 500], savedDisplay > 0
- **WHEN** `GestureRepository.save(g)` is called and `GestureRepository.load()` is subsequently called
- **THEN** the loaded `RecordedGesture` SHALL equal `g` modulo encoding normalization (deep equality on display fields, stroke count, ordered points, all coordinates and timestamps)

#### Scenario: Schema version mismatch returns null

- **WHEN** `gesture_prefs` contains `KEY_SCHEMA_VERSION` value different from the current `SCHEMA_VERSION`
- **THEN** `load()` SHALL return `null`
- **AND** `load()` SHALL clear the stale entry to prevent future parse attempts

#### Scenario: Corrupt payload returns null

- **WHEN** `gesture_prefs` contains malformed `KEY_STROKES_PAYLOAD` (parse failure)
- **THEN** `load()` SHALL return `null`
- **AND** SHALL clear the corrupt entry
- **AND** SHALL NOT throw an exception to the caller

### Requirement: Recording Validation Gates

`GestureRepository.save()` SHALL hard-reject any `RecordedGesture` violating the documented bounds, with no partial persistence.

#### Scenario: Reject when stroke count exceeds limit

- **GIVEN** a `RecordedGesture` with `strokes.size > 10`
- **WHEN** `save()` is called
- **THEN** the method SHALL throw `IllegalArgumentException` (or return failure result) AND SHALL NOT mutate stored data

#### Scenario: Reject when total duration exceeds limit

- **GIVEN** a gesture where `Σ(stroke.durationMs) > 60000`
- **WHEN** `save()` is called
- **THEN** the method SHALL reject AND SHALL NOT persist

#### Scenario: Reject when any stroke has fewer than 2 points

- **GIVEN** a gesture where any `stroke.points.size < 2`
- **WHEN** `save()` is called
- **THEN** the method SHALL reject

#### Scenario: Reject when any stroke duration < 1 ms

- **GIVEN** a gesture where any `stroke.durationMs < 1`
- **WHEN** `save()` is called
- **THEN** the method SHALL reject

### Requirement: Single-Pointer Capture Discipline

`RecordGestureActivity` SHALL accept only single-pointer touchscreen MotionEvents during recording.

#### Scenario: Multi-touch aborts session

- **GIVEN** recording is in `RECORDING` phase
- **WHEN** any `MotionEvent` with `actionMasked == ACTION_POINTER_DOWN` is dispatched
- **THEN** the recording session SHALL transition to `ABORTED` immediately
- **AND** SHALL NOT persist any captured points
- **AND** SHALL emit Snackbar `R.string.toast_multitouch_rejected`

#### Scenario: Non-touchscreen source ignored

- **GIVEN** any phase
- **WHEN** a `MotionEvent` arrives without `InputDevice.SOURCE_TOUCHSCREEN` flag set
- **THEN** the event SHALL NOT be appended to the path
- **AND** if no recording exists, Snackbar `R.string.toast_keyboard_rejected` SHALL be shown

#### Scenario: Single-pointer demo accepted

- **GIVEN** a gesture demonstrated entirely with one touchscreen pointer
- **WHEN** `ACTION_DOWN` → multiple `ACTION_MOVE` → `ACTION_UP` is observed
- **THEN** the activity SHALL persist exactly 1 stroke containing the captured points

### Requirement: Sampling Decimation

The activity SHALL coalesce noise samples to keep payload bounded while preserving shape fidelity.

#### Scenario: Adjacent samples within thresholds are coalesced

- **GIVEN** the last accepted point at time `t_prev` and position `(x_prev, y_prev)`
- **WHEN** a new `ACTION_MOVE` arrives with `Δt < 16 ms` AND `Δdistance < 2 dp`
- **THEN** the new sample SHALL replace the pending tail (not append)

#### Scenario: ACTION_DOWN and ACTION_UP always recorded

- **WHEN** any `ACTION_DOWN` or `ACTION_UP` event arrives during `RECORDING`
- **THEN** the corresponding point SHALL be recorded unconditionally (no decimation)

### Requirement: Coordinate Storage and Replay Rescaling

Recorded coordinates SHALL be stored as absolute pixels alongside the recording-time display size; replay SHALL rescale proportionally per axis.

#### Scenario: Recording stores absolute px + display size

- **WHEN** a session is saved
- **THEN** stored data SHALL include `savedDisplayWidthPx` and `savedDisplayHeightPx` (Int) reflecting `DisplayHelper.getRealDisplaySizePx()` at save time
- **AND** every point SHALL store `(xPx: Float, yPx: Float, tMsFromGestureStart: Long)`

#### Scenario: Identity replay when sizes match

- **GIVEN** `currentDisplay == savedDisplay`
- **WHEN** replay reconstructs the path
- **THEN** every replay point coordinate SHALL equal the recorded coordinate exactly (no multiplication)

#### Scenario: Proportional rescaling when sizes differ

- **GIVEN** `currentDisplay ≠ savedDisplay`
- **WHEN** replay reconstructs the path
- **THEN** for every point: `xReplay = xRec × (currentW / savedW)` AND `yReplay = yRec × (currentH / savedH)`

### Requirement: Multi-Stroke Timeline Preservation

When the user lifts and re-touches during recording, each finger-down segment SHALL persist as a separate stroke with preserved inter-stroke timing.

#### Scenario: Lift-and-repress produces multiple strokes

- **WHEN** the recording observes `DOWN_a → UP_a → DOWN_b → UP_b`
- **THEN** the saved gesture SHALL contain 2 strokes
- **AND** the gap `DOWN_b.t - UP_a.t` SHALL be preserved in `stroke[1].startTime - (stroke[0].startTime + stroke[0].durationMs)`

#### Scenario: StrokeDescription startTime is gesture-relative

- **GIVEN** a saved multi-stroke gesture with first point at `t0`
- **WHEN** building `GestureDescription` for replay
- **THEN** every `StrokeDescription[i].startTime` SHALL equal `strokes[i].points[0].tMsFromGestureStart`

### Requirement: Replay Path Construction

Replay SHALL build paths deterministically without smoothing.

#### Scenario: Path uses moveTo + lineTo only

- **WHEN** building a `Path` for any saved stroke
- **THEN** the `Path` SHALL be constructed as `moveTo(points[0])` followed by `lineTo(points[i])` for `i ∈ [1, n)`
- **AND** SHALL NOT use `quadTo`, `cubicTo`, or any smoothing primitive

#### Scenario: Stroke duration enforces minimum

- **WHEN** computing `StrokeDescription.duration` for any stroke
- **THEN** `duration = max(1L, lastPoint.t - firstPoint.t)` (StrokeDescription requires `≥ 1 ms`)

### Requirement: Replay Fallback Determinism

`BlockerAccessibilityService.triggerOneHandedGesture()` SHALL select the recorded path iff the repository returns a valid recording, otherwise the legacy hardcoded path.

#### Scenario: Recorded gesture used when present and valid

- **GIVEN** `GestureRepository.load()` returns a valid `RecordedGesture`
- **WHEN** `triggerOneHandedGesture()` is invoked
- **THEN** the function SHALL build `GestureDescription` from the recording (with current-display rescale)
- **AND** SHALL call `dispatchGesture(gesture, null, null)`
- **AND** SHALL NOT use the hardcoded path

#### Scenario: Hardcoded fallback used when recording absent

- **GIVEN** `GestureRepository.load()` returns `null`
- **WHEN** `triggerOneHandedGesture()` is invoked
- **THEN** the function SHALL build the legacy hardcoded `(w/2, h-4) → (w/2, h-1)` 80 ms stroke
- **AND** SHALL call `dispatchGesture(gesture, null, null)`

#### Scenario: dispatchGesture failure does NOT auto-fallback

- **GIVEN** `dispatchGesture()` for the recorded gesture returns `false`
- **WHEN** the failure is observed
- **THEN** the function SHALL log the failure and return `false`
- **AND** SHALL NOT subsequently dispatch the hardcoded fallback gesture

### Requirement: Recorder Lifecycle Isolation

`RecordGestureActivity` SHALL NOT mutate any `OverlayService` runtime state nor any preference outside `gesture_prefs`.

#### Scenario: Activity lifecycle leaves OverlayService state unchanged

- **GIVEN** any `OverlayService.RunState` (STOPPED, ARMING, RUNNING) and any `(manualBypass, autoBypass)` configuration
- **WHEN** `RecordGestureActivity` is launched, optionally records, and finishes (saved or cancelled)
- **THEN** `RunState`, `manualBypass`, `autoBypass`, `fab_visible` pref, `auto_bypass_displaced` pref, `runtime_visible` pref, and block-area data SHALL remain unchanged

#### Scenario: onPause discards transient session

- **GIVEN** recording is in `RECORDING` or `REVIEW` phase
- **WHEN** `Activity.onPause` is invoked (user switches apps, system overlay, recorder backgrounded)
- **THEN** the in-memory session SHALL be discarded
- **AND** `gesture_prefs` SHALL NOT be modified

#### Scenario: Back press cancels without saving

- **WHEN** user invokes back navigation in any phase
- **THEN** the activity SHALL finish with `RESULT_CANCELED`
- **AND** SHALL NOT persist any data

### Requirement: Out-of-Bounds User Feedback

When validation rejects a recording, the user SHALL receive a Snackbar identifying which boundary was violated.

#### Scenario: Stroke duration exceeds limit

- **GIVEN** the captured session has `Σ(strokeDuration) > 60000 ms`
- **WHEN** save is attempted
- **THEN** Snackbar with text `R.string.toast_gesture_too_long` SHALL be shown
- **AND** the session SHALL remain in `REVIEW` phase awaiting retry

#### Scenario: Gesture too short

- **GIVEN** the captured session has any stroke with `< 2 points` OR `duration < 1 ms`
- **WHEN** save is attempted
- **THEN** Snackbar with text `R.string.toast_gesture_too_short` SHALL be shown

#### Scenario: Multi-touch detected mid-recording

- **WHEN** during `RECORDING`, a second pointer is detected
- **THEN** the session SHALL abort
- **AND** Snackbar `R.string.toast_multitouch_rejected` SHALL be shown

### Requirement: MainActivity Entry and Status

The MainActivity SHALL expose a TonalButton entry to launch `RecordGestureActivity` and a status TextView reflecting whether a recording exists.

#### Scenario: Status reflects repository state on resume

- **GIVEN** any `RecordedGesture` save state in `gesture_prefs`
- **WHEN** `MainActivity.onResume` runs
- **THEN** the status TextView SHALL display `R.string.status_gesture_recorded` if `repo.has() == true`, else `R.string.status_gesture_unrecorded`

#### Scenario: Re-record requires explicit confirmation at MainActivity entry

- **GIVEN** `repo.has() == true`
- **WHEN** the user taps the record entry button
- **THEN** an AlertDialog with title `R.string.dialog_rerecord_title` and message `R.string.dialog_rerecord_message` SHALL be shown
- **AND** only on dialog confirmation SHALL `RecordGestureActivity` be launched

#### Scenario: Empty state launches directly

- **GIVEN** `repo.has() == false`
- **WHEN** the user taps the record entry button
- **THEN** `RecordGestureActivity` SHALL launch immediately without confirmation dialog

#### Scenario: Post-save offers test action

- **WHEN** `RecordGestureActivity` returns `RESULT_OK`
- **THEN** MainActivity SHALL show a Snackbar with action `R.string.hint_gesture_test` (5 seconds)
- **AND** tapping the action SHALL invoke `BlockerAccessibilityService.get()?.triggerOneHandedGesture()`
