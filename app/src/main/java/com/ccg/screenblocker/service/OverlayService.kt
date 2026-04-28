package com.ccg.screenblocker.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.TransitionDrawable
import android.os.Build
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ccg.screenblocker.data.SharedPrefsBlockAreaRepository
import com.ccg.screenblocker.model.BlockArea
import com.ccg.screenblocker.ui.OverlayView
import com.ccg.screenblocker.util.DisplayHelper
import com.ccg.screenblocker.util.NotificationHelper

/**
 * 屏蔽运行服务：
 *
 * - Started Service：用 startForegroundService 接收命令
 * - Bound Service：MainActivity 通过 LocalBinder 同步状态
 * - 单一状态机 RunState；bypass 状态拆为 manualBypass / autoBypass，effectiveBypass = OR
 * - 所有 bypass 视图状态变更必须经由 applyBypassState() 单一入口
 */
class OverlayService : Service() {

    enum class RunState { STOPPED, ARMING, RUNNING }

    companion object {
        private const val TAG = "OverlayService"

        const val ACTION_ENABLE = "com.ccg.screenblocker.action.ENABLE"
        const val ACTION_STOP = "com.ccg.screenblocker.action.STOP"
        const val ACTION_A11Y_AVAILABLE = "com.ccg.screenblocker.action.A11Y_AVAILABLE"

        /** Quick Toggle 入口（敲击背部）：原子完成「翻转 manualBypass + 触发单手手势」 */
        const val ACTION_TOGGLE_BYPASS_AND_GESTURE =
            "com.ccg.screenblocker.action.TOGGLE_BYPASS_AND_GESTURE"
        /** FAB 单击：仅切换 manualBypass，不触发单手手势 */
        const val ACTION_TOGGLE_BYPASS_ONLY =
            "com.ccg.screenblocker.action.TOGGLE_BYPASS_ONLY"
        /** FAB 双击：强制 manualBypass=true + 触发已录制手势 */
        const val ACTION_FORCE_BYPASS_AND_GESTURE =
            "com.ccg.screenblocker.action.FORCE_BYPASS_AND_GESTURE"
        const val EXTRA_SOURCE = "com.ccg.screenblocker.extra.SOURCE"

        /** 声明式 FAB 显隐命令（替代旧的 toggle 语义）：必须携带 EXTRA_VISIBLE */
        const val ACTION_FAB_SET_VISIBLE = "com.ccg.screenblocker.action.FAB_SET_VISIBLE"

        /** 声明式：运行态屏蔽层提示色显隐。必须携带 EXTRA_VISIBLE */
        const val ACTION_RUNTIME_VISIBLE_SET =
            "com.ccg.screenblocker.action.RUNTIME_VISIBLE_SET"

        const val EXTRA_VISIBLE = "com.ccg.screenblocker.extra.VISIBLE"

        const val ENABLE_DELAY_MS = 500L
        const val FAB_DEBOUNCE_MS = 300L
        const val FAB_ICON_FADE_MS = 200
    }

    interface StateListener {
        fun onServiceStateChanged(running: Boolean)
    }

