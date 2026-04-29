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
        /** a11y 服务下线 → 通知 OverlayService 触发 backend fallback */
        const val ACTION_A11Y_UNBOUND = "com.ccg.screenblocker.action.A11Y_UNBOUND"

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
        /** 反向补偿 hysteresis 死区：abs(drift) <= dp(2) 时不补偿（noise floor） */
        const val DRIFT_HYSTERESIS_DP = 2f
    }

    interface StateListener {
        fun onServiceStateChanged(running: Boolean)
    }

    inner class LocalBinder : Binder() {
        fun getService(): OverlayService = this@OverlayService
        fun isRunning(): Boolean = runState != RunState.STOPPED
        fun isAttachedViaA11y(): Boolean =
            activeBackend?.id() == OverlayBackend.ID_ACCESSIBILITY ||
                activeBackend?.id() == OverlayBackend.ID_PHYSICAL_DISPLAY
        fun getActiveBackend(): String = activeBackend?.id() ?: OverlayBackend.ID_NONE
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

    /** 监听 AccessibilityService 上下线，用于在用户授权后自动重挂 trusted overlay 或 fallback */
    private val a11yReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_A11Y_AVAILABLE -> {
                    if (runState != RunState.STOPPED) {
                        Log.i(TAG, "a11y available, re-attaching as trusted overlay")
                        currentArea?.let { area ->
                            removeOverlayIfAttached()
                            runCatching { addOrUpdateOverlay(area) }
                                .onFailure { Log.e(TAG, "re-attach failed", it) }
                        }
                    }
                }
                ACTION_A11Y_UNBOUND -> {
                    if (runState == RunState.RUNNING &&
                        activeBackend?.id() == OverlayBackend.ID_PHYSICAL_DISPLAY
                    ) {
                        triggerFallbackToWindowManager("a11y_unbound")
                    }
                }
            }
        }
    }

    /** 当前激活的 overlay backend（WindowManager 或 DisplayAttached） */
    private var activeBackend: OverlayBackend? = null

    /** 当前 service 生命周期内是否已经触发过 fallback（防 ping-pong） */
    private var fallbackTriggered: Boolean = false

    /** 持续漂移帧计数（hysteresis 阈值 = 2，仅 PHYSICAL_DISPLAY → fallback 用） */
    private var driftFrameCount: Int = 0

    /** 反向补偿累积量（仅 WindowManagerBackend + anti_transform=true 路径使用） */
    private var compensatedDeltaX: Int = 0
    private var compensatedDeltaY: Int = 0

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
        val filter = android.content.IntentFilter().apply {
            addAction(ACTION_A11Y_AVAILABLE)
            addAction(ACTION_A11Y_UNBOUND)
        }
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
        fallbackTriggered = false  // 下次 enable 重新评估 backend
        driftFrameCount = 0
        compensatedDeltaX = 0
        compensatedDeltaY = 0
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
     * 单一 sink：将 (manualBypass, autoBypass) → 视图/参数/通知。
     * - WindowManagerBackend 路径：FLAG_NOT_TOUCHABLE 经 WindowManager.updateViewLayout 切换
     * - DisplayAttachedBackend 路径：靠 OverlayView.bypassRuntime + onTouchEvent 返回 false 实现透传
     */
    private fun applyBypassState() {
        val v = overlayView ?: return
        val bypass = effectiveBypass
        v.bypassRuntime = bypass
        val backend = activeBackend
        if (backend is WindowManagerBackend) {
            runCatching {
                val params = v.layoutParams as? WindowManager.LayoutParams ?: return@runCatching
                val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                params.flags = if (bypass)
                    baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                else baseFlags
                backend.currentWm()?.updateViewLayout(v, params)
            }
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

    /**
     * 选择 backend：
     * - API 34 + a11y service bound + 未 fallback → DisplayAttachedBackend
     * - 否则 → WindowManagerBackend
     *
     * 注意：DisplayAttachedBackend 引用必须位于 SDK gate 内，避免 API < 34 类加载错误。
     */
    private fun selectBackend(view: OverlayView): OverlayBackend {
        val a11yBound = BlockerAccessibilityService.get() != null
        val wm = windowManager ?: return WindowManagerBackend(this, view, getSystemService(WINDOW_SERVICE) as WindowManager)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            a11yBound && !fallbackTriggered
        ) {
            return DisplayAttachedBackend(this, view)
        }
        return WindowManagerBackend(this, view, wm)
    }

    private fun addOrUpdateOverlay(area: BlockArea) {
        val isFreshAttach = overlayView == null

        if (!isFreshAttach) {
            // 已挂载：仅更新位置/尺寸
            activeBackend?.update(area)
            Log.i(TAG, "overlay updated to (${area.leftPx},${area.topPx}) ${area.widthPx}x${area.heightPx}")
            return
        }

        // Fresh attach 路径
        val a11y = BlockerAccessibilityService.get()
        val viewContext: android.content.Context = a11y ?: this
        val view = OverlayView(viewContext).also {
            it.mode = OverlayView.Mode.RUNTIME
            it.runtimeVisible = runtimeVisiblePref()
        }
        overlayView = view

        // 选 backend，attach 失败则 fallback 到 WindowManagerBackend
        val primary = selectBackend(view)
        val ok = try {
            primary.attach(area)
        } catch (e: Exception) {
            Log.e(TAG, "primary attach threw", e)
            false
        }

        activeBackend = if (ok) {
            primary
        } else {
            if (primary !is WindowManagerBackend) {
                fallbackTriggered = true
                Log.w(TAG, "primary backend ${primary.id()} failed, falling back to WindowManager")
            }
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val legacy = WindowManagerBackend(this, view, wm)
            if (!legacy.attach(area)) {
                Log.e(TAG, "legacy backend also failed; aborting attach")
                overlayView = null
                android.widget.Toast.makeText(
                    this,
                    getString(com.ccg.screenblocker.R.string.toast_security_exception),
                    android.widget.Toast.LENGTH_LONG
                ).show()
                return
            }
            legacy
        }

        expectedX = area.leftPx
        expectedY = area.topPx
        runState = RunState.RUNNING
        driftFrameCount = 0
        compensatedDeltaX = 0
        compensatedDeltaY = 0
        Log.i(TAG, "overlay attached via=${activeBackend?.id()} (${area.leftPx},${area.topPx}) ${area.widthPx}x${area.heightPx}")

        if (autoBypassPref() || activeBackend?.id() == OverlayBackend.ID_PHYSICAL_DISPLAY) {
            // PHYSICAL_DISPLAY 下也开 detector，用于驱动漂移 fallback
            overlayView?.viewTreeObserver?.addOnPreDrawListener(displaceDetector)
        }
        overlayView?.startEnableFlash()
        if (fabPref()) attachFabIfNeeded()
        applyBypassState()
        notifyStateChanged()
        val msgRes = if (activeBackend?.id() == OverlayBackend.ID_ACCESSIBILITY ||
            activeBackend?.id() == OverlayBackend.ID_PHYSICAL_DISPLAY
        ) com.ccg.screenblocker.R.string.toast_started_trusted
        else com.ccg.screenblocker.R.string.toast_started_with_pos
        android.widget.Toast.makeText(
            this,
            getString(msgRes, area.leftPx, area.topPx, area.widthPx, area.heightPx),
            android.widget.Toast.LENGTH_LONG
        ).show()
    }

    /**
     * 漂移检测触发：DisplayAttachedBackend 下若 view 仍被 transform 平移 → fallback 老路径。
     * 防 ping-pong：单 RUNNING 周期内仅触发一次。
     */
    private fun triggerFallbackToWindowManager(reason: String) {
        if (fallbackTriggered) return
        if (runState != RunState.RUNNING) return
        fallbackTriggered = true
        Log.w(TAG, "fallback to WindowManager backend: reason=$reason")

        val area = currentArea ?: return
        val view = overlayView ?: return

        runCatching { activeBackend?.detach() }
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val legacy = WindowManagerBackend(this, view, wm)
        if (legacy.attach(area)) {
            activeBackend = legacy
            applyBypassState()
            android.widget.Toast.makeText(
                this,
                getString(com.ccg.screenblocker.R.string.toast_fallback_to_legacy),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        } else {
            Log.e(TAG, "fallback legacy attach failed; stopping service")
            handleStop()
        }
        driftFrameCount = 0
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
     * 位移检测 + transform 抵消 / 让步分发：
     * - 信号源：OverlayView.getLocationOnScreen() 报告的 ViewRootImpl-reported position（C19/C25）
     * - 三分支：
     *   (a) PHYSICAL_DISPLAY backend：连续 ≥ 2 帧漂移 > dp(50) → 触发 fallback
     *   (b) WindowManagerBackend + anti_transform=true：反向 LayoutParams 补偿（钉物理坐标）
     *   (c) WindowManagerBackend + anti_transform=false：v1 让步行为（设置 autoBypass）
     */
    private fun detectDisplacementAndBypass() {
        if (runState != RunState.RUNNING) return
        if (manualBypass) return
        val v = overlayView as? OverlayView ?: return
        if (!v.isAttachedToWindow) return
        v.getLocationOnScreen(locBuf)
        val driftX = locBuf[0] - expectedX
        val driftY = locBuf[1] - expectedY

        // (a) PHYSICAL_DISPLAY 路径：连续漂移 > dp(50) → fallback
        if (activeBackend?.id() == OverlayBackend.ID_PHYSICAL_DISPLAY) {
            val fallbackThresholdPx = DisplayHelper.dp(this, 50f)
            if (kotlin.math.abs(driftY) > fallbackThresholdPx ||
                kotlin.math.abs(driftX) > fallbackThresholdPx
            ) {
                driftFrameCount++
                if (driftFrameCount >= 2) {
                    triggerFallbackToWindowManager("displaced")
                }
            } else {
                driftFrameCount = 0
            }
            return
        }

        // (b/c) WindowManagerBackend 路径
        val backend = activeBackend ?: return
        if (backend !is WindowManagerBackend) return
        val area = currentArea ?: return

        if (autoBypassPref()) {
            // (b) 反向补偿：累积式
            val hysteresisPx = DisplayHelper.dp(this, DRIFT_HYSTERESIS_DP)
            if (kotlin.math.abs(driftX) <= hysteresisPx &&
                kotlin.math.abs(driftY) <= hysteresisPx
            ) return
            compensatedDeltaX -= driftX
            compensatedDeltaY -= driftY
            val compensatedArea = area.copy(
                leftPx = area.leftPx + compensatedDeltaX,
                topPx = area.topPx + compensatedDeltaY
            )
            backend.update(compensatedArea)
            Log.i(TAG, "compensate drift=($driftX,$driftY) delta=($compensatedDeltaX,$compensatedDeltaY)")
        } else {
            // (c) v1 让步行为
            val legacyThresholdPx = DisplayHelper.dp(this, 50f)
            val displaced = kotlin.math.abs(driftY) > legacyThresholdPx
            if (displaced == autoBypass) return
            autoBypass = displaced
            Log.i(TAG, "displacement detected: deltaY=$driftY → autoBypass=$displaced")
            applyBypassState()
        }
    }

    private fun removeOverlayIfAttached() {
        val v = overlayView ?: return
        runCatching { v.viewTreeObserver?.removeOnPreDrawListener(displaceDetector) }
        runCatching { activeBackend?.detach() }
            .onFailure { Log.w(TAG, "backend detach failed", it) }
        activeBackend = null
        overlayView = null
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
        // 重置反向补偿状态：旋转/字号变更后基于新 area 重新测量
        compensatedDeltaX = 0
        compensatedDeltaY = 0
        expectedX = rescaled.leftPx
        expectedY = rescaled.topPx

        // DisplayAttachedBackend 在 config 变化时 detach + attach（重建 surface 大小）；
        // WindowManagerBackend 仅 update。
        runCatching {
            val backend = activeBackend
            if (backend is DisplayAttachedBackend) {
                backend.detach()
                if (!backend.attach(rescaled)) {
                    triggerFallbackToWindowManager("config_change_reattach_failed")
                }
            } else {
                backend?.update(rescaled)
            }
        }.onFailure {
            Log.e(TAG, "config change relayout failed, stopping", it)
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
        compensatedDeltaX = 0
        compensatedDeltaY = 0
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
