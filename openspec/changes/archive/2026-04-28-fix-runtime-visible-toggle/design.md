# Design: fix-runtime-visible-toggle

## Architecture Decision

### Rationale

根因：`runtime_visible` pref 是 single-source-of-truth，但缺失从 pref → 已挂载 view 的 push 通道。`OverlayService` 在每次 `addOrUpdateOverlay` 调一次 `runtimeVisiblePref()` 同步到 view，但运行中 pref 变更不触发同步。

修复策略：复刻 `ACTION_FAB_SET_VISIBLE` 的声明式命令模式 — MainActivity 写 pref 后立即推 intent；OverlayService 收到后直接给 `overlayView?.runtimeVisible = desired` 赋值，setter 内置的 `invalidate()` 保证下一帧重绘。

### Rejected Alternatives

1. **OnSharedPreferenceChangeListener 监听 pref 变化** — 拒绝：跨进程不可靠，且需要在 OverlayService 注册/反注册增加生命周期复杂度，违反 KISS
2. **bound service 双向回调** — 拒绝：MainActivity 已通过 LocalBinder 拿到 Service 引用，但 Service 当前公开 API 无 setter；新增 setter 又破坏「命令-状态」分离原则
3. **重启 overlay** — 拒绝：闪烁动画 + 通道切换风险，破坏 G1 即时生效语义

### Assumptions

- `OverlayView.runtimeVisible` setter 已正确触发 invalidate（已验证：`ui/OverlayView.kt:48-52`）
- 运行态 view 主线程访问；`Service.startService` 路由到 `onStartCommand` 也在主线程（Android 文档）
- 用户在 service 未运行时切开关 → 仅写 pref，下次 fresh attach 通过 `runtimeVisiblePref()` 自然生效

### Potential Side Effects

- 无 — handler 仅修改一个 view 字段；不触碰 layout、state machine、bypass、FAB

## Resolved Constraints

| # | Ambiguity | Required Constraint |
|---|---|---|
| C1 | Action 命名 | **`com.ccg.screenblocker.action.RUNTIME_VISIBLE_SET`**（与 `ACTION_FAB_SET_VISIBLE` 同一命名 pattern） |
| C2 | Extra 复用 | **复用 `EXTRA_VISIBLE`**（`com.ccg.screenblocker.extra.VISIBLE`），避免常量爆炸 |
| C3 | Service 未运行时行为 | **MainActivity 仅写 pref，不派发 intent**（service 启动时通过 `runtimeVisiblePref()` 自然读取） |
| C4 | EXTRA_VISIBLE 缺失时 | **Log.e + 丢弃**（与 `handleFabSetVisible` 一致） |
| C5 | overlayView == null 时 | **静默 no-op**（service ARMING 期间或被 detach；下次 attach 自然读 pref） |
| C6 | 写 pref 顺序 | **先写 pref，再派发 intent**（保证 SoT 一致；service 收到 intent 不读 pref，仅信任 EXTRA_VISIBLE） |

## PBT Properties

| ID | Name | Definition |
|---|---|---|
| **P1** | Live sync | service 运行中 `switchRuntimeVisible.setChecked(b)` → `overlayView.runtimeVisible == b` 在下一帧 |
| **P2** | Pref single-writer | 任意切换序列后 `prefs.getBoolean("runtime_visible") == 最后一次 setChecked 值` |
| **P3** | Service-stopped tolerance | service 未运行时切换不抛异常、不崩溃；pref 正确更新 |
| **P4** | EXTRA strictness | `ACTION_RUNTIME_VISIBLE_SET` 缺 `EXTRA_VISIBLE` → no-op + Log.e |
| **P5** | View-absent tolerance | service ARMING 状态收到命令 → no-op |

## Implementation Plan

1. **OverlayService**：新增 `ACTION_RUNTIME_VISIBLE_SET` 常量；`onStartCommand` 路由；`handleRuntimeVisibleSet(intent)` handler
2. **MainActivity**：`switchRuntimeVisible` 监听器写 pref 后判断 `isServiceRunning`，运行态时 push intent

## Considerations

| Aspect | Note |
|---|---|
| Performance | O(1)；handler 仅设字段 + 一次 `View.invalidate` |
| Accessibility | 不影响 TalkBack；开关本身已有 SwitchMaterial 默认语义 |
| Maintainability | 与 FAB-SET-VISIBLE 对称；未来若新增其他 view-state pref（如 alpha 度），可复用同一模式 |
| Testing | 纯 JVM 不可测（Intent + ApplicationContext 需要 framework）；androidTest 中可通过 Bound Service 模拟 |
| Compatibility | 无新 API；`Service.startService(Intent)` 自 API 1 起可用 |
| Rollback | 纯加法；移除新增 ACTION 即可回退到旧行为 |
