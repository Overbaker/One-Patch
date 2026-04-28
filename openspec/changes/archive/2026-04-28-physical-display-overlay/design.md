# Design: physical-display-overlay

## Multi-Model Synthesis

| Source | Headline finding |
|---|---|
| Codex (research phase) | OverlayService.addOrUpdateOverlay 是 sole attach pipeline；`applyBypassState` 通过 LayoutParams.flags 切换 FLAG_NOT_TOUCHABLE — 新路径无法直接复用；OverlayView.onTouchEvent 已 bypass-aware 可复用；displaceDetector 的 `getLocationOnScreen` 比较是核心检测机制，但嵌入 SurfaceControlViewHost 后语义可能改变 |
| Gemini (research phase) | spec 兼容性：bypass-state-machine 不变 / floating-button RunState 联动不变；新增第 3 状态 `PHYSICAL_DISPLAY`；anti_transform 重新定位为 fallback 触发器；FAB 不动 |
| Convergence | 引入 `OverlayBackend` 接口抽象；2 个实现（WindowManager / DisplayAttached）；自动选型 + 自动 fallback；状态机零变更；FAB 路径完全不动；旋转用 remove-and-recreate |

## Architecture Decision

### Rationale

`OverlayService.addOrUpdateOverlay` 当前是 ~80 行的混合逻辑，掺杂「选择 channel」「构造 LayoutParams」「addView」「绑 displaceDetector」「applyBypassState」「附 FAB」。引入 backend 抽象的核心动机：

1. **关注点分离**：channel selection / param 构造 / 触控分发 / 状态机 是 4 个独立维度，但当前 hard-coupled 到 WindowManager API 上。
2. **API 34 隔离**：`SurfaceControl` / `SurfaceControlViewHost` / `attachAccessibilityOverlayToDisplay` 必须在 `@RequiresApi(34)` 守护下，禁止在 API < 34 设备的 class loader 解析时崩溃。
3. **fallback 透明化**：当新 backend 自检测漂移时，能在不改 OverlayService 状态机的前提下切换实现。

新 backend 接口只暴露 4 个方法（attach / update / detach / observePosition），所有状态机（RunState / manualBypass / autoBypass）仍在 OverlayService 内，符合既有 spec。

### Rejected Alternatives

1. **不抽象，在 addOrUpdateOverlay 内 if-else 双分支** — 拒绝：`SurfaceControl` 类在 API 34- 设备上即使不调用、仅引用类名也可能触发 verifier 错误；隔离至独立类 + `@RequiresApi(34)` 是唯一安全模式。
2. **把 backend 切换暴露为用户开关** — 拒绝：Boss Step 6 决策「自动选择」；用户开关增加配置面复杂度，与「fallback 透明」目标冲突。
3. **修改 OverlayView 让其知道自己在哪种 backend 内** — 拒绝：违反 KISS；OverlayView 应保持 backend-agnostic（`runtimeVisible` + `bypassRuntime` 是仅有的运行时输入）。
4. **每帧自检测漂移** — 拒绝：现有 displaceDetector 已是 OnPreDrawListener（每帧），但仅在条件满足时执行重 logic；保留即可。
5. **rotate 时 in-place resize SurfaceControl** — 拒绝：复杂（需要 setBufferSize + setGeometry + 重新 dispatch touch region）；remove-and-recreate 在 1 frame 内完成，用户不可见。

### Assumptions

- `SurfaceControlViewHost` 在 API 30+ 引入，`attachAccessibilityOverlayToDisplay` 在 API 34+；项目 `compileSdk=34` 满足。
- `BlockerAccessibilityService.get()` 单例模式可靠（已在 fix-fab-bugs 验证）。
- `Display.DEFAULT_DISPLAY` 始终是物理主屏（项目仅 portrait 单屏，不考虑外接副屏）。
- `OverlayView.runtimeVisible` 和 `bypassRuntime` setter 不依赖 attach 通道。
- HyperOS 实测前不知 API 34 路径在该 ROM 上是否实际起作用 — 自检测漂移 + fallback 必须保证用户体验不退化。

