# Tasks: fix-runtime-visible-toggle

## 1. OverlayService — declarative SET command

- [x] 1.1 Add `const val ACTION_RUNTIME_VISIBLE_SET = "com.ccg.screenblocker.action.RUNTIME_VISIBLE_SET"` in `OverlayService.companion`
- [x] 1.2 Reuse existing `EXTRA_VISIBLE`; do NOT introduce new extra
- [x] 1.3 Add `ACTION_RUNTIME_VISIBLE_SET` branch in `onStartCommand` switch routing to `handleRuntimeVisibleSet(intent)`
- [x] 1.4 Implement `private fun handleRuntimeVisibleSet(intent: Intent)`:
  1. If `!intent.hasExtra(EXTRA_VISIBLE)` → `Log.e + return`
  2. Read `desired = intent.getBooleanExtra(EXTRA_VISIBLE, true)`
  3. Assign `overlayView?.runtimeVisible = desired`（setter 内置 invalidate）

## 2. MainActivity — push on switch change

- [x] 2.1 In `setupOperationPanel()`，replace `switchRuntimeVisible.setOnCheckedChangeListener` body：
  - Write `settingsPrefs.edit().putBoolean("runtime_visible", isChecked).apply()`（保持现有写法）
  - If `isServiceRunning` → `startService(Intent(...).setAction(ACTION_RUNTIME_VISIBLE_SET).putExtra(EXTRA_VISIBLE, isChecked))`
  - Else → no-op（service 未运行；下次 fresh attach 通过 pref 自然读取）

## 3. Compile + smoke verification

- [x] 3.1 `./gradlew :app:assembleDebug` succeeds
- [x] 3.2 `./gradlew :app:testDebugUnitTest` — 既有 12 单元测试保持通过（不引入回归）
- [x] 3.3 手测：service 运行中切换 `switchRuntimeVisible` → 下一帧屏蔽层提示色显隐（无重启）
- [x] 3.4 手测：service 未运行切换 → 仅写 pref；启用屏蔽后状态正确
