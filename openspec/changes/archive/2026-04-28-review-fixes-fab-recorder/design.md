# Design: review-fixes-fab-recorder

## Multi-Model Synthesis

| Source | Headline finding |
|---|---|
| Codex (63/100) | wall-clock validation gap; binder-only runtime_visible loses updates; FAB drag GestureDetector state staleness; trail coords inconsistency |
| Gemini (74/100) | TalkBack double-tap inaccessible; fab_desc i18n drift; 64dp hardcoded margin; 50% scrim WCAG fail; multi-stroke hint missing |
| Convergence | 1 Critical + 4 Major + 4 Minor + 1 Suggestion; all addressable with small targeted patches; zero new dependencies |

## Architecture Decision

### Rationale
This change is a follow-up bundle: each issue is independently scoped and has one obvious fix. We bundle them because they were identified by a single review pass and share `record-gesture-playback` as their root context.

### Rejected Alternatives
1. **Replace GestureDetector with custom long-press handler** to bypass TalkBack issue — rejected: long-press conflicts with floating-button spec ban; a11y custom action is the canonical Android pattern.
2. **Persist runtime_visible state via OnSharedPreferenceChangeListener in OverlayService** instead of dual-path push — rejected: violates KISS; spawns lifecycle watcher for one pref.
3. **Decimate samples again to fit wall-clock 60s** — rejected: full-fidelity is required for OEM gesture recognition; the proper fix is honest validation.
4. **Use ViewCompat.setOnApplyWindowInsetsListener for navbar offset** — rejected for this round: 96dp covers max nav-gesture height (48dp) + 3-button (48dp) with safe margin, no dynamic calculation needed.

### Assumptions
- TalkBack 用户的预期模型：单击 = focus-click（系统拦截）；自定义 a11y action 通过菜单选择
- 三键导航 navbar 高度 ≤ 48dp + 16dp gesture inset = 64dp；加 32dp 缓冲 = 96dp 安全
- WCAG AA 标准 4.5:1 是产品上线门槛（无设计 review 否决）
- record-gesture-playback 已发布的 RecordedGesture（schema_version=1）目前 0 实例（feature 刚 archive，无生产用户）

## Resolved Constraints

| # | Constraint | Concrete value |
|---|---|---|
| C1 | A11y custom action label | `R.string.fab_a11y_action_trigger_gesture` = "触发单手模式手势" |
| C2 | A11y action ID | `R.id.fab_action_trigger_gesture` (新建 ids.xml 或 attrs) — 或直接生成 hashCode-based action id |
| C3 | Wall-clock validation max | `60_000L ms`（与 GestureDescription.getMaxGestureDuration 一致） |
| C4 | Wall-clock 计算 | `lastStroke.points.last().tMs - firstStroke.points.first().tMs` |
| C5 | Runtime_visible dual-path 顺序 | binder 直调先（同步立即生效）→ Intent 派发后（异步兜底） |
| C6 | Intent fallback 触发条件 | 始终派发；service stopped 时由 onStartCommand fallback stopSelf |
| C7 | fab_desc 新文案 | "屏幕边缘悬浮按钮：单击切换屏蔽暂停/恢复；双击触发已录制的单手模式手势；可拖动到任意位置。" |
| C8 | record_gesture_instruction 新文案 | "请单指演示单手模式触发手势（可多次抬起重按）" |
| C9 | btn_save / btn_retry marginBottom | 96dp |
| C10 | scrim color | `#B3000000` (70% black) |
| C11 | tapDetector CANCEL 时机 | dragging 从 false 转 true 的瞬间，单次注入 |
| C12 | trail 坐标语义 | activePoints + completedStrokes 全用 view-local；saveAndFinish 时 mapTo screen-absolute |
| C13 | 删除符号 | `OverlayService.FAB_CLICK_MAX_DURATION_MS` |

## PBT Properties

| ID | Name | Definition |
|---|---|---|
| **P1** | A11y action invokable | `ViewCompat.performAccessibilityAction(fabView, fab_action_trigger_gesture, null)` 等价于双击行为（force bypass + dispatch gesture） |
| **P2** | Wall-clock validator | for any RecordedGesture，`save()` accept iff `last.last.tMs - first.first.tMs ≤ 60_000L` |
| **P3** | Wall-clock vs sumOf 区分 | 存在 RecordedGesture 满足 `sumOf ≤ 60_000` 但 wall-clock > 60_000，validator 拒绝 |
| **P4** | Dual-path idempotency | `binder.setRuntimeVisible(b)` 后再 `Intent SET_VISIBLE(b)` 视图状态不变（幂等） |
| **P5** | Drag cancels tap | dragging=true 后单次 ACTION_CANCEL 到 tapDetector；下一次 ACTION_DOWN→UP 仍正确分类为 single-tap |
| **P6** | View-local consistency | RecordGestureActivity 录制结束前所有 stroke 点 ∈ trail_view 局部坐标系；saveAndFinish 后存储坐标 = local + getLocationOnScreen 偏移 |

## Implementation Plan

1. **A11y action**（G1）：MainActivity 暴露 fab a11y action — 但 fab 由 OverlayService 管理 → 改在 OverlayService.attachFabIfNeeded 中调用 `ViewCompat.addAccessibilityAction(iv, label) { performForceBypassAndGesture; true }`
2. **Wall-clock validation**（G2）：GestureRepository.validate 添加 `wallClockMs = last - first; require ≤ 60_000`
3. **Runtime_visible dual-path**（G3）：MainActivity 监听器同时 binder + Intent；OverlayService.onStartCommand fast-path STOPPED → stopSelf
4. **fab_desc + multi-stroke hint**（G4 + G9）：strings.xml 修订
5. **Margin bottom**（G5）：activity_record_gesture.xml 96dp
6. **TapDetector CANCEL**（G6）：拖动转入瞬间 inject MotionEvent.ACTION_CANCEL
7. **Trail 坐标统一**（G7）：RecordGestureActivity activePoints 改 view-local；saveAndFinish 时 mapTo screen
8. **Scrim contrast**（G8）：activity_record_gesture.xml `#B3000000`
9. **删除 dead const**（G10）：OverlayService.FAB_CLICK_MAX_DURATION_MS
10. **Compile + smoke verify**

## Considerations

| Aspect | Note |
|---|---|
| Performance | 0 hot-path 影响；trail 坐标转换仅 saveAndFinish 一次 |
| Accessibility | Critical 修复 — TalkBack 用户首次可用 |
| Maintainability | 删除 dead code；命名一致 |
| Testing | GestureRepositoryRoundTripTest 新增 wall-clock boundary case |
| Compatibility | 0 schema 变化；既有 RecordedGesture 可加载 |
| Rollback | 纯加性 / 字符串变更 / 校验增强；revert 即回退 |
