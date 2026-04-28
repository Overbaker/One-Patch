# Change Proposal: fix-fab-bugs

**Status**: Active
**Created**: 2026-04-28
**Related Spec**: floating-button (new) + quick-toggle-entry (new) + bypass-state-machine (new)

## Why

DeadZone v1.0.0 ships a Floating Action Button (FAB) and a `QuickToggleActivity` trampoline meant to give users one-click access to "暂停屏蔽 + 触发单手模式". Real-device testing on Xiaomi 13 Ultra (HyperOS 2.x) revealed **four production-blocking defects**:

1. **FAB single-tap is silently no-op** — the click handler returns early when `overlayView` is null, with no user feedback. Worse, `toggleFabVisibility()` can attach an "orphan" FAB while the OverlayService is not running, in which case every tap permanently fails.
2. **FAB visibility toggle is inverted** — `MainActivity.setOnCheckedChangeListener` writes the new state to `SharedPreferences` *and then* asks `OverlayService` to toggle it again, causing a double-flip. User flips switch ON → FAB hides; flips OFF → FAB shows.
3. **Xiaomi "敲击背部" (Quick Tap on back) cannot find the app** — `QuickToggleActivity` declared `<category android:name="android.intent.category.LAUNCHER_APP" />`, which is not a valid Android category. Xiaomi's app picker only lists activities exposing standard `category.LAUNCHER`.
4. **Single-click should atomically perform both actions** — current design splits "toggle bypass" (single-click) and "trigger one-handed mode" (long-press 600ms). User expects: one tap = "free the screen" / "lock the screen" — both operations as one atomic gesture.

These defects undermine the only mitigation we have for HyperOS's irreversible display-area transform behavior on overlays. Without working FAB / Quick Tap entry, users cannot reach blocked regions when the screen is full-height, defeating the product's purpose.

## What Changes

This change introduces four hard guarantees and two architectural improvements, with **zero new external dependencies**:

### Hard guarantees (correctness fixes)

- **G1 (FAB-Click-Atomicity)**: A single tap on the FAB executes — atomically and in a single user gesture — both `toggleBypass()` and `triggerOneHandedGesture()`. Long-press behavior is removed.
- **G2 (Switch-Single-Source-of-Truth)**: The "Show floating button" preference has exactly one writer. UI components emit *desired state* commands, not *toggle* commands; service layer applies state without re-negation.
- **G3 (Standard-LAUNCHER-Discoverability)**: `QuickToggleActivity` is exposed via `<activity-alias>` carrying `category.LAUNCHER`, with a distinct label ("DeadZone 快捷切换") and icon. Resulting in the Xiaomi 敲击背部 app picker listing it as a separate selectable entry.
- **G4 (FAB-Click-Determinism)**: The FAB is only attached when `OverlayService` is in the `RUNNING` state; preference toggles while service is stopped only update persisted state, not on-screen widgets. The FAB click handler never silently returns; if the runtime overlay is unexpectedly absent, it logs an error and shows a Toast.

### Architectural improvements

- **A1 (Bypass-State-Decomposition)**: Split current single `isBypassing` flag into two orthogonal states: `manualBypass` (user toggle) and `autoBypass` (displacement-detected). The effective `bypassRuntime` is `manualBypass || autoBypass`. This eliminates the "FAB tap silently overwrites auto-bypass" race condition.
- **A2 (Atomic-Action-Reuse)**: Both FAB click and `QuickToggleActivity` route through the same `OverlayService.ACTION_TOGGLE_BYPASS_AND_GESTURE` intent action — no duplicate logic.

### Scope

**In scope**:
- `app/src/main/java/com/ccg/screenblocker/service/OverlayService.kt`
- `app/src/main/java/com/ccg/screenblocker/QuickToggleActivity.kt`
- `app/src/main/java/com/ccg/screenblocker/MainActivity.kt`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/res/values/strings.xml` (label additions)

**Out of scope**:
- BlockerAccessibilityService gesture path geometry (will remain `bottom-edge short-down-swipe`; user-tunable in future change)
- AutoBypass displacement threshold tuning
- Any change to the rectangle drag/edit logic
- Internationalization beyond zh-CN + en

## Impact

| Surface | Impact |
|---|---|
| End user | One unambiguous tap (or back-tap / quick-ball) = full-screen self-service for blocked regions. No more "tapped but nothing happened" moments. |
| Privacy / permissions | None added. AccessibilityService already declared, gesture dispatch already enabled. |
| App size | Negligible (≤ 2 KB resource for new label/string). |
| Build / packaging | None. |
| Existing users (upgrade) | Old `fab_visible` preference value is reused as-is; no migration required. |
| Risks | (a) Adding `category.LAUNCHER` alias creates a second home-screen icon — accepted trade-off, documented in README; (b) HyperOS may not respond to `dispatchGesture` reliably — covered by `G4` (Toast feedback on failure) and out-of-scope gesture-tuning future change. |

## Non-Goals

- Replacing `TYPE_APPLICATION_OVERLAY` with `TYPE_ACCESSIBILITY_OVERLAY` for the FAB (FAB benefits from being transformed with the display area: in one-handed mode it lands within thumb reach).
- Detecting whether single-handed mode actually engaged after gesture dispatch (no public API).
- Persisting per-app FAB position; FAB position is global.
