# Spec Delta: gesture-recording — review followup

## MODIFIED Requirements

### Requirement: Recording Validation Gates

`GestureRepository.save()` SHALL hard-reject any `RecordedGesture` violating documented bounds, with no partial persistence.

#### Scenario: Reject when total wall-clock duration exceeds limit

- **GIVEN** a gesture where `lastStroke.points.last().tMs - firstStroke.points.first().tMs > 60_000`
- **WHEN** `save()` is called
- **THEN** the method SHALL reject AND SHALL NOT persist
- **AND** sumOf-based check SHALL also remain in place as defensive secondary gate

## ADDED Requirements

### Requirement: Coordinate System Internal Consistency

`RecordGestureActivity` SHALL keep recording state in `trail_view` view-local coordinates throughout the session and SHALL convert to screen-absolute coordinates only at persistence time.

#### Scenario: Trail rendering matches finger position

- **WHEN** the user demonstrates a gesture in any phase
- **THEN** every `activePoints[i]` and `completedStrokes[*].points[*]` x/y SHALL be in `trail_view` view-local coordinate space
- **AND** `GestureTrailView.onDraw` SHALL render points without any coordinate offset

#### Scenario: Persisted gesture coordinates are screen-absolute

- **WHEN** `saveAndFinish()` constructs the `RecordedGesture` for persistence
- **THEN** every point's `xPx`/`yPx` SHALL equal its view-local value plus `trailView.getLocationOnScreen()` offset
- **AND** the resulting `RecordedGesture` SHALL be replay-ready by `BlockerAccessibilityService.dispatchGesture` without further transformation
