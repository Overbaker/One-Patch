package com.ccg.screenblocker.service

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import com.ccg.screenblocker.model.BlockArea
import com.ccg.screenblocker.ui.OverlayView

/**
 * 经典 WindowManager.addView 路径：
 * - 优先使用 BlockerAccessibilityService 的 WindowManager + TYPE_ACCESSIBILITY_OVERLAY（trusted window）
 * - 无 a11y 时 fallback 到 application context 的 WindowManager + TYPE_APPLICATION_OVERLAY
 *
 * 触控 / FLAG_NOT_TOUCHABLE / runtime_visible 行为保持原 v1 语义不变。
 */
class WindowManagerBackend(
    private val appContext: Context,
    private val overlayView: OverlayView,
    private val appWindowManager: WindowManager
) : OverlayBackend {

    private var attached: Boolean = false
    private var attachedViaA11y: Boolean = false

    override fun attach(area: BlockArea): Boolean {
        val a11y = BlockerAccessibilityService.get()
        val useA11y = a11y != null
        val wm: WindowManager = if (useA11y) {
            a11y!!.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        } else {
            appWindowManager
        }
        val params = buildParams(area, useA11y)
        return runCatching {
            wm.addView(overlayView, params)
            attached = true
            attachedViaA11y = useA11y
            Log.i(TAG, "attached via=${if (useA11y) "ACCESSIBILITY" else "APPLICATION"} (${area.leftPx},${area.topPx}) ${area.widthPx}x${area.heightPx}")
            true
        }.getOrElse {
            Log.e(TAG, "WindowManagerBackend.attach failed", it)
            attached = false
            false
        }
    }

    override fun update(area: BlockArea) {
        if (!attached) return
        val wm = currentWm() ?: return
        runCatching {
            wm.updateViewLayout(overlayView, buildParams(area, attachedViaA11y))
        }.onFailure { Log.w(TAG, "updateViewLayout failed", it) }
    }

    override fun detach() {
        if (!attached) return
        val wm = currentWm() ?: appWindowManager
        runCatching { wm.removeViewImmediate(overlayView) }
            .onFailure { Log.w(TAG, "removeView failed (a11y=$attachedViaA11y)", it) }
        attached = false
        attachedViaA11y = false
    }

    override fun isAttached(): Boolean = attached

    override fun id(): String =
        if (attachedViaA11y) OverlayBackend.ID_ACCESSIBILITY
        else OverlayBackend.ID_APPLICATION_OVERLAY

    /** 暴露给 OverlayService.applyBypassState 使用：基于 attachedViaA11y 选 WM 实例 */
    fun currentWm(): WindowManager? {
        val a11y = BlockerAccessibilityService.get()
        return if (attachedViaA11y && a11y != null) {
            a11y.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        } else appWindowManager
    }

    fun isAttachedViaA11y(): Boolean = attachedViaA11y

    private fun buildParams(area: BlockArea, useA11y: Boolean): WindowManager.LayoutParams {
        val type = if (useA11y) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }
        return WindowManager.LayoutParams(
            area.widthPx,
            area.heightPx,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = area.leftPx
            y = area.topPx
            title = "OnePatchOverlay"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    companion object {
        private const val TAG = "WindowManagerBackend"
    }
}