### Potential Side Effects

- 第三方 a11y 服务可能影响 SurfaceFlinger 行为（罕见，不主动适配）
- 通知文本变更（`buildRunning` 已 backend-agnostic，无影响）
- ProGuard / R8 release 配置：新 SurfaceControl/SurfaceControlViewHost API 不可被混淆（系统 framework 类，不会被 R8 触及；项目内的 OverlayBackend 接口需保留）

## Resolved Constraints

| # | Constraint | Concrete value |
|---|---|---|
| C1 | minSdk / targetSdk | minSdk=26（API 26）, targetSdk=34（API 34） — 双 backend 必备 |
| C2 | API 34 路径 attach API | `AccessibilityService.attachAccessibilityOverlayToDisplay(Display.DEFAULT_DISPLAY, surfaceControl)` |
| C3 | OverlayView 嵌入容器 | `SurfaceControlViewHost(context, displayId, hostToken=null)` (API 30+); `setView(overlayView, w, h)` |
| C4 | Surface 初始位置 | `SurfaceControl.Transaction().setPosition(sc, area.leftPx.toFloat(), area.topPx.toFloat()).apply()` |
| C5 | Surface 大小 | `SurfaceControl.Builder().setBufferSize(area.widthPx, area.heightPx)` |
| C6 | Surface 格式 | `setFormat(PixelFormat.TRANSLUCENT).setOpaque(false)` |
| C7 | API 34 可用性检测 | `Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE` (34) AND `BlockerAccessibilityService.get() != null` AND `accessibilityService.canAttachOverlayToDisplay()` (运行时探针) |
| C8 | Backend 选型时机 | 仅在 `addOrUpdateOverlay` fresh-attach 分支决策一次；channel switch / re-attach 时重新选 |
| C9 | Backend 切换粒度 | 每次 fresh attach 独立决策（不持久化偏好） |
| C10 | Drift 检测频率 | 沿用 `OverlayView.viewTreeObserver.addOnPreDrawListener`（每帧），现有 displaceDetector 不动 |
| C11 | Drift 阈值 | 沿用 `dp(50)`，不变 |
| C12 | Drift 检测语义（API 34 路径） | `OverlayView.getLocationOnScreen()` 返回**嵌入 view 自身**位置；如果 SurfaceControlViewHost 内 view 永远在 host (0,0)，需备选检测 |
| C13 | Drift 备选检测 | API 34 路径下，对比 `currentArea.topPx` vs `overlayView.getLocationOnScreen()[1]` — 若两者差异 > dp(50) 持续 2 帧（避免抖动）→ 触发 fallback |
| C14 | Fallback 触发机制 | `triggerFallbackToWindowManager(reason: String)`：detach 当前 backend → switch activeBackend = WindowManagerBackend → attach(currentArea) → applyBypassState() |
| C15 | Fallback 后是否再尝试 API 34 | 否：本次 service 生命周期内不再切回（防 ping-pong）；handleStop 后下一次 enable 重新决策 |
| C16 | onConfigurationChanged 行为 | API 34 路径下：`activeBackend.detach()` → `activeBackend.attach(rescaledArea)`；间隔在同一 main-thread frame 内完成 |
| C17 | a11y service 断连行为 | `BlockerAccessibilityService.onUnbind` 时若当前 backend == DisplayAttachedBackend → 触发 `triggerFallbackToWindowManager("a11y_unbound")` |
| C18 | 资源释放点（API 34 路径） | 3 处：`detach()` 内（每次 channel switch / fallback）+ `handleStop()` + `onDestroy()`。每处 `Transaction.remove(sc).apply(); host.release(); sc.release()` |
| C19 | FAB 路径 | 完全不动：始终 `WindowManager.addView(iv, TYPE_APPLICATION_OVERLAY)`，与 backend 选型解耦 |
| C20 | applyBypassState 在新路径下行为 | OverlayView.bypassRuntime / runtimeVisible 仍走 setter；FLAG_NOT_TOUCHABLE 等 LayoutParams 操作仅在 WindowManagerBackend 路径执行；DisplayAttachedBackend 路径下，touch 不可达靠 bypass=true 时 OverlayView.onTouchEvent 返回 false（已实现） |
| C21 | LocalBinder.setRuntimeVisible 兼容 | mainHandler.post { overlayView?.runtimeVisible = visible; overlayView?.invalidate() } — 不依赖 backend |
| C22 | tv_channel_status 第 3 状态 | `R.string.channel_status_physical_display` = "运行中 · 通道：PHYSICAL_DISPLAY (display-attached)" |
| C23 | LocalBinder 暴露接口 | 增 `fun getActiveBackend(): String` 返回 `"PHYSICAL_DISPLAY" / "ACCESSIBILITY" / "APPLICATION_OVERLAY" / "NONE"` |
| C24 | MainActivity.renderState 状态映射 | 当 isServiceRunning 时根据 binder.getActiveBackend() 选三种 string 资源 |
| C25 | OverlayBackend 接口签名 | `interface OverlayBackend { fun attach(area: BlockArea): Boolean; fun update(area: BlockArea); fun detach(); fun isAttached(): Boolean }` |
| C26 | DisplayAttachedBackend 构造时机 | onCreate 时 lazy 初始化；首次 attach 真正分配 SurfaceControl |
| C27 | DisplayAttachedBackend.attach 失败处理 | 任意异常 → log + return false → OverlayService 回退到 WindowManagerBackend.attach |
| C28 | 通知文本与 backend 关系 | `NotificationHelper.buildRunning` 不变；通知文本不区分 backend（避免增加 i18n 负担） |
| C29 | 旧路径 anti_transform 行为 | 不变 — 在 WindowManagerBackend 下 displaceDetector 仍触发 autoBypass |
| C30 | 新路径 anti_transform 行为 | DisplayAttachedBackend 下 displaceDetector 检测到漂移 → 触发 `triggerFallbackToWindowManager("displaced")` 而非 autoBypass。fallback 后回到老路径，autoBypass 行为恢复 |
| C31 | Persistence | 不持久化 backend 选择；不持久化 transient drift state |
| C32 | 测试覆盖 | OverlayBackend 接口可纯 JVM 单测；DisplayAttachedBackend 因依赖 SurfaceControl framework 类，仅做契约测试 |

