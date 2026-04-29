# Design: transform-compensation-overlay

## Multi-Model Synthesis (from research phase)

| Source | Headline finding |
|---|---|
| Codex (research) | onMotionEvent / setMotionEventSources / TouchInteractionController 全部不可行；唯一公开 API 路径是 closed-loop layoutParams 反向补偿 |
| Gemini (research) | autoBypass 语义重塑而非删除（向后兼容用户偏好）；anti_transform 开关含义改写但不更名 |
| Convergence | 复用现有 displaceDetector 基础设施；新增累积补偿状态字段；与 v1 让步路径互斥（anti_transform 开关二选一） |

## Architecture Decision

### Rationale

研究阶段确认：第三方公开 API 下不存在「物理坐标 input filter」干净方案。`LayoutParams.x/y` 是**唯一**公开 API 中**同时控制 view 视觉位置 + 触控判定区**的字段。SurfaceFlinger transform 不修改 LayoutParams，仅在合成阶段平移 view —— 反向调整 LayoutParams 等于让 view 在「即将被 transform 平移」之前先反向偏移，合成结果 = 物理目标位置。

**关键洞察**：
- `WindowManager.LayoutParams.x/y` = window 在物理屏幕上的请求位置
- SurfaceFlinger transform = 在合成阶段对 display-area 整体平移
- 实际呈现位置 = LayoutParams.x/y + transform 偏移
- 反向补偿: `LayoutParams.x/y -= drift` → 实际呈现位置 = (LayoutParams - drift) + drift = 期望位置 ✓

**触控判定区追随**：WindowManager 用 LayoutParams.x/y 决定 hit-test 区域；同步反向偏移意味着「触控判定区」也钉在物理坐标。

### Rejected Alternatives

1. **每帧 detect + update**（无 hysteresis）— rejected: HyperOS transform 与 LayoutParams update 同源（都改 view 位置），可能引发抖动（每次 update 触发新一轮 transform → drift → update）
2. **重设式补偿**（`params.y = expectedY - driftY`）— rejected: 假设 transform 是常量，但 HyperOS 单手模式中 transform 随用户调节强度变化；累积式补偿对动态 transform 鲁棒
3. **使用 view.translationY 抵消**（不动 LayoutParams）— rejected: translationY 仅影响视觉，不影响触控判定区，G2 失败
4. **新增独立用户开关「钉物理坐标」**（与 anti_transform 并存） — rejected: 配置面复杂度上升 +1；anti_transform 用户既有偏好可直接迁移到新语义
5. **检测频率 throttle 到 16ms**（用 Choreographer.postFrameCallback） — rejected: OnPreDrawListener 已是 vsync-aligned，无需额外 throttle
6. **setOnApplyWindowInsetsListener 监听 system bar inset 变化** — rejected: 单手模式不改 inset，改的是 SurfaceFlinger transform；inset listener 不触发

### Rejected Detection Signals（单手模式检测信号选型）

**核心结论**：grok-search 多角度调研（broadcast / Configuration / WindowInsets / DisplayListener / Settings ContentObserver / View.matrix）确认 **HyperOS 单手模式没有任何公开 API 提供「主动事件通知」**。所有候选信号在 HyperOS 上失效：

| 候选检测信号 | HyperOS 实测 / 文档 | Reject 原因 |
|---|---|---|
| `Intent.ACTION_*` 广播 | 无公开 broadcast | Android 公开 API 无此 intent；HyperOS 也不发自定义广播 |
| `Activity.onConfigurationChanged` / `Configuration.screenHeightDp` | matrix transform 不触发 config 变更 | 单手模式只改 SurfaceFlinger 合成 transform，不改 logical configuration |
| `View.OnApplyWindowInsetsListener` / `WindowInsets` 变化 | inset 不变 | 单手模式不修改 system bar / cutout / IME insets |
| `DisplayManager.DisplayListener.onDisplayChanged` | 取决于 ROM 实现，HyperOS 不一致触发 | 不可作为唯一信号 |
| `ContentObserver` 监听 `Settings.System.one_handed_mode_*` | key 名 ROM 私有，未稳定文档化 | 需 adb 探测，跨版本不稳定；用户可能未通过 settings 触发（手势直接进入） |
| `View.getMatrix()` / `View.getGlobalVisibleRect` | 仅反映 view 树自身 matrix，不反映 SurfaceFlinger compositor transform | view 的 matrix 在单手模式下不变；compositor transform 不暴露给 app 层 |

**唯一可行**：`ViewTreeObserver.OnPreDrawListener` + `OverlayView.getLocationOnScreen()` 比对 `expectedX / expectedY` 检测**已发生**的位置漂移。这是 trailing detection（事后检测，1 frame 滞后），但是公开 API 下唯一可获取「view 实际渲染位置」的接口。