    inner class LocalBinder : Binder() {
        fun getService(): OverlayService = this@OverlayService
        fun isRunning(): Boolean = runState != RunState.STOPPED
        fun isAttachedViaA11y(): Boolean = attachedViaA11y
        fun isManualBypass(): Boolean = manualBypass
        fun isAutoBypass(): Boolean = autoBypass
        fun setStateListener(listener: StateListener?) {
            stateListener = listener
        }

        /** 同步直调：MainActivity 切换 switch_runtime_visible 时强制刷新已挂载 view */
        fun setRuntimeVisible(visible: Boolean) {
            mainHandler.post {
                val v = overlayView ?: return@post
                v.runtimeVisible = visible
                v.invalidate()
            }
        }
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingEnableRunnable: Runnable? = null

    private var windowManager: WindowManager? = null
    private var overlayView: OverlayView? = null
    private var currentArea: BlockArea? = null

    @Volatile
    private var runState: RunState = RunState.STOPPED

    @Volatile
    private var manualBypass: Boolean = false

    @Volatile
    private var autoBypass: Boolean = false

    private val effectiveBypass: Boolean get() = manualBypass || autoBypass

    private var stateListener: StateListener? = null

    private val repository by lazy { SharedPrefsBlockAreaRepository(this) }

    /** 监听 AccessibilityService 上线，用于在用户授权后自动重挂 trusted overlay */
    private val a11yReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action == ACTION_A11Y_AVAILABLE && runState != RunState.STOPPED) {
                Log.i(TAG, "a11y available, re-attaching as trusted overlay")
                currentArea?.let { area ->
                    removeOverlayIfAttached()
                    runCatching { addOrUpdateOverlay(area) }
                        .onFailure { Log.e(TAG, "re-attach failed", it) }
                }
            }
        }
    }

    /** 当前 overlay 是否使用 trusted (AccessibilityService) 通道 */
    private var attachedViaA11y: Boolean = false

    /** 期望的物理屏幕位置（启用屏蔽时记录） */
    private var expectedX: Int = 0
    private var expectedY: Int = 0

    /** 悬浮快捷按钮 view + 是否已挂载 */
    private var fabView: android.widget.ImageView? = null
    private var fabAttached: Boolean = false
    private var fabCurrentDrawable: android.graphics.drawable.Drawable? = null

    /** 位移检测监听器：发现 view 被系统 transform 下移 → 让屏蔽自动让步 */
    private val locBuf = IntArray(2)
    private val displaceDetector = android.view.ViewTreeObserver.OnPreDrawListener {
        detectDisplacementAndBypass()
        true
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        NotificationHelper.ensureChannel(this)
        val filter = android.content.IntentFilter(ACTION_A11Y_AVAILABLE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(a11yReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(a11yReceiver, filter)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ENABLE -> handleEnable()
            ACTION_STOP -> handleStop()
            ACTION_TOGGLE_BYPASS_AND_GESTURE -> handleToggleBypassAndGesture(intent)
            ACTION_TOGGLE_BYPASS_ONLY -> handleToggleBypassOnly(intent)
            ACTION_FORCE_BYPASS_AND_GESTURE -> handleForceBypassAndGesture(intent)
            ACTION_FAB_SET_VISIBLE -> handleFabSetVisible(intent)
            ACTION_RUNTIME_VISIBLE_SET -> handleRuntimeVisibleSet(intent)
            else -> {
                if (runState == RunState.STOPPED) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun handleEnable() {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            Log.e(TAG, "SYSTEM_ALERT_WINDOW permission not granted, cannot enable")
            android.widget.Toast.makeText(
                this,
                getString(com.ccg.screenblocker.R.string.toast_overlay_perm_required),
                android.widget.Toast.LENGTH_LONG
            ).show()
            stopSelf()
            return
        }

        val (dw, dh) = DisplayHelper.getRealDisplaySizePx(this)
        val area = repository.loadOrDefault(dw, dh)
            .rescaleIfNeeded(dw, dh)
            .clamp(
                displayWidthPx = dw,
                displayHeightPx = dh,
                marginPx = 0,
                minSizePx = DisplayHelper.dp(this, 72f)
            )
        currentArea = area
        repository.save(area)
        Log.i(TAG, "handleEnable area=$area display=${dw}x${dh}")
        startForegroundCompat(area)
        runState = RunState.ARMING
        notifyStateChanged()

        pendingEnableRunnable?.let(mainHandler::removeCallbacks)
        pendingEnableRunnable = Runnable {
            try {
                addOrUpdateOverlay(area)
            } catch (e: Exception) {
                Log.e(TAG, "addOverlay failed", e)
                handleStop()
            }
        }
        mainHandler.postDelayed(pendingEnableRunnable!!, ENABLE_DELAY_MS)
    }

    private fun handleStop() {
        // FAB 必须先于 overlay 拆除，避免主线程残留 view
        detachFabIfAttached()
        pendingEnableRunnable?.let(mainHandler::removeCallbacks)
        pendingEnableRunnable = null

        removeOverlayIfAttached()
        runState = RunState.STOPPED
        manualBypass = false
        autoBypass = false
        notifyStateChanged()

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * FAB 单击：仅切换 manualBypass，不触发单手手势。
     */
    private fun handleToggleBypassOnly(intent: Intent) {
        if (!ensureRunningWithOverlay("toggle_only")) return
        manualBypass = !manualBypass
        Log.i(TAG, "FAB single-tap → manualBypass=$manualBypass")
        applyBypassState()
    }

    /**
     * FAB 双击：强制 manualBypass=true + 触发录制手势（无论当前状态）。
     */
    private fun handleForceBypassAndGesture(intent: Intent) {
        if (!ensureRunningWithOverlay("force_bypass")) return
        if (!manualBypass) {
            manualBypass = true
            applyBypassState()
        }
        Log.i(TAG, "FAB double-tap → force bypass + dispatch gesture")
        BlockerAccessibilityService.get()?.triggerOneHandedGesture()
            ?: android.widget.Toast.makeText(
                this,
                getString(com.ccg.screenblocker.R.string.fab_long_press_a11y_required),
                android.widget.Toast.LENGTH_LONG
            ).show()
    }

    /** 校验前置：service 必须 RUNNING 且 overlayView 已挂载，否则 Toast + return false */
    private fun ensureRunningWithOverlay(source: String): Boolean {
        if (runState != RunState.RUNNING) {
            android.widget.Toast.makeText(
                this,
                getString(com.ccg.screenblocker.R.string.toast_blocker_not_running),
                android.widget.Toast.LENGTH_SHORT
            ).show()
            if (runState == RunState.STOPPED) stopSelf()
            return false
        }
        if (overlayView == null) {
            Log.e(TAG, "$source requested but overlayView is null")
            android.widget.Toast.makeText(
                this,
                getString(com.ccg.screenblocker.R.string.toast_overlay_not_attached_yet),
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return false
        }
        return true
    }

    /**
     * 原子操作（保留供 QuickToggleActivity 敲击背部路径使用）：
     * 翻转 manualBypass + 触发单手模式手势。
     */
    private fun handleToggleBypassAndGesture(intent: Intent) {
        val source = intent.getStringExtra(EXTRA_SOURCE) ?: "unknown"
        if (runState != RunState.RUNNING) {
            android.widget.Toast.makeText(
                this,
                getString(com.ccg.screenblocker.R.string.toast_blocker_not_running),
                android.widget.Toast.LENGTH_SHORT
            ).show()
            // 命令独立到达且服务未运行 → 直接停止
            if (runState == RunState.STOPPED) stopSelf()
            return
        }
        if (overlayView == null) {
            Log.e(TAG, "toggle requested (source=$source) but overlayView is null")
            android.widget.Toast.makeText(
                this,
                getString(com.ccg.screenblocker.R.string.toast_overlay_not_attached_yet),
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        manualBypass = !manualBypass
        Log.i(TAG, "manual bypass toggled by $source → $manualBypass")
        applyBypassState()
        BlockerAccessibilityService.get()?.triggerOneHandedGesture()
            ?: android.widget.Toast.makeText(
                this,
                getString(com.ccg.screenblocker.R.string.fab_long_press_a11y_required),
                android.widget.Toast.LENGTH_LONG
            ).show()
    }

    /**
     * 声明式：实时切换运行态屏蔽层提示色显隐。
     * - 必须携带 EXTRA_VISIBLE，否则 log + 丢弃。
     * - 只更新已挂载的 OverlayView；无 view 时静默（下次 fresh attach 自然读 pref）。
     */
    private fun handleRuntimeVisibleSet(intent: Intent) {
        if (!intent.hasExtra(EXTRA_VISIBLE)) {
            Log.e(TAG, "ACTION_RUNTIME_VISIBLE_SET missing EXTRA_VISIBLE")
            return
        }
        val desired = intent.getBooleanExtra(EXTRA_VISIBLE, true)
        overlayView?.runtimeVisible = desired
    }

    /**
     * 声明式 FAB 显隐：必须携带 EXTRA_VISIBLE，否则 log error 丢弃。
     */
    private fun handleFabSetVisible(intent: Intent) {
        if (!intent.hasExtra(EXTRA_VISIBLE)) {
            Log.e(TAG, "ACTION_FAB_SET_VISIBLE missing EXTRA_VISIBLE")
            return
        }
        val desired = intent.getBooleanExtra(EXTRA_VISIBLE, false)
        when {
            desired && runState == RunState.RUNNING && !fabAttached -> attachFabIfNeeded()
            !desired && fabAttached -> detachFabIfAttached()
            else -> { /* no-op */ }
        }
    }

    /**
     * 单一 sink：将 (manualBypass, autoBypass) → 视图/参数/通知
     */
    private fun applyBypassState() {
        val v = overlayView ?: return
        val bypass = effectiveBypass
        v.bypassRuntime = bypass
        runCatching {
            val params = v.layoutParams as? WindowManager.LayoutParams ?: return@runCatching
            val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            params.flags = if (bypass)
                baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            else baseFlags
            currentWm()?.updateViewLayout(v, params)
        }
        updateFabIcon()
        currentArea?.let { area ->
            runCatching {
                NotificationManagerCompat.from(this).notify(
                    NotificationHelper.NOTIFICATION_ID,
                    NotificationHelper.buildRunning(this, area, manualBypass, autoBypass)
                )
            }
        }
    }

    private fun currentWm(): WindowManager? {
        val a11y = BlockerAccessibilityService.get()
        return if (attachedViaA11y && a11y != null) {
            a11y.getSystemService(WINDOW_SERVICE) as WindowManager
        } else windowManager
    }

    private fun startForegroundCompat(area: BlockArea) {
        val notification = NotificationHelper.buildRunning(this, area, manualBypass, autoBypass)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NotificationHelper.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NotificationHelper.NOTIFICATION_ID, notification)
        }
    }

    private fun addOrUpdateOverlay(area: BlockArea) {
        // 优先使用 AccessibilityService 的 WindowManager + TYPE_ACCESSIBILITY_OVERLAY
        // → trusted window，不被 SurfaceFlinger 的 display-area transform（小米单手模式）影响。
        val a11y = BlockerAccessibilityService.get()
        val useA11y = a11y != null
        val wm: WindowManager = if (useA11y) {
            a11y!!.getSystemService(WINDOW_SERVICE) as WindowManager
        } else {
            windowManager ?: return
        }
        val params = buildOverlayParams(area, useA11y)

        try {
            // 通道切换（普通 ↔ trusted）→ 先彻底移除旧 view
            if (overlayView != null && attachedViaA11y != useA11y) {
                Log.i(TAG, "channel switch: trustedOverlay=$useA11y → re-creating view")
                removeOverlayIfAttached()
            }

            val isFreshAttach = overlayView == null
            if (isFreshAttach) {
                overlayView = OverlayView(if (useA11y) a11y!! else this).also {
                    it.mode = OverlayView.Mode.RUNTIME
                    it.runtimeVisible = runtimeVisiblePref()
                }
                wm.addView(overlayView, params)
                attachedViaA11y = useA11y
                expectedX = area.leftPx
                expectedY = area.topPx
                runState = RunState.RUNNING
                // 注：保留 manualBypass / autoBypass — re-attach（如 a11y 上线）时不清除用户态，
                // 仅 handleStop() / onDestroy() 才完全 reset。
                Log.i(
                    TAG,
                    "overlay attached via=${if (useA11y) "ACCESSIBILITY" else "APPLICATION"} " +
                        "(${params.x},${params.y}) ${params.width}x${params.height}"
                )
                if (autoBypassPref()) {
                    overlayView?.viewTreeObserver?.addOnPreDrawListener(displaceDetector)
                }
                overlayView?.startEnableFlash()
                if (fabPref()) attachFabIfNeeded()
                // 让新挂载的 view + FAB 立刻反映既有 bypass 状态
                applyBypassState()
                notifyStateChanged()
                val msgRes = if (useA11y)
                    com.ccg.screenblocker.R.string.toast_started_trusted
                else
                    com.ccg.screenblocker.R.string.toast_started_with_pos
                android.widget.Toast.makeText(
                    this,
                    getString(msgRes, area.leftPx, area.topPx, area.widthPx, area.heightPx),
                    android.widget.Toast.LENGTH_LONG
                ).show()
            } else {
                wm.updateViewLayout(overlayView, params)
                Log.i(TAG, "overlay updated (${params.x},${params.y}) ${params.width}x${params.height}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "addView failed (a11y=$useA11y)", e)
            android.widget.Toast.makeText(
                this,
                getString(com.ccg.screenblocker.R.string.toast_security_exception) + ": " + e.javaClass.simpleName,
                android.widget.Toast.LENGTH_LONG
            ).show()
            throw e
        }
    }

    private fun runtimeVisiblePref(): Boolean =
        getSharedPreferences("settings_prefs", MODE_PRIVATE)
            .getBoolean("runtime_visible", true)

    private fun fabPref(): Boolean =
        getSharedPreferences("settings_prefs", MODE_PRIVATE)
            .getBoolean("fab_visible", true)

    private fun autoBypassPref(): Boolean =
        getSharedPreferences("settings_prefs", MODE_PRIVATE)
            .getBoolean("auto_bypass_displaced", true)

    /**
     * 创建并挂载悬浮快捷按钮：
     * - 单击 = 切换 manualBypass（开关，不触发录制手势）
     * - 双击 = 强制 manualBypass=true + 触发录制手势
     * - 拖动 = 改变位置（touch slop 区分）
     */
    private fun attachFabIfNeeded() {
        if (fabAttached) return
        if (runState != RunState.RUNNING) {
            Log.w(TAG, "refusing FAB attach in state $runState")
            return
        }
        val wm = windowManager ?: return
        val sizePx = DisplayHelper.dp(this, 56f)

        val initialDrawable = ContextCompat.getDrawable(
            this, com.ccg.screenblocker.R.drawable.ic_fab_pause
        )
        // 渐变背景：呼应 launcher 图标对角渐变；上层 ripple mask 提供按压反馈
        val gradientBg = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                android.graphics.Color.parseColor("#1976D2"),
                android.graphics.Color.parseColor("#0D47A1")
            )
        ).apply { shape = android.graphics.drawable.GradientDrawable.OVAL }
        val rippleMask = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(android.graphics.Color.WHITE)
        }
        val rippleColor = android.content.res.ColorStateList.valueOf(
            android.graphics.Color.parseColor("#33FFFFFF")
        )
        val fabBackground = android.graphics.drawable.RippleDrawable(
            rippleColor, gradientBg, rippleMask
        )
        val iv = android.widget.ImageView(this).apply {
            setImageDrawable(initialDrawable)
            background = fabBackground
            elevation = DisplayHelper.dpF(this@OverlayService, 8f)
            val padPx = DisplayHelper.dp(this@OverlayService, 14f)
            setPadding(padPx, padPx, padPx, padPx)
            contentDescription = getString(com.ccg.screenblocker.R.string.fab_cd_pause)
        }
        fabView = iv
        fabCurrentDrawable = initialDrawable

        val sp = getSharedPreferences("settings_prefs", MODE_PRIVATE)
        val (sw, sh) = DisplayHelper.getRealDisplaySizePx(this)
        val defaultX = sw - sizePx - DisplayHelper.dp(this, 16f)
        val defaultY = sh / 2

        val params = WindowManager.LayoutParams(
            sizePx, sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = sp.getInt("fab_x", defaultX)
            y = sp.getInt("fab_y", defaultY)
            title = "DeadZoneFAB"
        }

        // GestureDetector 区分 single-tap vs double-tap；并提前消费 touch 防止冲突
        val tapDetector = android.view.GestureDetector(
            this,
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean {
                    iv.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    startService(
                        Intent(this@OverlayService, OverlayService::class.java)
                            .setAction(ACTION_TOGGLE_BYPASS_ONLY)
                            .putExtra(EXTRA_SOURCE, "fab")
                    )
                    return true
                }

                override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                    iv.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    startService(
                        Intent(this@OverlayService, OverlayService::class.java)
                            .setAction(ACTION_FORCE_BYPASS_AND_GESTURE)
                            .putExtra(EXTRA_SOURCE, "fab")
                    )
                    return true
                }
            }
        )

        var startTouchX = 0f
        var startTouchY = 0f
        var startParamX = 0
        var startParamY = 0
        var dragging = false
        val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop

        iv.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startTouchX = event.rawX
                    startTouchY = event.rawY
                    startParamX = params.x
                    startParamY = params.y
                    dragging = false
                    tapDetector.onTouchEvent(event)
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - startTouchX).toInt()
                    val dy = (event.rawY - startTouchY).toInt()
                    if (!dragging && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
                        dragging = true
                        // 拖动开始：注入 CANCEL 让 tapDetector 重置 tap-state，避免下次 tap 残留分类错乱
                        val cancel = android.view.MotionEvent.obtain(event).apply {
                            action = android.view.MotionEvent.ACTION_CANCEL
                        }
                        tapDetector.onTouchEvent(cancel)
                        cancel.recycle()
                    }
                    if (dragging) {
                        params.x = (startParamX + dx).coerceIn(0, sw - sizePx)
                        params.y = (startParamY + dy).coerceIn(0, sh - sizePx)
                        runCatching { wm.updateViewLayout(iv, params) }
                    } else {
                        tapDetector.onTouchEvent(event)
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        sp.edit().putInt("fab_x", params.x).putInt("fab_y", params.y).apply()
                    } else {
                        // 非拖动 → 把事件交给 GestureDetector 完成 tap 识别
                        tapDetector.onTouchEvent(event)
                    }
                    true
                }
                else -> false
            }
        }

        // TalkBack 用户自定义 action：等价于双击行为（系统会拦截真实 double-tap 做 focus-click）
        androidx.core.view.ViewCompat.addAccessibilityAction(
            iv,
            getString(com.ccg.screenblocker.R.string.fab_a11y_action_trigger_gesture)
        ) { _, _ ->
            startService(
                Intent(this@OverlayService, OverlayService::class.java)
                    .setAction(ACTION_FORCE_BYPASS_AND_GESTURE)
                    .putExtra(EXTRA_SOURCE, "a11y")
            )
            true
        }

        runCatching {
            wm.addView(iv, params)
            fabAttached = true
            Log.i(TAG, "FAB attached at (${params.x},${params.y})")
        }.onFailure { Log.e(TAG, "FAB addView failed", it) }
    }

    private fun detachFabIfAttached() {
        val v = fabView ?: return
        if (!fabAttached) return
        runCatching { windowManager?.removeViewImmediate(v) }
        fabView = null
        fabAttached = false
        fabCurrentDrawable = null
    }

    /**
     * FAB 图标更新（仅由 applyBypassState() 调用）：cross-fade 200ms + 同步 contentDescription。
     * 图标和 contentDescription 反映 effectiveBypass，使 auto-only 状态也能正确指示「点击恢复」。
     */
    private fun updateFabIcon() {
        val iv = fabView ?: return
        val paused = effectiveBypass
        val targetRes = if (paused)
            com.ccg.screenblocker.R.drawable.ic_play
        else
            com.ccg.screenblocker.R.drawable.ic_fab_pause
        val target = ContextCompat.getDrawable(this, targetRes) ?: return
        val current = fabCurrentDrawable
        if (current != null) {
            val transition = TransitionDrawable(arrayOf(current, target)).apply {
                isCrossFadeEnabled = true
            }
            iv.setImageDrawable(transition)
            transition.startTransition(FAB_ICON_FADE_MS)
        } else {
            iv.setImageDrawable(target)
        }
        fabCurrentDrawable = target
        iv.contentDescription = getString(
            if (paused)
                com.ccg.screenblocker.R.string.fab_cd_resume
            else
                com.ccg.screenblocker.R.string.fab_cd_pause
        )
    }

    /**
     * 位移检测 + 自动让步：
     * - 手动 bypass 期间禁用（避免双向打架）
     * - 阈值 dp(50)：低于即视为正常抖动
     */
    private fun detectDisplacementAndBypass() {
        val v = overlayView as? OverlayView ?: return
        if (!v.isAttachedToWindow) return
        if (manualBypass) return
        v.getLocationOnScreen(locBuf)
        val deltaY = locBuf[1] - expectedY
        val threshold = DisplayHelper.dp(this, 50f)
        val displaced = kotlin.math.abs(deltaY) > threshold
        if (displaced == autoBypass) return

        autoBypass = displaced
        Log.i(TAG, "displacement detected: deltaY=$deltaY → autoBypass=$displaced")
        applyBypassState()
    }

    private fun buildOverlayParams(area: BlockArea, useA11y: Boolean = false): WindowManager.LayoutParams {
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
            title = "TouchBlockOverlay"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun removeOverlayIfAttached() {
        val v = overlayView ?: return
        runCatching { v.viewTreeObserver?.removeOnPreDrawListener(displaceDetector) }
        val wm: WindowManager? = if (attachedViaA11y) {
            BlockerAccessibilityService.get()?.getSystemService(WINDOW_SERVICE) as? WindowManager
        } else {
            windowManager
        } ?: windowManager
        runCatching { wm?.removeViewImmediate(v) }
            .onFailure { Log.w(TAG, "removeView failed (a11y=$attachedViaA11y)", it) }
        overlayView = null
        attachedViaA11y = false
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (runState != RunState.RUNNING) return

        val (dw, dh) = DisplayHelper.getRealDisplaySizePx(this)
        val saved = currentArea ?: return
        val rescaled = saved
            .rescaleIfNeeded(dw, dh)
            .clamp(
                displayWidthPx = dw,
                displayHeightPx = dh,
                marginPx = 0,
                minSizePx = DisplayHelper.dp(this, 72f)
            )
        currentArea = rescaled
        repository.save(rescaled)

        runCatching {
            currentWm()?.updateViewLayout(overlayView, buildOverlayParams(rescaled, attachedViaA11y))
        }.onFailure {
            Log.e(TAG, "updateView on config change failed, fallback to stop", it)
            handleStop()
        }
    }

    override fun onDestroy() {
        pendingEnableRunnable?.let(mainHandler::removeCallbacks)
        pendingEnableRunnable = null
        removeOverlayIfAttached()
        detachFabIfAttached()
        runCatching { unregisterReceiver(a11yReceiver) }
        runState = RunState.STOPPED
        manualBypass = false
        autoBypass = false
        notifyStateChanged()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        handleStop()
        super.onTaskRemoved(rootIntent)
    }

    private fun notifyStateChanged() {
        stateListener?.onServiceStateChanged(runState != RunState.STOPPED)
    }
}
