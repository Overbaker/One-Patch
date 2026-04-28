package com.ccg.screenblocker.service

import com.ccg.screenblocker.model.BlockArea

/**
 * Overlay attach backend abstraction.
 *
 * 实现：
 * - [WindowManagerBackend] — 经典 WindowManager.addView 路径（API 26+，支持 trusted overlay）
 * - [DisplayAttachedBackend] — Android 14+ attachAccessibilityOverlayToDisplay 路径
 *
 * OverlayService 持有 activeBackend 引用，所有 attach/update/detach 路由到该 backend。
 * 状态机（RunState、manualBypass、autoBypass）保持在 OverlayService 内，与 backend 选型解耦。
 */
interface OverlayBackend {

    /** 首次挂载 overlay 到指定区域；成功返回 true，失败 caller 应 fallback 到下一个 backend */
    fun attach(area: BlockArea): Boolean

    /** 已挂载状态下更新位置/尺寸（如 onConfigurationChanged） */
    fun update(area: BlockArea)

    /** 从当前 backend 卸载并释放所有相关资源（SurfaceControl / View 引用等） */
    fun detach()

    fun isAttached(): Boolean

    /** 用于 status UI 渲染：PHYSICAL_DISPLAY / ACCESSIBILITY / APPLICATION_OVERLAY */
    fun id(): String

    companion object {
        const val ID_PHYSICAL_DISPLAY = "PHYSICAL_DISPLAY"
        const val ID_ACCESSIBILITY = "ACCESSIBILITY"
        const val ID_APPLICATION_OVERLAY = "APPLICATION_OVERLAY"
        const val ID_NONE = "NONE"
    }
}
