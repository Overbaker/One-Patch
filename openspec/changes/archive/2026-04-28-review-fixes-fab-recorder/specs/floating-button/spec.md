# Spec Delta: floating-button — review followup

## ADDED Requirements

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
