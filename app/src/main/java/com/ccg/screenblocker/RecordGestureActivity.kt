package com.ccg.screenblocker

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.ccg.screenblocker.data.GestureRepository
import com.ccg.screenblocker.model.Point
import com.ccg.screenblocker.model.RecordedGesture
import com.ccg.screenblocker.model.Stroke
import com.ccg.screenblocker.ui.GestureTrailView
import com.ccg.screenblocker.util.DisplayHelper
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar

/**
 * 全屏录制单手模式触发手势：
 *
 * - 倒计时 3-2-1 → RECORDING → 用户主动点 完成
 * - 触控由 trail_view.setOnTouchListener 处理：按钮在 z-order 上层，天然消费按钮区 touch
 * - trail 渲染用 view-local 坐标（无 statusbar inset 偏移）；录制坐标用 view→screen 偏移转为绝对像素
 * - 录制完成后 normalizeTimestamps() 保证 stroke[0].first.tMs == 0（消除首点前的等待延时）
 */
class RecordGestureActivity : AppCompatActivity() {

    private enum class Phase { COUNTDOWN, RECORDING, ABORTED }

    private val gestureRepository by lazy { GestureRepository(this) }

    private lateinit var trailView: GestureTrailView
    private lateinit var countdownPill: TextView
    private lateinit var instructionText: TextView
    private lateinit var btnRetry: MaterialButton
    private lateinit var btnSave: MaterialButton
    private lateinit var btnCancel: ImageButton
    private lateinit var rootView: View

    private val mainHandler = Handler(Looper.getMainLooper())
    private val countdownRunnable = object : Runnable {
        override fun run() {
            val remaining = countdownRemaining
            if (remaining > 0) {
                countdownPill.text = remaining.toString()
                rootView.announceForAccessibility(remaining.toString())
                countdownRemaining = remaining - 1
                mainHandler.postDelayed(this, COUNTDOWN_STEP_MS)
            } else {
                enterRecording()
            }
        }
    }
    private var countdownRemaining: Int = COUNTDOWN_FROM

    @Volatile
    private var phase: Phase = Phase.COUNTDOWN

    private val completedStrokes = mutableListOf<Stroke>()
    private var activePoints: MutableList<Point> = mutableListOf()
    private var activeXY: MutableList<GestureTrailView.PointXY> = mutableListOf()
    private var gestureStartUptimeMs: Long = 0L

    /** trail_view 在屏幕中的左上角偏移（view-local → screen-absolute 转换用） */
    private val trailLocOnScreen = IntArray(2)

    private var displayWidthPx = 0
    private var displayHeightPx = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupFullscreenWindow()
        setContentView(R.layout.activity_record_gesture)

        rootView = findViewById(R.id.record_root)
        trailView = findViewById(R.id.trail_view)
        countdownPill = findViewById(R.id.tv_countdown_pill)
        instructionText = findViewById(R.id.tv_instruction)
        btnRetry = findViewById(R.id.btn_retry)
        btnSave = findViewById(R.id.btn_save)
        btnCancel = findViewById(R.id.btn_cancel)

        val (w, h) = DisplayHelper.getRealDisplaySizePx(this)
        displayWidthPx = w
        displayHeightPx = h

        title = getString(R.string.record_gesture_title)
        rootView.announceForAccessibility(getString(R.string.a11y_record_announce))

        btnRetry.setOnClickListener { restart() }
        btnSave.setOnClickListener { saveAndFinish() }
        btnCancel.setOnClickListener { cancelAndFinish() }

