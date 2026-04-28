# Change Proposal: physical-display-overlay

**Status**: Active
**Created**: 2026-04-28
**Related Spec**: bypass-state-machine (extension)

## Why

HyperOS 单手模式（display-area transform）在 SurfaceFlinger 层把整个 display-area 平移／缩放到屏幕一侧，**所有 window-level overlay 都跟随平移**——包括 `TYPE_ACCESSIBILITY_OVERLAY` 这种 trusted 窗口。当前 One Patch 的屏蔽矩形虽用 trusted overlay，仍会随单手模式下移，导致：

1. 用户启用单手模式时，屏蔽方块跟着下移到屏幕中部，**不再覆盖原本要保护的物理区域**（如顶部状态栏附近的误触区）
2. 现有的 `autoBypass` 检测到位移后只能让步暂停，但用户的初衷是「在单手模式下也屏蔽原物理位置」——而非妥协放开

调研（grok-search × 6 角度 + AOSP 文档）锁定唯一公开方案：**Android 14 / API 34 新增的 `AccessibilityService.attachAccessibilityOverlayToDisplay(displayId, SurfaceControl)`**。该 API 把 overlay 直接绑到 SurfaceFlinger 的物理 display layer，**不属于任何 window**，因此不被 window-level transform 影响。AOSP 设计意图正是为此类场景。

项目 `targetSdk=34` 满足 API 要求；`minSdk=26` 决定必须保留当前路径作 fallback。HyperOS fork 了 SurfaceFlinger，新 API 在 HyperOS 上的行为公网无实测，必须设计**自检测漂移 + 自动 fallback** 兜底。

## What Changes

### Hard guarantees

- **G1 (Physical-Display-Binding)**: API 34+ 设备且 a11y 服务可用时，运行态 overlay 通过 `attachAccessibilityOverlayToDisplay(Display.DEFAULT_DISPLAY, surfaceControl)` 绑定到物理 display layer。位置由 `SurfaceControl.Transaction.setPosition(area.leftPx, area.topPx)` 控制，使用绝对像素坐标。
- **G2 (Drift-Self-Monitor)**: 现有 `displaceDetector` 持续监测 `OverlayView.getLocationOnScreen()`。检测到 view 实际位置漂移超过 `dp(50)` 时（说明新 API 在该 ROM 上仍被 transform），自动 detach + fallback 到老路径，恢复 `autoBypass` 兜底行为。
- **G3 (Legacy-Path-Preserved)**: API < 34 设备 / a11y 服务未连接 / SurfaceControl 创建失败 / 自检测触发 → 全部走当前 `WindowManager.addView(TYPE_ACCESSIBILITY_OVERLAY/TYPE_APPLICATION_OVERLAY)` 路径，行为与 v1.0.0 完全一致。
- **G4 (Touch-Semantics-Identical)**: 新路径下 `OverlayView` 通过 `SurfaceControlViewHost` 嵌入；触控分发由 host 自动转发。`OverlayView.onTouchEvent` 行为保持 `return !bypassRuntime`。`manualBypass` / `autoBypass` 通过 `applyBypassState()` 仍是单一 sink。
- **G5 (FAB-Path-Unchanged)**: FAB 仍用 `TYPE_APPLICATION_OVERLAY` + `WindowManager.addView`。FAB 在单手模式下随 transform 下移属于 acceptable UX（FAB 设计目的就是「让步用户拇指」）。

### Architectural improvements

- **A1 (Backend-Abstraction)**: 引入 `OverlayBackend` 接口（`attach(area)` / `update(area)` / `detach()`）；两个实现：`WindowManagerBackend`（现行）+ `DisplayAttachedBackend`（新）。`OverlayService.addOrUpdateOverlay` 路由到当前激活的 backend。
- **A2 (Channel-Status-Tri-State)**: `tv_channel_status` 新增第 3 状态字符串：`运行中 · 通道：PHYSICAL_DISPLAY (display-attached)`。沿用既有 ACCESSIBILITY (trusted) / APPLICATION_OVERLAY 文案。
- **A3 (Anti-Transform-Repurposed)**: `auto_bypass_displaced` 开关保留含义不变，但在新路径下兼任「漂移检测器」驱动 fallback 触发。

### Scope

**In scope**:
- 新建 `service/OverlayBackend.kt`（接口）+ `service/WindowManagerBackend.kt`（封装现行逻辑）+ `service/DisplayAttachedBackend.kt`（新 API 34 路径）
- `OverlayService.kt`：`addOrUpdateOverlay` / `removeOverlayIfAttached` / `applyBypassState` / `onConfigurationChanged` / `displaceDetector` 改造为 backend-aware 路由；增 `selectBackend()` 选型 + fallback 逻辑
- `BlockerAccessibilityService.kt`：新增 `fun attachOverlayToDisplay(sc: SurfaceControl): Boolean` 方法包装 API 34 调用；新增 `fun isApi34DisplayAttachSupported(): Boolean`
- `MainActivity.kt`：`renderState` 增第 3 状态读取 `binder.getActiveBackend()`
- `strings.xml`：增 `channel_status_physical_display` + 新提示
- 双路径切换的状态保留（`manualBypass / autoBypass / runtime_visible / fab_visible` 不重置）

