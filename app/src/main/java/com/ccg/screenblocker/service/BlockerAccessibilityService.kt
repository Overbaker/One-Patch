package com.ccg.screenblocker.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.SurfaceControl
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import com.ccg.screenblocker.data.GestureRepository
import com.ccg.screenblocker.util.DisplayHelper

/**
 * 极简无障碍服务：
 *
 * 唯一作用 = 获得 [TYPE_ACCESSIBILITY_OVERLAY] 创建权限，让屏蔽 overlay 成为 **trusted window**，
 * 不被 SurfaceFlinger 的 display-area transform（如小米单手模式）一起平移。
 *
 * 不监听任何 AccessibilityEvent，不读取窗口内容。
 */
class BlockerAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "BlockerA11yService"

        @Volatile
        private var instance: BlockerAccessibilityService? = null

        fun get(): BlockerAccessibilityService? = instance
    }

    private val gestureRepository by lazy { GestureRepository(this) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "accessibility service connected")
        sendBroadcast(Intent(OverlayService.ACTION_A11Y_AVAILABLE).setPackage(packageName))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* not used */ }

    override fun onInterrupt() { /* no-op */ }

    /**
     * 触发单手模式手势：优先回放用户录制；缺失则 fallback 到 4px/80ms 下滑。
     *
     * 失败处理：dispatchGesture 返回 false 不再二次自动 fallback（防止双重注入）。
     */
    fun triggerOneHandedGesture(): Boolean {
        val (w, h) = DisplayHelper.getRealDisplaySizePx(this)
        val recorded = gestureRepository.load()
        val gesture = recorded?.let { GestureReplayBuilder.build(it, w, h) }
            ?: buildFallbackGesture(w, h)
        val ok = dispatchGesture(gesture, null, null)
        Log.i(TAG, "triggerOneHandedGesture recorded=${recorded != null} dispatched=$ok")
        return ok
    }

    private fun buildFallbackGesture(w: Int, h: Int): GestureDescription {
        val path = Path().apply {
            moveTo(w / 2f, h - 4f)
            lineTo(w / 2f, h - 1f)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 80L)
        return GestureDescription.Builder().addStroke(stroke).build()
    }

    /**
     * API 34+ 把 SurfaceControl-backed overlay 绑到物理 display。
     * 失败（异常 / 老系统调用）→ 返回 false 让调用方 fallback 到 WindowManager 路径。
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun attachOverlayToDisplay(sc: SurfaceControl): Boolean = runCatching {
        attachAccessibilityOverlayToDisplay(Display.DEFAULT_DISPLAY, sc)
        true
    }.getOrElse {
        Log.e(TAG, "attachOverlayToDisplay failed", it)
        false
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        // 通知 OverlayService a11y 离线 → 当前 backend == DisplayAttachedBackend 时触发 fallback
        sendBroadcast(Intent(OverlayService.ACTION_A11Y_UNBOUND).setPackage(packageName))
        Log.i(TAG, "accessibility service unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }
}