**接受的 trade-offs**：
- 检测滞后 ≤ 1 vsync (~16ms at 60Hz, ~8ms at 120Hz)；用户首帧可能看到屏蔽矩形被 transform 平移后立即被反向补偿钉回，整个过程在感知阈值内
- 必须与「单手模式」之外的位移源解耦：当前已通过 backend 类型筛选（仅 WindowManagerBackend 路径补偿）+ runState 门控 + manualBypass 门控覆盖

### Assumptions

- `LayoutParams.x/y` 修改后 SurfaceFlinger 在下一 vsync 应用新位置
- HyperOS transform 是「相对 LayoutParams 的偏移」而非「绝对坐标 override」（实测前不可证；若是后者，反向补偿无效）
- `OverlayView.getLocationOnScreen()` 在 transform 应用后返回物理屏幕坐标（AOSP 文档保证）
- `WindowManager.updateViewLayout()` 不会触发 layout pass（仅改 window position），不引入额外 frame 延迟
- 单手模式 transform 收敛在 1-2 frame 内稳定（用户感知期间）

### Potential Side Effects

- 1-2 frame 视觉滞后（用户首次进入单手模式时屏蔽矩形闪一下后钉住；用户感知不到）
- `anti_transform=true` 默认 → 已有用户体验改变（之前是「让步」，现在是「钉物理坐标」）；通过 strings.xml 描述变更告知

## Resolved Constraints