## PBT Properties

| ID | Name | Definition | Boundary | Falsification |
|---|---|---|---|---|
| **P1** | Backend selection determinism | 给定 (sdkVersion, a11yServiceState)，`selectBackend()` 输出确定性枚举值；不依赖随机 / 时间 | 9 组合（sdk ∈ {26, 30, 33, 34}, a11y ∈ {bound, unbound, null}） | 用枚举驱动的 stub 测；assert 输出 = 期望表 |
| **P2** | Backend interface idempotency | 任一 backend 的 `attach(a)` 后再 `attach(a)`（同 area）→ 状态等价 | 同 area 反复 attach | 调用两次 attach，对比 isAttached + 内部资源唯一性 |
| **P3** | Detach completeness | `detach()` 后 `isAttached()` == false；外部观察者（如 SurfaceFlinger dump）查不到残留 | API 34 backend 启停 100 次 | manual: `dumpsys SurfaceFlinger \| grep OnePatchOverlay` 应空 |
| **P4** | Fallback transparency | `triggerFallbackToWindowManager(reason)` 后：runState / manualBypass / autoBypass / runtime_visible / fab_visible 不变 | 在每个状态组合下触发 fallback | snapshot before/after，assert 8 字段相等 |
| **P5** | No backend ping-pong | 单次 service 生命周期内 fallback 触发后，不再尝试 API 34 路径直至 handleStop | 反复触发 displaceDetector 报告 drift | 模拟连续 drift，assert backend.id 仅切换一次 |
| **P6** | API 34 attach failure → fallback | DisplayAttachedBackend.attach 抛异常 / 返回 false → 自动 WindowManagerBackend.attach；用户最终获得可用屏蔽 | runCatching 模拟各种异常路径 | mock SurfaceControl.Builder 抛异常；assert overlay 最终可见 |
| **P7** | API < 34 always uses legacy | sdk < 34 设备调用 selectBackend() → 必返回 WindowManagerBackend；DisplayAttachedBackend 类不被 ClassLoader 解析 | sdk = 26, 28, 30, 33 | 跑 release APK 在 API 26 模拟器；assert no ClassNotFoundException |
| **P8** | applyBypassState backend-independent | `bypassRuntime` / `runtimeVisible` setter 在两 backend 下产生等价 OverlayView 视觉效果 | manualBypass × autoBypass × runtimeVisible 8 组合 | 截屏 diff（API 30 + API 34）应像素一致（除位置外） |
| **P9** | Touch dispatch transparency | OverlayView.onTouchEvent 在 SurfaceControlViewHost 内仍 return !bypassRuntime；新路径触控阻挡行为不变 | bypass = true / false | uiautomator inject touch；assert 应用层是否收到 |
| **P10** | Resource cleanup invariant | 启停 N 次后，进程 SurfaceControl 数 ≤ 1；release 调用计数 == acquire 调用计数 | N ∈ [1, 100] | adb shell dumpsys SurfaceFlinger \| grep OnePatch \| wc -l ≤ 1 |
| **P11** | Configuration change safety | 旋转 / 字号变更 触发 onConfigurationChanged → backend 经历 detach → attach(rescaledArea)；overlay 在 1 frame 内可见 | 横竖屏切换、显示大小变更 | 截屏延迟 ≤ 16ms |
| **P12** | Drift detector hysteresis | 短暂跳动（1 frame）不触发 fallback；持续 ≥ 2 frame 漂移触发 | 模拟 [1, 5] frame 漂移序列 | 注入位置事件；assert fallback 仅在 ≥ 2 frame 时触发 |

