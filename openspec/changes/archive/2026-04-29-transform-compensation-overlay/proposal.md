# Change Proposal: transform-compensation-overlay

**Status**: Active
**Created**: 2026-04-29
**Related Spec**: bypass-state-machine (extension)

## Why

实测：HyperOS 单手模式触发后，无论是 `TYPE_ACCESSIBILITY_OVERLAY`（v1）还是 `attachAccessibilityOverlayToDisplay`（physical-display-overlay），屏蔽矩形仍随 SurfaceFlinger display-area transform 一起平移，**未达到「钉在物理坐标」的初衷**。

### 已排除的方向（Codex AOSP 调研锁定的硬约束）

| 路径 | 不可行原因 |
|---|---|
| `AccessibilityService.onMotionEvent()` 选择性 drop | 返回 `void`，无法 consume 单个 event |
| `setMotionEventSources(SOURCE_TOUCHSCREEN)` | 劫持整个触控源，整屏失效（catastrophic） |
| `FLAG_REQUEST_TOUCH_EXPLORATION_MODE` | 任一 a11y 服务用它都会屏蔽 onMotionEvent；与 TalkBack 互斥 |
| `TouchInteractionController` (API 34+) | 仅在 touch-exploration 模式下生效，全局触控语义改变 |
| 系统级 inputflinger 注入（小米官方 Game Turbo 路径） | system signature 私有 |

第三方公开 API 下，**唯一可行路径**：在 OnPreDrawListener 中检测 view 实际位置漂移 → 反向调整 `WindowManager.LayoutParams.x/y` 持续抵消 transform。这与小米官方机制完全不同（小米在 input pipeline 层 drop touch；我们在 view layout 层反向移动），但用户感知效果相同：**屏蔽矩形钉在物理坐标，单手模式期间也保护原物理区域**。

## What Changes

### Hard guarantees

- **G1 (Closed-Loop-Compensation)**: 当 `OverlayView.getLocationOnScreen()` 报告漂移 |Δ| > 阈值时，OverlayService 持续反向调整 `LayoutParams.x/y` 直至漂移收敛 ≤ 阈值。系统每次 transform 应用 → 检测 → 补偿 → 收敛，形成 closed loop。
- **G2 (Touch-Region-Tracks-Visual)**: 因为 `LayoutParams.x/y` 同时决定 view 视觉位置 + 触控判定区，反向补偿天然保证「视觉钉物理坐标」与「触控判定区钉物理坐标」**同步**。
- **G3 (Anti-Oscillation)**: hysteresis = 2 px 死区 + 每帧最多 1 次 update 调用，避免与 SurfaceFlinger transform 形成抖动循环。
- **G4 (User-Toggle-Semantics)**: 现有 `auto_bypass_displaced` 开关含义重塑：`true` = 反向补偿（钉物理坐标）；`false` = v1 让步行为（被 transform 后停止屏蔽该区域）。默认 `true`。
- **G5 (Backend-Agnostic)**: 反向补偿仅在 WindowManagerBackend 路径生效（`LayoutParams.x/y` 是 WM API）。DisplayAttachedBackend 路径不变（继续用 `SurfaceControl.Transaction.setPosition`，但 HyperOS 实测对 SurfaceControl 也 transform，会通过现有 displaceDetector 触发 fallback 到 WindowManagerBackend → 然后享受 G1 的反向补偿）。

### Architectural improvements

- **A1 (Reuse-Existing-Detector)**: 不引入新检测器；扩展 `detectDisplacementAndBypass()` 在 WindowManagerBackend + auto_bypass_displaced=true 时改为补偿模式而非让步。
- **A2 (Compensation-State-Tracked)**: 新增 `compensatedDeltaX / compensatedDeltaY` 累积补偿量字段；handleStop 重置；onConfigurationChanged 触发新一轮补偿测量。

### Scope

