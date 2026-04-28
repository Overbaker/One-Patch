package com.ccg.screenblocker

import android.app.Application
import com.google.android.material.color.DynamicColors

/**
 * 应用入口，启用 Material You 动态色（Android 12+）。
 * 仅支持 Android 12+ 设备，低版本自动回退到 colors.xml 中的静态色。
 */
class TouchBlockerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