**Out of scope**:
- FAB 路径不动（保留 WindowManager + TYPE_APPLICATION_OVERLAY）
- 编辑器（FullscreenEditorActivity）不改
- 录制 Activity / GestureRepository 不改
- spec 已固化的 ACTION / EXTRA 命名不变
- 不为 backend 选择新增独立用户开关（自动选择 + 自动 fallback 是设计目标）

## Impact

| Surface | Impact |
|---|---|
| End user (API 34+ + a11y) | 单手模式下屏蔽矩形固定物理坐标，原本被遮的区域仍被保护 |
| End user (API 26-33 / a11y 未启 / fallback) | 行为完全等同 v1.0.0；`autoBypass` 兜底仍生效 |
| Privacy / permissions | 不引入新权限；仍依赖现有 a11y 服务 |
| App size | < 8 KB（3 个新 Kotlin 类 + ≤ 5 个 string） |
| Build / packaging | 无新依赖；`@RequiresApi(34)` 标注隔离新 API |
| Existing users | 升级后自动启用新路径（API 34+），失败自动 fallback；用户感知透明 |
| Risks | (a) HyperOS SurfaceFlinger fork 是否仍 transform 新 API ——已用 displaceDetector 自检测兜底；(b) `SurfaceControlViewHost.getLocationOnScreen` 是否反映 SurfaceControl 位置——若不反映需补充检测维度（见下方 Risks）|

## Non-Goals

- 不替换 / 重构 FAB 实现
- 不为编辑器路径引入物理 display 绑定（编辑器是 EDIT 模式，不在运行态 transform 影响范围）
- 不实现旋转期间的「无缝」surface 重定位（`SurfaceControl` 在 config change 上 remove-and-recreate 即可，复杂度更低）
- 不持久化 backend 选择偏好（重启自动 re-detect）
- 不改 spec 已固化的 ACTION_TOGGLE_BYPASS_AND_GESTURE / FAB / quick-toggle / gesture-recording 行为

## Risks & Mitigations

| Risk | Mitigation |
|---|---|
| HyperOS SurfaceFlinger fork 仍 transform 新 API 的 SurfaceControl | displaceDetector 自检测 → auto-fallback 老路径；用户仍可用 |
| `SurfaceControlViewHost.OverlayView.getLocationOnScreen` 不反映外部 SurfaceControl 位置 | 备选检测：用 a11y 的 `getMagnificationRegion` 或 `Display.getRealSize` 对比；若都失败，保留 anti_transform 老逻辑 |
| API 34 attach 失败而异常未被 catch → 服务崩溃 | `runCatching { ... }` 包裹整个 attach 流程，失败立即 fallback |
| SurfaceControl / SurfaceControlViewHost 资源泄漏（onDestroy 漏 release） | 显式 `release()` 在 `removeOverlayIfAttached / handleStop / onDestroy` 三处调用；ProGuard 不 obfuscate |
| 旋转 / display 改变期间 surface 残留 | onConfigurationChanged 触发 `detach() + attach(rescaledArea)`；间隔 1 frame 内完成（用户不可见） |
| 新旧路径 attach 期间触控行为不一致 | 测试矩阵覆盖：API 26 / 30 / 34 真机 + emulator 32/34 |
| a11y 服务中途断连 → physical-display surface 失去 owner | `BlockerAccessibilityService.onUnbind` 时通知 `OverlayService` 触发 fallback rebuild（保持当前 ACTION_A11Y_AVAILABLE 模式镜像） |

## Success Criteria

1. **小米 13 Ultra HyperOS 实测**：启用屏蔽 → 触发单手模式 → 屏蔽方块**保持原物理坐标**，下移屏内容里看不到屏蔽方块下半区域跑到中部
2. **Android 13 设备**（API 33，无 attachAccessibilityOverlayToDisplay）：行为与 v1.0.0 完全一致；`tv_channel_status` 显示 `ACCESSIBILITY (trusted)` 或 `APPLICATION_OVERLAY`
3. **MainActivity 状态栏**：API 34+ + a11y 启用时显示 `运行中 · 通道：PHYSICAL_DISPLAY (display-attached)`
4. **fallback 透明**：HyperOS 仍 transform 新 API → displaceDetector 检测漂移 → 自动 fallback；`tv_channel_status` 切换为 `ACCESSIBILITY (trusted)`；用户操作不被打断
5. **触控语义**：`manualBypass / autoBypass / FAB 单击双击 / runtime_visible 开关 / 通知交互` 在新旧路径下行为字节级一致
6. **资源清理**：启停 100 次后 `dumpsys SurfaceFlinger` 无残留 layer；`adb shell dumpsys meminfo com.overbaker.deadzone` 内存稳定
7. **现有 28 个单元测试**：全部保持 green（新增 backend 抽象不破坏既有契约）

## User Confirmations (Step 6)

| 决策点 | Boss 选择 |
|---|---|
| FAB 是否迁移 display-attached | **保留 WindowManager 路径**（FAB 设计上就是随屏跟手） |
| API 34 attach 失败策略 | **立即 fallback 老路径**（透明，不打断用户） |
| anti_transform 开关处理 | **保留作驱动 fallback 检测**（双路径统一含义） |
| tv_channel_status 状态显示 | **增第 3 状态 PHYSICAL_DISPLAY**（诊断信息透明） |
