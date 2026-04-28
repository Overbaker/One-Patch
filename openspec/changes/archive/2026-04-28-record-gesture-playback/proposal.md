# Change Proposal: record-gesture-playback

**Status**: Active
**Created**: 2026-04-28
**Related Spec**: gesture-recording (new) + bypass-state-machine (extension)

## Why

`BlockerAccessibilityService.triggerOneHandedGesture()` 当前用一段硬编码 4 px / 80 ms 的下滑（屏幕底部 `(w/2, h-4)` → `(w/2, h-1)`）尝试触发 OEM 单手模式。HyperOS 2.x（Xiaomi 13 Ultra 实测）将其识别为系统底部噪点抖动，**完全不响应**。OEM 单手模式触发手势在 ROM 间差异巨大：

- HyperOS 默认手势：底部边缘短上滑（手势导航）或 home 指示器下拽（三键导航）
- ColorOS / OneUI / Pixel：完全不同的几何 / 时长

对第三方 APP 来说，没有公开 API 可查询 OEM 当前实际绑定的手势。继续沿用任何「猜想式」硬编码都会在某些 ROM 上失效。

唯一可靠的方案：**让用户在自己的设备上演示一次有效手势，APP 录制 MotionEvent 序列，回放时复刻**。这把 ROM 兼容性的不确定性外化为「用户一次性配置」，从根上消除 fix-fab-bugs 中残留的「FAB 单击有时不触发单手模式」生产问题。

## What Changes

### Hard guarantees

- **G1 (Record-Demonstration-Persistence)**: 用户在 `RecordGestureActivity` 演示一次手势 → APP 持久化 `(x_px, y_px, t_ms)` 序列 + 录制时显示尺寸到 `gesture_prefs`。重启 APP 后录制仍存在。
- **G2 (Replay-Replaces-Hardcoded)**: `BlockerAccessibilityService.triggerOneHandedGesture()` 优先从 `GestureRepository` 加载录制；存在则按 `GestureDescription` 重建并 `dispatchGesture()`；不存在则 fallback 至当前硬编码 4 px / 80 ms 下滑。
- **G3 (Modal-Recorder-Isolation)**: 录制使用独立全屏 `RecordGestureActivity`（portrait, immersive），与 `OverlayService` 状态机完全隔离。录制期间 `RunState` / `manualBypass` / `autoBypass` / FAB / blocker rect 全部不受影响。
- **G4 (Reject-Out-Of-Bounds-Recording)**: 录制持续时长 > 60 000 ms 或 stroke 数 > 10 → 保存前硬拒绝 + Snackbar 提示重录；不静默截断、不分块 chain。
- **G5 (Single-Pointer-Recording)**: 多指手势在录制中被拒绝（multi-touch 检测到第二根手指 → 终止当前会话 + Snackbar 提示「请用单指演示」）。

### Architectural improvements

- **A1 (Repository-Pattern-Reuse)**: 新 `GestureRepository` 沿用 `SharedPrefsBlockAreaRepository` 模式（dedicated `gesture_prefs` 文件、`SCHEMA_VERSION`、flat `KEY_*` 常量、`Context.MODE_PRIVATE`）。无 JSON 序列化框架引入。
- **A2 (Coordinate-System-Saved)**: 录制坐标存为 absolute px，伴随 `saved_display_w_px` / `saved_display_h_px`。回放时按当前 `DisplayHelper.getRealDisplaySizePx()` 等比 rescale，与现有 `BlockArea.rescaleIfNeeded()` 模式一致。
- **A3 (Activity-Boundary-Decomposition)**: 录制属于 setup-time concern → 独立 Activity；回放属于 runtime concern → 留在 BlockerAccessibilityService。两者通过 GestureRepository 解耦，无直接调用。

### Scope

**In scope**:
- 新建 `app/src/main/java/com/ccg/screenblocker/RecordGestureActivity.kt`（全屏录制 + 倒计时 + 轨迹绘制 + 错误处理）
- 新建 `app/src/main/java/com/ccg/screenblocker/data/GestureRepository.kt`（持久化）
- 新建 `app/src/main/java/com/ccg/screenblocker/model/RecordedGesture.kt`（data class：strokes 列表 + savedDisplay）
- 修改 `app/src/main/java/com/ccg/screenblocker/service/BlockerAccessibilityService.kt:51-62` 的 `triggerOneHandedGesture()`：注入 GestureRepository，存在录制则用录制构造 GestureDescription
- 修改 `app/src/main/java/com/ccg/screenblocker/MainActivity.kt`：新增 TonalButton 入口（位置：`switch_anti_transform` 卡片下方，对齐 `btn_open_a11y_settings` 风格）
- 修改 `app/src/main/res/layout/activity_main.xml`：添加 `btn_record_gesture`
- 修改 `app/src/main/AndroidManifest.xml`：注册 RecordGestureActivity
- 新建 `app/src/main/res/layout/activity_record_gesture.xml`
- 修改 `app/src/main/res/values/strings.xml`：新增 zh-CN 字符串（en 同步可后续）

**Out of scope**:
- 多手势库（仅一个槽位 `one_handed_mode`）
- 多指 / 压感 / 设备来源还原（GestureDescription 丢失这些字段）
- 跨设备同步 / 云备份
- 录制成功率检测（dispatchGesture onCompleted ≠ ROM 实际识别，无可靠 API）
- 回放期间调试 trail 渲染
- 触发方式按 ROM 自适应（用户始终录制一次）

## Impact

| Surface | Impact |
|---|---|
| End user | 一次录制后 FAB 单击稳定触发其设备的单手模式；首次使用沿用硬编码 fallback 不影响现有行为 |
| Privacy / permissions | 不引入新权限；录制坐标本地存储 |
| App size | < 5 KB（Activity + Repository + Layout + Drawable） |
| Build / packaging | 无新依赖 |
| Existing users (upgrade) | 无录制时 fallback 至硬编码，行为与 v1 一致；用户主动录制后启用录制路径 |
| Risks | (a) ROM 过滤 a11y 注入手势 — 文档说明、UX 中暴露「测试」按钮；(b) 录制时设备屏幕被 modal 覆盖 — 倒计时显式提示用户；(c) 全屏 Activity 无法覆盖 navbar 区 — 接受：navbar 区底部 ≤ 24 dp 不可录制，ROM 单手模式起点几乎不在 navbar 内 |

## Non-Goals

- 不实现 OEM 单手模式实际激活的成功探测（无可靠 API）
- 不暴露第三方 broadcast 接口允许其他 APP 触发录制
- 不持久化录制压感 / tool type / source（GestureDescription 不支持）
- 不在录制中让 blocker rect 自动暂停（独立 Activity 不与 OverlayService 共享状态）
- 不为 navbar / statusbar 区强行 over-draw（系统安全设计）
