# Change Proposal: review-fixes-fab-recorder

**Status**: Active
**Created**: 2026-04-28
**Related Spec**: gesture-recording (extension) + bypass-state-machine (extension) + floating-button (extension)

## Why

`/ccg:review` 在 record-gesture-playback 实施后产出双模型交叉审查（Codex 63/100、Gemini 74/100），识别出 1 项 Critical + 4 项 Major + 4 项 Minor + 1 项 Suggestion，全部需要修复才能合并。这些问题分布于：
- A11y：FAB 双击对 TalkBack 用户完全失能（系统拦截 double-tap 做 focus-click）
- 数据一致性：stroke 时长校验用 `sumOf` 而 replay 用 absolute timestamp，长 inter-stroke gap 通过校验后 timeline 可超 60s
- 状态同步：runtime_visible 改用 binder 直调后丢失 Intent fallback，binding 未就绪期间切换被丢弃
- i18n 错位：`fab_desc` 字符串说"长按"但代码已是 `onDoubleTap`
- 系统兼容：64dp 硬编码 marginBottom 在三键导航设备与 navbar 重叠
- GestureDetector 状态：FAB 拖动开始时 tapDetector 未收到 ACTION_CANCEL，残留状态影响下次分类
- 坐标系混乱：completedStrokes 存 screen-absolute 但 GestureTrailView 当 view-local 绘制（immersive 全屏 offset=0 隐藏 bug）
- WCAG：scrim 50% 黑 + 白文字 contrast ~2.3:1 < 4.5:1
- 用户认知：录制 instruction 不提示多 stroke 能力
- 死代码：`FAB_CLICK_MAX_DURATION_MS` 已不被引用

## What Changes

### Hard guarantees

- **G1 (TalkBack-Action-Accessible)**: TalkBack 启用时，FAB 必须暴露「触发录制手势」自定义 a11y action（语义等价于双击）
- **G2 (Wall-Clock-Validation)**: GestureRepository 校验改为 wall-clock：`lastStroke.last.tMs - firstStroke.first.tMs ≤ 60_000ms`
- **G3 (Runtime-Visible-Dual-Path)**: switchRuntimeVisible 切换同时走 binder 直调 + Intent 派发；binder 未就绪 / 路径切换时 Intent 兜底
- **G4 (FAB-Description-Truthful)**: `fab_desc` 描述与实际代码行为一致（单击=切换、双击=触发录制手势）
- **G5 (Edge-To-Edge-Safe)**: RecordGestureActivity 底部按钮使用 96dp marginBottom，覆盖三键导航条最大高度
- **G6 (Tap-Detector-Drag-Cancel)**: FAB 拖动开始时主动注入 ACTION_CANCEL 到 tapDetector，重置 tap-state-machine
- **G7 (Trail-Coords-Consistent)**: 录制全程使用 view-local 坐标；仅在 saveAndFinish 时转换为 screen-absolute 持久化
- **G8 (WCAG-Compliant-Contrast)**: scrim 改为 `#B3000000`（70%）保证 4.5:1 contrast
- **G9 (Multi-Stroke-Hint)**: `record_gesture_instruction` 追加「可多次抬起重按」
- **G10 (Dead-Code-Removed)**: 删除 `FAB_CLICK_MAX_DURATION_MS` 常量

### Scope

**In scope**:
- `app/src/main/java/com/ccg/screenblocker/service/OverlayService.kt`（移除 dead const + 拖动 CANCEL）
- `app/src/main/java/com/ccg/screenblocker/MainActivity.kt`（runtime_visible Intent fallback + FAB a11y action）
- `app/src/main/java/com/ccg/screenblocker/data/GestureRepository.kt`（wall-clock 校验）
- `app/src/main/java/com/ccg/screenblocker/RecordGestureActivity.kt`（坐标系统一）
- `app/src/main/res/layout/activity_record_gesture.xml`（marginBottom 96dp + scrim 70%）
- `app/src/main/res/values/strings.xml`（fab_desc + record_gesture_instruction）

**Out of scope**:
- 不引入新依赖（不加 Robolectric / Espresso）
- 不动 BlockerAccessibilityService / GestureReplayBuilder
- 不改 spec 已固化的 ACTION 命名 / EXTRA 命名

## Impact

| Surface | Impact |
|---|---|
| End user | TalkBack 用户首次可触发单手手势；多 stroke 录制更被理解；亮屏下 scrim 文字可读 |
| Privacy / permissions | 无 |
| App size | 0 净变化 |
| Build / packaging | 无 |
| Existing users | 已存录制的 RecordedGesture 全部兼容（schema 不变）；只读字段语义不变 |
| Risks | a11y custom action 注册需测真机 TalkBack；wall-clock 校验比 sumOf 更严格，理论上可能拒绝某些边界录制（实测 60s 录制极少） |

## Non-Goals

- 不重构 RecordGestureActivity phase 模型
- 不改 dispatchGesture failure 行为
- 不为录制增加 Robolectric 测试基础设施