## Implementation Plan (high-level → maps to tasks.md)

1. **Backend interface** — 定义 `OverlayBackend` 接口 + 数据模型
2. **WindowManagerBackend** — 把现有 `addOrUpdateOverlay / removeOverlayIfAttached / applyBypassState 的 LayoutParams 部分` 抽出到该实现
3. **DisplayAttachedBackend** — 新建 `@RequiresApi(34)` 类；SurfaceControl + SurfaceControlViewHost + attachAccessibilityOverlayToDisplay 流水线
4. **BlockerAccessibilityService** 新增 `attachOverlayToDisplay(sc): Boolean` 包装方法
5. **OverlayService** 改造：`selectBackend()` + `triggerFallbackToWindowManager(reason)` + 路由所有 attach/update/detach 调用
6. **drift detector 升级**：API 34 backend 下检测到位移 → 触发 fallback 而非 autoBypass
7. **LocalBinder** 增 `getActiveBackend()`；MainActivity 渲染第 3 状态
8. **strings.xml** 增 `channel_status_physical_display`
9. **编译 + 测试**：assembleDebug / assembleRelease ≤ 1.6 MB / 28 现有测试保持 green
10. **PBT 测试**：新增 BackendSelectionTest（P1, P2, P7） + FallbackTransparencyTest（P4, P5）

## Considerations

| Aspect | Note |
|---|---|
| Performance | drift 检测每帧 O(1)（getLocationOnScreen + 比较 long）；新 backend attach 一次性成本 ~10ms；用户不可见 |
| Accessibility | TalkBack 不直接交互 overlay；保持现有 contentDescription |
| Maintainability | OverlayBackend 接口 + 2 实现 + 选型/fallback 逻辑 ~300 行；OverlayService 主体减少 ~80 行 |
| Testing | 接口可纯 JVM 测；具体实现仅契约测；无需 Robolectric |
| Compatibility | 全 API 26+ 不退化；API 34+ HyperOS 真机验证后才能确认 G1 |
| Rollback | 删除 DisplayAttachedBackend 引用 + 改 selectBackend 永远返回 WindowManagerBackend = 完全 v1.0.0 行为 |
| Security | 不新增权限；SurfaceControl 操作仅在 a11y service 内；release APK ProGuard rules 不需修改 |
| Privacy | 不新增数据采集 |