| # | Constraint | Concrete value |
|---|---|---|
| C1 | 反向补偿启用条件 | `runState == RUNNING` AND `activeBackend is WindowManagerBackend` AND `anti_transform == true` AND `!manualBypass` |
| C2 | Hysteresis 死区阈值 | `dp(2)` 像素，绝对值；`abs(driftX) < 2dp && abs(driftY) < 2dp` 不触发补偿 |
| C3 | 检测器 | 复用现有 `displaceDetector` (OnPreDrawListener)，不新增 |
| C4 | 补偿语义 | **累积式**：`compensatedDeltaX -= driftX; compensatedDeltaY -= driftY`；不重设 |
| C5 | LayoutParams 实际值 | `params.x = area.leftPx + compensatedDeltaX; params.y = area.topPx + compensatedDeltaY` |
| C6 | 每帧最多 update 次数 | 1 次（OnPreDrawListener 自然 vsync-bound） |
| C7 | 漂移基准（expectedX/Y） | `area.leftPx / area.topPx`（不动；compensatedDelta 累积偏移） |
| C8 | 补偿状态生命周期 | `addOrUpdateOverlay` fresh attach 时重置为 0；`handleStop` 重置；`onConfigurationChanged` 重置（新 area 重新测量） |
| C9 | DisplayAttachedBackend 路径 | 不应用反向补偿；保持 physical-display-overlay change 既定行为（drift → fallback to WindowManagerBackend） |
| C10 | autoBypass 字段保留 | 是；但 `anti_transform=true` 路径下 detector 不再 set autoBypass，改为 set compensatedDelta；`anti_transform=false` 时回到 v1 让步行为（set autoBypass） |
| C11 | manualBypass 优先级 | 高于补偿：`manualBypass=true` 时 detector 早返回，不补偿、不让步 |
| C12 | anti_transform=false 时 | 沿用 v1 让步行为（autoBypass）；不补偿 |
| C13 | rotation / config change | onConfigurationChanged → 重新读取 area → 重置 `compensatedDeltaX/Y = 0` → 调 `WindowManagerBackend.update(rescaledArea)` |
| C14 | 视觉首帧滞后 | 接受最多 1 vsync (~16ms) 视觉滞后；用户感知不到 |
| C15 | FAB 不补偿 | FAB 仍随 transform 移动（spec 既定，UX 一致） |
| C16 | strings.xml 文案更新 | `anti_transform_label` 改为 "屏蔽区物理坐标锁定"；`anti_transform_desc` 改为 "开启后屏蔽区在小米单手模式下保持物理屏幕原位；关闭后屏蔽区随单手模式平移并自动让步" |
| C17 | 阈值不可配置 | dp(2) 写死在 OverlayService companion；不暴露给用户 |
| C18 | 测试可性 | 反向补偿核心是纯算术（`area.x + compensatedDelta`）；可纯 JVM 测；hysteresis 边界可参数化测 |
| **C19** | **检测信号唯一来源** | **`OverlayView.viewTreeObserver.addOnPreDrawListener` + `getLocationOnScreen()` 比对 `expectedX/Y`**；不依赖 broadcast / Configuration / WindowInsets / DisplayListener / Settings ContentObserver / View.matrix（HyperOS 全部不可靠或不触发） |
| **C20** | **检测延迟界**  | **≤ 1 vsync (~16ms at 60Hz)**；trailing detection 接受为 trade-off；用户感知阈值（~100ms）内 |
| **C21** | **检测的目标 view** | **`OverlayView`（屏蔽矩形）自身**，不在 decorView 上挂 detector；理由：仅关心屏蔽矩形是否被平移，decorView 在 compositor transform 下也漂移但不指示矩形 |
| **C22** | **冷启动 transform 处理** | service 启动时若单手模式已激活：fresh-attach 后第一次 OnPreDraw 即检测到 drift > hysteresis → 立即触发首次补偿；用户可能看到首帧漂移后即归位 |
| **C23** | **检测器去除时机** | `removeOverlayIfAttached`（detach 时） + `onDestroy`；与现有 `displaceDetector` 生命周期一致 |
| **C24** | **多帧 hysteresis（避免误检）** | **不需要**：dp(2) 已是 noise floor；HyperOS transform 不会单帧抖动跨 dp(2)；多帧 hysteresis 只在「检测 → 触发 fallback」（physical-display-overlay change 已实施）场景需要 |
| **C25** | **「位置」语义三层模型** | 显式区分 view 在 Android 渲染管线的三种「位置」：(a) **physical pixel position** — 硬件像素层面，**无公开 API 可查**；(b) **SurfaceFlinger composited position** — compositor 合成后位置，仅 `dumpsys SurfaceFlinger` / Winscope trace 可见，**第三方应用不可查**；(c) **ViewRootImpl-reported position** — `getLocationOnScreen()` 返回值，是 (a) (b) 的**最佳近似**但 ROM-dependent（HyperOS 等 OEM 通过 ViewRootImpl.mTranslator 部分但不完美地反映 SurfaceFlinger transform） |
| **C26** | **检测信号精度声明** | 反向补偿基于 `getLocationOnScreen` 报告漂移（即 (c) ViewRootImpl-reported position）。补偿目标 = 消除「报告漂移」；实际视觉钉物理坐标的精度 **取决于 ROM 对 SurfaceFlinger transform 的反映程度**。HyperOS v1 实测：dp(50) 阈值的 displaceDetector 触发 autoBypass 让步行为成功 → 报告漂移信号在 HyperOS **存在且足够大**（足以驱动反向补偿）；但实际视觉残余漂移可能 1-20% 不被反映（接受 trade-off） |
| **C27** | **拒绝反射访问 mTranslator/mInvCompatScale** | `ViewRootImpl.mTranslator` / `mInvCompatScale` 是 `@hide` / `@UnsupportedAppUsage`；反射访问跨版本不稳定、违反 Android 隐藏 API 限制（API 28+ 触发 reflection denylist）。**坚持仅用公开 API**（`getLocationOnScreen`），接受其精度局限 |
| **C28** | **首帧 detector 结果作为基线锚点** | `addOrUpdateOverlay` fresh attach 后第一次 OnPreDraw：若 `getLocationOnScreen` 报告位置 ≠ `(area.leftPx, area.topPx)`（说明 service 启用时单手模式已激活），立即触发首次补偿。`expectedX/Y` 始终设为 `area.leftPx/topPx`（物理 BlockArea 坐标），不动态更新 |
| **C29** | **不同 backend 检测信号一致性** | `WindowManagerBackend` 和 `DisplayAttachedBackend` 均依赖同一 `OverlayView.getLocationOnScreen` 检测（物理坐标系一致）；compensatedDelta 仅用于 WindowManagerBackend（C9）；DisplayAttachedBackend 的漂移触发 fallback 而非补偿（physical-display-overlay 既定） |

## PBT Properties

