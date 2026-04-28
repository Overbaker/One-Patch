package com.ccg.screenblocker

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.ccg.screenblocker.data.SharedPrefsBlockAreaRepository
import com.ccg.screenblocker.service.OverlayService
import com.ccg.screenblocker.ui.OverlayView
import com.ccg.screenblocker.util.DisplayHelper
import com.google.android.material.button.MaterialButton

/**
 * 全屏编辑器：直接在真实屏幕上拖拽屏蔽矩形，所见即所得。
 * - 透明背景 + 沉浸式全屏（隐藏状态栏/导航栏）
 * - OverlayView 在全屏模式下直接覆盖屏幕
 * - 提供「确认启用」「取消」按钮
 */
class FullscreenEditorActivity : AppCompatActivity() {

    private val repository by lazy { SharedPrefsBlockAreaRepository(this) }
    private lateinit var overlayView: OverlayView
    private lateinit var coordinatesLabel: TextView

    private var displayWidthPx = 0
    private var displayHeightPx = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupFullscreenWindow()

        val (w, h) = DisplayHelper.getRealDisplaySizePx(this)
        displayWidthPx = w
        displayHeightPx = h

        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#22000000"))
        }

        // 全屏 OverlayView：letterbox = 整个 View，矩形坐标 = 物理坐标
        overlayView = OverlayView(this).apply {
            mode = OverlayView.Mode.EDIT
            setScreenSize(displayWidthPx, displayHeightPx)
            fullscreenMode = true   // 关键：跳过 letterbox 缩放，1:1 物理坐标
        }
        root.addView(
            overlayView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // 顶部坐标显示
        coordinatesLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#99000000"))
            setPadding(dp(20), dp(12), dp(20), dp(12))
            textSize = 14f
            typeface = android.graphics.Typeface.MONOSPACE
            text = getString(R.string.fullscreen_hint)
        }
        root.addView(
            coordinatesLabel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(48) }
        )

        // 底部按钮栏
        val buttonBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#CC000000"))
            setPadding(dp(20), dp(16), dp(20), dp(20))
            gravity = android.view.Gravity.CENTER
        }
        val cancelBtn = MaterialButton(this).apply {
            text = getString(R.string.action_cancel)
            setOnClickListener { finish() }
        }
        val confirmBtn = MaterialButton(this).apply {
            text = getString(R.string.action_confirm_enable)
            setOnClickListener { confirmAndEnable() }
        }
        buttonBar.addView(cancelBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
        buttonBar.addView(confirmBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
        root.addView(
            buttonBar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM
            )
        )

        setContentView(root)

        // 加载历史矩形 + 实时更新坐标
        overlayView.post {
            val saved = repository.loadOrDefault(displayWidthPx, displayHeightPx)
            overlayView.applyFromBlockArea(saved, displayWidthPx, displayHeightPx)
            updateCoordinates()
        }
        overlayView.onAreaChangedListener = { _, isFinal ->
            updateCoordinates()
            if (isFinal) {
                val area = overlayView.toBlockArea(displayWidthPx, displayHeightPx)
                repository.save(area)
            }
        }
    }

    private fun setupFullscreenWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        // 沉浸式：隐藏状态栏 + 导航栏
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
    }

    private fun updateCoordinates() {
        val area = overlayView.toBlockArea(displayWidthPx, displayHeightPx)
        coordinatesLabel.text = getString(
            R.string.fullscreen_coordinates_format,
            area.leftPx, area.topPx, area.widthPx, area.heightPx
        )
    }

    private fun confirmAndEnable() {
        val area = overlayView.toBlockArea(displayWidthPx, displayHeightPx)
        repository.save(area)

        Toast.makeText(this, R.string.toast_starting, Toast.LENGTH_SHORT).show()
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_ENABLE
        }
        androidx.core.content.ContextCompat.startForegroundService(this, intent)
        setResult(RESULT_OK)
        finish()
    }

    private fun dp(v: Int): Int = DisplayHelper.dp(this, v.toFloat())
}
