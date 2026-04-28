package com.ccg.screenblocker

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.ccg.screenblocker.service.OverlayService

/**
 * 透明无界面 Activity（trampoline）。启动后立即派发 ACTION_TOGGLE_BYPASS_AND_GESTURE，
 * 由 OverlayService 在原子事务中完成「翻转 manualBypass + 触发单手手势」并 finish()。
 *
 * 用户可在系统设置中将「敲击背部 (Quick Tap)」/「悬浮球」/「桌面快捷方式」绑定到
 * QuickToggleAlias（标准 LAUNCHER 别名）。
 */
class QuickToggleActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            startService(
                Intent(this, OverlayService::class.java)
                    .setAction(OverlayService.ACTION_TOGGLE_BYPASS_AND_GESTURE)
                    .putExtra(OverlayService.EXTRA_SOURCE, "quick_toggle")
            )
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
