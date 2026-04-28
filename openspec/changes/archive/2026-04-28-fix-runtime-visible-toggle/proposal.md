# Change Proposal: fix-runtime-visible-toggle

**Status**: Active
**Created**: 2026-04-28
**Related Spec**: bypass-state-machine (extension)

## Why

`MainActivity.switchRuntimeVisible` 切换「运行时显示屏蔽区域提示色」开关时，仅写入 `SharedPreferences("settings_prefs", "runtime_visible")`，但**已挂载的 `OverlayView.runtimeVisible` 字段不被更新**。`OverlayView.runtimeVisible` 仅在 `OverlayService.addOrUpdateOverlay()` 的 fresh-attach 分支调用 `runtimeVisiblePref()` 读一次。结果：用户在屏蔽运行中关掉开关，提示色仍然可见，必须停止再启用屏蔽才能生效。

这与同文件中的 `switchFab` 表现不一致（FAB 开关在运行中能即时显隐），违反单一 writer 模式（pref 是 SoT，命令是 push 通道）。

## What Changes

### Hard guarantee

- **G1 (Runtime-Visible-Live-Sync)**: 当用户在屏蔽运行中切换 `switchRuntimeVisible` 开关时，已挂载的 `OverlayView` 必须在下一帧反映新的可见状态，无需重启 service。

### Architectural alignment

- **A1 (Declarative-Set-Symmetry)**: 引入 `ACTION_RUNTIME_VISIBLE_SET` + 复用 `EXTRA_VISIBLE`，与 `ACTION_FAB_SET_VISIBLE` 对齐；MainActivity 单一 writer 写 pref 后立即推 intent。

### Scope

**In scope**:
- `app/src/main/java/com/ccg/screenblocker/service/OverlayService.kt`（新增 ACTION + handler）
- `app/src/main/java/com/ccg/screenblocker/MainActivity.kt`（switch 监听器 push intent）

**Out of scope**:
- 不改 `OverlayView.runtimeVisible` setter（其 `invalidate()` 行为已正确）
- 不改 SharedPreferences key / default 值（保持兼容）
- 不引入新依赖
- 不改 manifest / 资源

## Impact

| Surface | Impact |
|---|---|
| End user | 切换开关即时生效，行为与 FAB 开关一致 |
| Privacy / permissions | 无 |
| App size | 0（仅常量与方法增加） |
| Build / packaging | 无 |
| Existing users (upgrade) | pref 值复用；首次升级后行为按 pref 持久值即时生效 |
| Risks | 无 — handler 在 view 不存在时 no-op；pref 仍是 SoT |

## Non-Goals

- 不改运行态颜色 / alpha 值
- 不为「闪烁动画」加独立开关
- 不暴露第三方 broadcast 接口