| ID | Name | Definition | Boundary | Falsification |
|---|---|---|---|---|
| **P1** | Compensation arithmetic | 给定 area 和 (compensatedDeltaX, Y)，反向补偿后的 layoutParams = (area.leftPx + compensatedDeltaX, area.topPx + compensatedDeltaY)；纯函数 | 任意 (area, delta) 对 | 直接 assert 算术等式 |
| **P2** | Hysteresis dead-zone | drift 绝对值 ≤ dp(2) 时不修改 compensatedDelta | drift ∈ {0, ±1, ±2, ±3} dp | 注入 drift 序列；assert delta 仅在 \|drift\| > 2dp 时变化 |
| **P3** | Cumulative compensation | N 次连续 drift（同向）后，compensatedDelta 单调减少 | drift = +5dp 重复 N 次 | assert delta 序列单调，绝对值 ≥ N×3dp |
| **P4** | Reset on stop | handleStop 后 compensatedDeltaX/Y == 0 | 任意运行时累积量 | snapshot before/after handleStop |
| **P5** | Reset on config change | onConfigurationChanged 后 compensatedDelta 归零；新一轮检测从 0 开始 | rotation / display size change | 模拟 config change；assert 重置 |
| **P6** | manualBypass overrides compensation | manualBypass=true 时 detector 不修改 compensatedDelta（也不 set autoBypass） | 同时模拟 manualBypass=true 和 drift > threshold | assert 两者皆不变 |
| **P7** | Backend exclusivity | DisplayAttachedBackend 路径下不补偿 | activeBackend.id() == "PHYSICAL_DISPLAY" | inject drift；assert compensatedDelta 不变 |
| **P8** | anti_transform=false fallback | 开关关闭时走 v1 autoBypass 让步路径 | drift > threshold + anti_transform=false | assert autoBypass = true 且 compensatedDelta 不变 |
| **P9** | anti_transform=true compensation | 开关开启时走反向补偿 + autoBypass 不被 set | drift > threshold + anti_transform=true | assert compensatedDelta 变化 + autoBypass = false |
| **P10** | RunState gate | runState != RUNNING 时 detector 跳过 | runState ∈ {STOPPED, ARMING} | inject drift；assert 无补偿无 autoBypass |
| **P11** | Convergence target | 一次成功补偿后，view 实际位置 ≤ dp(2) drift（hysteresis 内） | 模拟 transform delta D；补偿 -D | 算术：actual = (area + (-D)) + D = area ✓ |
| **P12** | Detection latency bound | 任何外部 transform 应用后，下一次 OnPreDraw 必触发检测（≤ 1 vsync 滞后） | inject simulated transform → 下一帧 mock detector 必收到 drift | mock viewTreeObserver；assert detector 在下一 invalidate 后被调用 |
| **P13** | Detector signal source uniqueness | `detectDisplacementAndBypass` 仅从 `OverlayView.getLocationOnScreen` 读取信号；不从 Configuration / WindowInsets / DisplayListener / Settings / matrix 读 | 代码静态扫描 | grep 验证 `detectDisplacementAndBypass` 函数体仅引用 `getLocationOnScreen` 和 area 字段 |
| **P14** | HyperOS 漂移信号实测下界 | v1 实测：HyperOS 单手模式触发后，`getLocationOnScreen` 返回的 view 位置与 `expectedY` 的差异 ≥ dp(50)（足以驱动 v1 displaceDetector 触发 autoBypass）；本 change 把阈值降至 dp(2) 后必然能检测到同等或更微小的漂移 | HyperOS 真机 + 单手模式触发 | adb logcat 验证 detectDisplacementAndBypass log 输出 `compensate drift=...` 至少一条，drift 绝对值 > dp(2) |
| **P15** | No-Op on AOSP / 无 transform 设备 | 在标准 AOSP / Pixel / 普通 ROM 上，未触发任何 transform 时 detector 不修改 compensatedDelta（noise floor 保护） | drift = 0 / drift < dp(2) | 注入零漂移；assert compensatedDelta 全程 = 0 |
| **P16** | 反射访问拒绝（API 卫生） | 实现不引用 `mTranslator`、`mInvCompatScale`、`@hide` / `@UnsupportedAppUsage` 字段 | 代码静态扫描 + lint | grep `mTranslator\|mInvCompatScale\|getDeclaredField`：在 OverlayService 内 0 命中 |

## Implementation Plan (high-level → maps to tasks.md)

1. **OverlayService**: 新增 `compensatedDeltaX / compensatedDeltaY` 字段；提取 `applyCompensation(driftX, driftY)` 算法；扩展 `detectDisplacementAndBypass` 分支（anti_transform 开关 + backend 检查）
2. **handleStop / onConfigurationChanged / addOrUpdateOverlay** 重置 compensatedDelta
3. **WindowManagerBackend.update**: 验证已支持任意 area 偏移（C5 语义），如需则微调
4. **strings.xml**: 改 anti_transform_label / desc 文案
5. **PBT 测试**: 纯算术 + 状态机测试
6. **编译 + 测试 + spec 合规验证**
7. **归档**

## Considerations

| Aspect | Note |
|---|---|
| Performance | 每帧 O(1)（getLocationOnScreen + 算术 + 可能 1 次 updateViewLayout）；vsync-bound，无溢出风险 |
| Maintainability | 新增 ≤ 30 行代码；纯函数 applyCompensation 易测；不引入新类 |
| Testing | 算法纯函数可纯 JVM 测；3 个新测试覆盖 P1/P2/P3/P4/P5 |
| Compatibility | 不动 minSdk / API；DisplayAttachedBackend 行为不变 |
| Rollback | 删除 compensatedDelta 字段 + 恢复 detect 旧逻辑 + 恢复 strings 文案 = 完全 v1 行为 |
| Privacy | 0 |
| Security | 0 新增权限或 broadcast |
