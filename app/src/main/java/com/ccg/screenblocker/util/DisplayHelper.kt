package com.ccg.screenblocker.util

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.WindowManager

/**
 * 屏幕尺寸与单位换算工具。
 */
object DisplayHelper {

    /**
     * 获取屏幕真实物理尺寸（含状态栏和导航栏）。
     * - API 30+ 使用 WindowManager.currentWindowMetrics
     * - API 26-29 fallback 到 Display.getRealMetrics
     */
    @Suppress("DEPRECATION")
    fun getRealDisplaySizePx(context: Context): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = wm.currentWindowMetrics
            metrics.bounds.width() to metrics.bounds.height()
        } else {
            val display = wm.defaultDisplay
            val dm = DisplayMetrics()
            display.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }

    /** dp → px */
    fun dp(context: Context, dp: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics
        ).toInt()

    /** dp → px (Float 精度) */
    fun dpF(context: Context, dp: Float): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics
        )
}