        trailView.setOnTouchListener { _, event -> handleTouch(event) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = cancelAndFinish()
        })

        startCountdown()
    }

    override fun onResume() {
        super.onResume()
        applyImmersive()
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(countdownRunnable)
        if (!isFinishing) {
            resetSession(toPhase = Phase.ABORTED)
        }
    }

    /** trail_view onTouch：按钮在 z-order 上层 → 按钮区 touch 不会进入此处 */
    private fun handleTouch(event: MotionEvent): Boolean {
        if (phase != Phase.RECORDING) return false
        if (!event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                snackbar(R.string.toast_keyboard_rejected)
            }
            return true
        }
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { handleDown(event); true }
            MotionEvent.ACTION_MOVE -> { handleMove(event); true }
            MotionEvent.ACTION_UP -> { handleUp(event); true }
            MotionEvent.ACTION_POINTER_DOWN -> { abortMultiTouch(); true }
            MotionEvent.ACTION_CANCEL -> { resetActiveStroke(); true }
            else -> true
        }
    }

    private fun setupFullscreenWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyImmersive()
    }

    private fun applyImmersive() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun startCountdown() {
        phase = Phase.COUNTDOWN
        completedStrokes.clear()
        resetActiveStroke()
        gestureStartUptimeMs = 0L
        instructionText.setText(R.string.record_gesture_instruction)
        countdownPill.visibility = View.VISIBLE
        btnRetry.isEnabled = false
        btnSave.isEnabled = false
        countdownRemaining = COUNTDOWN_FROM
        mainHandler.removeCallbacks(countdownRunnable)
        mainHandler.post(countdownRunnable)
    }

    private fun enterRecording() {
        phase = Phase.RECORDING
        countdownPill.text = getString(R.string.record_gesture_recording)
        rootView.announceForAccessibility(getString(R.string.record_gesture_recording))
        btnRetry.isEnabled = true
        btnSave.isEnabled = false
        // 缓存 trail_view 在屏幕中的位置；用于 view-local → screen-absolute 的存储坐标转换
        trailView.getLocationOnScreen(trailLocOnScreen)
    }

    private fun handleDown(event: MotionEvent) {
        if (gestureStartUptimeMs == 0L) {
            gestureStartUptimeMs = event.eventTime
        }
        val tMs = event.eventTime - gestureStartUptimeMs
        // 录制期间统一 view-local 坐标，渲染与触控对齐；saveAndFinish 时再转 screen-absolute
        activePoints = mutableListOf(Point(event.x, event.y, tMs))
        activeXY = mutableListOf(GestureTrailView.PointXY(event.x, event.y))
        trailView.setStrokes(completedStrokes, activeXY)
    }

    private fun handleMove(event: MotionEvent) {
        if (activePoints.isEmpty()) return
        val tNow = event.eventTime - gestureStartUptimeMs
        val tail = activePoints.last()
        val tEnsured = if (tNow > tail.tMs) tNow else tail.tMs + 1
        activePoints.add(Point(event.x, event.y, tEnsured))
        activeXY.add(GestureTrailView.PointXY(event.x, event.y))
        trailView.setStrokes(completedStrokes, activeXY)
    }

    private fun handleUp(event: MotionEvent) {
        if (activePoints.isEmpty()) return
        val tNow = event.eventTime - gestureStartUptimeMs
        val tail = activePoints.last()
        val tEnsured = if (tNow > tail.tMs) tNow else tail.tMs + 1
        activePoints.add(Point(event.x, event.y, tEnsured))
        activeXY.add(GestureTrailView.PointXY(event.x, event.y))
        if (activePoints.size >= 2) {
            completedStrokes.add(Stroke(activePoints.toList()))
            btnSave.isEnabled = true
        }
        resetActiveStroke()
        trailView.setStrokes(completedStrokes, activeXY)
        // 保持 RECORDING — 用户可继续 lift-and-repress 或主动点完成
    }

    private fun abortMultiTouch() {
        resetSession(toPhase = Phase.COUNTDOWN)
        snackbar(R.string.toast_multitouch_rejected)
        restart()
    }

    private fun resetActiveStroke() {
        activePoints = mutableListOf()
        activeXY = mutableListOf()
    }

    private fun resetSession(toPhase: Phase) {
        phase = toPhase
        completedStrokes.clear()
        resetActiveStroke()
        gestureStartUptimeMs = 0L
        trailView.reset()
        btnSave.isEnabled = false
    }

    private fun restart() {
        startCountdown()
    }

    /**
     * 保险：把 stroke[0].first.tMs 平移为 0，消除录制 phase 切换到第一次 ACTION_DOWN 之间
     * 任何可能的隐含延时；多 stroke 间相对 gap 保持不变。
     */
    private fun normalizeTimestamps(strokes: List<Stroke>): List<Stroke> {
        if (strokes.isEmpty()) return strokes
        val offset = strokes[0].points[0].tMs
        if (offset == 0L) return strokes
        return strokes.map { s ->
            Stroke(s.points.map { p -> Point(p.xPx, p.yPx, p.tMs - offset) })
        }
    }

    private fun saveAndFinish() {
        if (completedStrokes.isEmpty()) {
            snackbar(R.string.toast_gesture_too_short)
            return
        }
        // view-local → screen-absolute（dispatchGesture 使用屏幕物理坐标）
        val offX = trailLocOnScreen[0].toFloat()
        val offY = trailLocOnScreen[1].toFloat()
        val absoluteStrokes = completedStrokes.map { stroke ->
            Stroke(stroke.points.map { p -> Point(p.xPx + offX, p.yPx + offY, p.tMs) })
        }
        val normalized = normalizeTimestamps(absoluteStrokes)
        val gesture = runCatching {
            RecordedGesture(displayWidthPx, displayHeightPx, normalized)
        }.getOrElse {
            snackbar(R.string.toast_gesture_too_short)
            return
        }
        try {
            gestureRepository.save(gesture)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "save rejected", e)
            val msg = if (e.message?.contains("duration") == true ||
                e.message?.contains("strokes") == true
            ) R.string.toast_gesture_too_long
            else R.string.toast_gesture_too_short
            snackbar(msg)
            return
        }
        setResult(RESULT_OK)
        finish()
    }

    private fun cancelAndFinish() {
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun snackbar(resId: Int) {
        Snackbar.make(rootView, resId, Snackbar.LENGTH_LONG).show()
    }

    companion object {
        private const val TAG = "RecordGestureActivity"
        private const val COUNTDOWN_FROM = 3
        private const val COUNTDOWN_STEP_MS = 1000L
    }
}
