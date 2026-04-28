package com.ccg.screenblocker.service

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.SurfaceControl
import android.view.SurfaceControlViewHost
import androidx.annotation.RequiresApi
import com.ccg.screenblocker.model.BlockArea
import com.ccg.screenblocker.ui.OverlayView

/**
 * Android 14+ AccessibilityService.attachAccessibilityOverlayToDisplay 路径：
 *
 * - SurfaceControl 作为物理 display layer 直接挂载
 * - SurfaceControlViewHost 嵌入 OverlayView，触控自动透传
 * - HyperOS 单手模式 transform 不影响该 layer（AOSP 设计如此；HyperOS 真机需验证）
 *
 * 任何 attach 异常都返回 false 让 OverlayService 自动 fallback 到 WindowManagerBackend。
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class DisplayAttachedBackend(
    private val context: Context,
    private val overlayView: OverlayView
) : OverlayBackend {

    private var sc: SurfaceControl? = null
    private var host: SurfaceControlViewHost? = null
    private var attached: Boolean = false
    private var currentWidth: Int = 0
    private var currentHeight: Int = 0

    override fun attach(area: BlockArea): Boolean {
        val a11y = BlockerAccessibilityService.get() ?: run {
            Log.w(TAG, "attach skipped: a11y service not bound")
            return false
        }
        return runCatching {
            val newSc = SurfaceControl.Builder()
                .setName("OnePatchOverlay")
                .setBufferSize(area.widthPx, area.heightPx)
                .setFormat(PixelFormat.TRANSLUCENT)
                .setOpaque(false)
                .build()
            val newHost = SurfaceControlViewHost(context, context.display ?: defaultDisplay(), null)
            newHost.setView(overlayView, area.widthPx, area.heightPx)
            val ok = a11y.attachOverlayToDisplay(newSc)
            if (!ok) {
                newHost.release()
                releaseSurface(newSc)
                throw IllegalStateException("attachAccessibilityOverlayToDisplay returned false")
            }
            SurfaceControl.Transaction()
                .setPosition(newSc, area.leftPx.toFloat(), area.topPx.toFloat())
                .setVisibility(newSc, true)
                .apply()
            sc = newSc
            host = newHost
            currentWidth = area.widthPx
            currentHeight = area.heightPx
            attached = true
            Log.i(TAG, "attached display-bound at (${area.leftPx},${area.topPx}) ${area.widthPx}x${area.heightPx}")
            true
        }.getOrElse {
            Log.e(TAG, "DisplayAttachedBackend.attach failed", it)
            cleanup()
            false
        }
    }

    override fun update(area: BlockArea) {
        val s = sc ?: return
        val h = host ?: return
        runCatching {
            SurfaceControl.Transaction()
                .setPosition(s, area.leftPx.toFloat(), area.topPx.toFloat())
                .apply()
            if (area.widthPx != currentWidth || area.heightPx != currentHeight) {
                h.relayout(area.widthPx, area.heightPx)
                currentWidth = area.widthPx
                currentHeight = area.heightPx
            }
        }.onFailure { Log.w(TAG, "update failed", it) }
    }

    override fun detach() {
        cleanup()
    }

    override fun isAttached(): Boolean = attached

    override fun id(): String = OverlayBackend.ID_PHYSICAL_DISPLAY

    private fun cleanup() {
        host?.let {
            runCatching { it.release() }
        }
        host = null
        sc?.let { releaseSurface(it) }
        sc = null
        attached = false
        currentWidth = 0
        currentHeight = 0
    }

    private fun releaseSurface(surface: SurfaceControl) {
        // SurfaceControl 不在 view 树时，setVisibility(false) + release() 即可让 SurfaceFlinger 回收
        runCatching {
            SurfaceControl.Transaction().setVisibility(surface, false).apply()
            surface.release()
        }
    }

    private fun defaultDisplay(): Display {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        return dm.getDisplay(Display.DEFAULT_DISPLAY)
    }

    companion object {
        private const val TAG = "DisplayAttachedBackend"
    }
}