**In scope**:
- `OverlayService.kt`：扩展 `detectDisplacementAndBypass`、新增累积补偿字段、新增 `applyCompensationDelta(area)` 方法委托给 `WindowManagerBackend.update()`
- `WindowManagerBackend.kt`：`update(area)` 已支持任意 area；不需改动（验证）
- `OverlayBackend.kt`：不动
- `DisplayAttachedBackend.kt`：不动（仍走原 fallback 路径）
- `accessibility_service_config.xml`：不动（不需要 touch exploration 等高敏感 flag）
- `MainActivity.kt`：不动（用户开关复用 `switch_anti_transform`）
- `strings.xml`：更新 `anti_transform_label / anti_transform_desc` 说明文案，反映新语义

**Out of scope**:
- 不实现 TouchInteractionController 路径
- 不引入 a11y 触控拦截
- 不修改 FAB / 编辑器 / 录制 / quick-toggle
- 不引入新用户开关（复用 anti_transform）
- 不改 spec 已固化的 ACTION / EXTRA / RunState / manualBypass / autoBypass 字段含义

## Impact

| Surface | Impact |
|---|---|
| End user (HyperOS 单手模式) | 屏蔽矩形钉物理坐标；原本要保护的区域在单手模式下仍被覆盖 |
| End user (其他 ROM 无 transform) | `getLocationOnScreen` ≈ expectedY，补偿不触发，行为等同 v1.0.0 |
| Privacy / permissions | 0 新增（不动 a11y config） |
| App size | < 1 KB（仅几行逻辑追加） |
| Build / packaging | 无 |
| 与 physical-display-overlay 协同 | DisplayAttachedBackend 路径若仍漂移 → 触发 fallback → WindowManagerBackend 接管 → 享受反向补偿。两个 change 互补 |
| Risks | (a) HyperOS transform 可能持续追赶补偿形成抖动 → 用 hysteresis 阈值 + max-update-rate 缓冲；(b) 补偿期间触控帧 1-2 ms 滞后（用户感知不到） |

## Non-Goals

- 不阻止系统手势（back swipe 等）；用户的合法系统手势在矩形外不受影响
- 不实现自适应阈值（阈值固定 dp(2)）
- 不持久化补偿状态（每次启用重新测量）
- 不为多 transform 来源（如 PiP、split-screen）单独优化（HyperOS 单手是主要 case）

## Risks & Mitigations

| Risk | Mitigation |
|---|---|
| HyperOS transform 持续追赶 → 抖动循环 | 2 px 死区 hysteresis + 每帧仅 1 次 update + 累积补偿（不重设） |
| 用户拖动屏蔽区时被错误判为「漂移」 | 仅在 RunState.RUNNING 且非编辑模式时启用 detector |
| onPreDrawListener 性能 | O(1) 计算（getLocationOnScreen + 减法）；现有 detector 已是该路径 |
| 与 v1 让步行为的兼容性 | 通过 anti_transform 开关切换；用户旧偏好 `auto_bypass_displaced=true` 自动获得新「钉物理坐标」行为 |
| 触控判定区与 layoutParams 同步 | LayoutParams.x/y 是 WindowManager 决策的触控判定起点，update 后系统自动同步 |
| FAB 行为 | FAB 不在补偿范围内，继续随 transform 移动（spec 既定） |

## Success Criteria

1. **小米 13 Ultra HyperOS 实测**：启用屏蔽 → 触发单手模式 → 屏蔽矩形**保持原物理坐标**；触控测试：在矩形对应物理位置点击应被屏蔽
2. **抖动测试**：单手模式持续 30 秒 → 屏蔽矩形位置稳定（无可见抖动）
3. **anti_transform 开关切换**：从 `true` 到 `false` 立即生效（让步模式 = 原 v1 行为）；从 `false` 到 `true` 立即开始补偿
4. **Backend 协同**：DisplayAttachedBackend 漂移 → fallback 到 WindowManagerBackend → 自动开始反向补偿（一气呵成）
5. **现有 33 单元测试**：保持 green
6. **退出补偿**：handleStop / onDestroy 后 compensatedDeltaX/Y 重置；下次启用从 0 开始

## User Confirmations (Step 6)

| 决策点 | Boss 选择 |
|---|---|
| 公开 API 拿不到 onMotionEvent 选择性 drop 后的替代路径 | **A. 反向 LayoutParams 补偿** |
| onMotionEvent 路径（catastrophic）/ TouchInteractionController（重）/ 接受现状 | 全部 reject |
