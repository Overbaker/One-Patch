package com.ccg.screenblocker.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.ccg.screenblocker.R
import com.ccg.screenblocker.model.BlockArea
import com.ccg.screenblocker.util.DisplayHelper
import kotlin.math.max

/**
 * 屏幕区域屏蔽 View。
 *
 * - [Mode.EDIT]：可见，绘制屏幕预览 letterbox + 屏蔽矩形 + 输入法警告区。
 *   触控分发：四角圆形热区 → 缩放，矩形内部 → 移动。
 *
 * - [Mode.RUNTIME]：由 WindowManager 添加，**默认半透明可见**（不再 100% 透明）：
 *   - 12% alpha 蓝色填充
 *   - 50% alpha 红色细边框
 *   - 启用瞬间 1.5s 高亮闪烁动画提示用户屏蔽生效
 *   - 可通过 setRuntimeVisible(false) 切换为完全透明
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Mode { EDIT, RUNTIME }
    enum class GestureMode { NONE, MOVE, RESIZE_TL, RESIZE_TR, RESIZE_BL, RESIZE_BR }

    var mode: Mode = Mode.EDIT
        set(value) {
            field = value
            invalidate()
        }

    /** 运行态是否绘制可见提示（默认 true，可由用户切换） */
    var runtimeVisible: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    /**
     * 运行态自动让步：检测到屏幕被系统级 transform（如小米单手模式）下移后，
     * 暂时不消费触控、不绘制内容，让单手模式期间整个屏幕可正常操作。
     * 位置回归后由 OverlayService 复位为 false。
     */
    var bypassRuntime: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var onAreaChangedListener: ((rect: RectF, isFinal: Boolean) -> Unit)? = null

    private var screenWidthPx: Int = 0
    private var screenHeightPx: Int = 0

    private val letterbox = RectF()
    private val rect = RectF()

    private val handleRadiusPx by lazy { DisplayHelper.dpF(context, 18f) }
    private val cornerIndicatorPx by lazy { DisplayHelper.dpF(context, 10f) }
    private val borderStrokePx by lazy { DisplayHelper.dpF(context, 2f) }
    private val runtimeBorderStrokePx by lazy { DisplayHelper.dpF(context, 1.5f) }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.rect_fill_edit)
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ContextCompat.getColor(context, R.color.rect_border_edit)
        pathEffect = DashPathEffect(floatArrayOf(16f, 8f), 0f)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.rect_handle)
    }
    private val frameOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = ContextCompat.getColor(context, R.color.outline)
    }
    private val imeWarnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.ime_warning_zone)
    }
    private val imeWarnLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = ContextCompat.getColor(context, R.color.status_warning)
        pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }

    // 运行态绘制 paints
    private val runtimeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.runtime_block_fill)
    }
    private val runtimeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ContextCompat.getColor(context, R.color.runtime_block_border)
    }
    private val runtimeFlashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.runtime_block_flash)
    }
    private var flashAlpha: Float = 0f
    private var flashAnimator: ValueAnimator? = null

    private var gestureMode = GestureMode.NONE
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var initialRect = RectF()

    init {
        isClickable = true
        isFocusable = false
    }

    fun setScreenSize(widthPx: Int, heightPx: Int) {
        screenWidthPx = widthPx
        screenHeightPx = heightPx
        recomputeLetterbox()
        invalidate()
    }

    /** 启动 1.5s 闪烁动画（运行态启用瞬间反馈） */
    fun startEnableFlash() {
        flashAnimator?.cancel()
        flashAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 1500L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                flashAlpha = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeLetterbox()
    }

    /**
     * 全屏模式：View 尺寸已等于屏幕物理尺寸，跳过 letterbox 缩放（直接 1:1）。
     * 由 FullscreenEditorActivity 调用。
     */
    var fullscreenMode: Boolean = false
        set(value) {
            field = value
            recomputeLetterbox()
            invalidate()
        }

    private fun recomputeLetterbox() {
        val vw = width
        val vh = height
        if (mode == Mode.RUNTIME || fullscreenMode) {
            // letterbox = 整个 View（运行态由 WindowManager 控制尺寸；全屏编辑态 View 已等于屏幕）
            letterbox.set(0f, 0f, vw.toFloat(), vh.toFloat())
            if (mode == Mode.RUNTIME) rect.set(letterbox)
            return
        }
        if (vw <= 0 || vh <= 0 || screenWidthPx <= 0 || screenHeightPx <= 0) return

        val screenRatio = screenWidthPx.toFloat() / screenHeightPx.toFloat()
        val viewRatio = vw.toFloat() / vh.toFloat()
        val lbW: Float
        val lbH: Float
        if (viewRatio > screenRatio) {
            lbH = vh.toFloat()
            lbW = lbH * screenRatio
        } else {
            lbW = vw.toFloat()
            lbH = lbW / screenRatio
        }
        val lbLeft = (vw - lbW) / 2f
        val lbTop = (vh - lbH) / 2f
        letterbox.set(lbLeft, lbTop, lbLeft + lbW, lbTop + lbH)
        clampRect()
    }

    fun setRect(left: Float, top: Float, width: Float, height: Float) {
        rect.set(left, top, left + width, top + height)
        clampRect()
        invalidate()
    }

    fun applyFromBlockArea(area: BlockArea, displayWidthPx: Int, displayHeightPx: Int) {
        screenWidthPx = displayWidthPx
        screenHeightPx = displayHeightPx
        if (letterbox.isEmpty) recomputeLetterbox()
        if (letterbox.isEmpty || displayWidthPx <= 0 || displayHeightPx <= 0) {
            post { applyFromBlockArea(area, displayWidthPx, displayHeightPx) }
            return
        }
        val sx = letterbox.width() / displayWidthPx.toFloat()
        val sy = letterbox.height() / displayHeightPx.toFloat()
        rect.set(
            letterbox.left + area.leftPx * sx,
            letterbox.top + area.topPx * sy,
            letterbox.left + (area.leftPx + area.widthPx) * sx,
            letterbox.top + (area.topPx + area.heightPx) * sy
        )
        clampRect()
        invalidate()
    }

    fun toBlockArea(displayWidthPx: Int, displayHeightPx: Int): BlockArea {
        if (letterbox.isEmpty) return BlockArea.default(displayWidthPx, displayHeightPx)
        val sx = displayWidthPx.toFloat() / letterbox.width()
        val sy = displayHeightPx.toFloat() / letterbox.height()
        val xPx = ((rect.left - letterbox.left) * sx).toInt()
        val yPx = ((rect.top - letterbox.top) * sy).toInt()
        val wPx = (rect.width() * sx).toInt()
        val hPx = (rect.height() * sy).toInt()
        return BlockArea(
            leftPx = xPx.coerceAtLeast(0),
            topPx = yPx.coerceAtLeast(0),
            widthPx = wPx.coerceAtLeast(1),
            heightPx = hPx.coerceAtLeast(1),
            savedDisplayWidthPx = displayWidthPx,
            savedDisplayHeightPx = displayHeightPx
        )
    }

    fun resetToDefault() {
        if (letterbox.isEmpty) return
        val w = letterbox.width() * 0.6f
        val h = letterbox.height() * 0.12f
        val l = letterbox.left + (letterbox.width() - w) / 2f
        val t = letterbox.top + letterbox.height() * 0.25f
        rect.set(l, t, l + w, t + h)
        clampRect()
        invalidate()
        onAreaChangedListener?.invoke(RectF(rect), true)
    }

    private fun clampRect() {
        if (letterbox.isEmpty) return

        val minSizeView = if (screenWidthPx > 0)
            DisplayHelper.dpF(context, 72f) * letterbox.width() / screenWidthPx.toFloat()
        else DisplayHelper.dpF(context, 72f)

        val w = max(rect.width(), minSizeView).coerceAtMost(letterbox.width())
        val h = max(rect.height(), minSizeView).coerceAtMost(letterbox.height())
        val l = rect.left.coerceIn(letterbox.left, letterbox.right - w)
        val t = rect.top.coerceIn(letterbox.top, letterbox.bottom - h)
        rect.set(l, t, l + w, t + h)
    }

    override fun onDraw(canvas: Canvas) {
        when (mode) {
            Mode.RUNTIME -> drawRuntime(canvas)
            Mode.EDIT -> drawEdit(canvas)
        }
    }

    private fun drawRuntime(canvas: Canvas) {
        if (bypassRuntime) return  // 让步模式：不绘制
        if (!runtimeVisible && flashAlpha <= 0f) return  // 完全透明模式

        val w = width.toFloat()
        val h = height.toFloat()

        if (runtimeVisible) {
            canvas.drawRect(0f, 0f, w, h, runtimeFillPaint)
            runtimeBorderPaint.strokeWidth = runtimeBorderStrokePx
            // 内缩 1px 避免边框被裁剪
            canvas.drawRect(
                runtimeBorderStrokePx / 2f,
                runtimeBorderStrokePx / 2f,
                w - runtimeBorderStrokePx / 2f,
                h - runtimeBorderStrokePx / 2f,
                runtimeBorderPaint
            )
        }

        // 启用瞬间高亮闪烁
        if (flashAlpha > 0f) {
            val flashColor = ContextCompat.getColor(context, R.color.runtime_block_flash)
            runtimeFlashPaint.color = (Color.alpha(flashColor) * flashAlpha).toInt()
                .coerceIn(0, 255)
                .let { (it shl 24) or (flashColor and 0x00FFFFFF) }
            canvas.drawRect(0f, 0f, w, h, runtimeFlashPaint)
        }
    }

    private fun drawEdit(canvas: Canvas) {
        if (letterbox.isEmpty) return

        // 输入法可能受影响区（屏幕底部 ~38%，与系统输入法弹起高度大致重合）
        val imeZoneTop = letterbox.top + letterbox.height() * 0.62f
        canvas.drawRect(
            letterbox.left, imeZoneTop, letterbox.right, letterbox.bottom, imeWarnPaint
        )
        canvas.drawLine(
            letterbox.left, imeZoneTop, letterbox.right, imeZoneTop, imeWarnLinePaint
        )

        // 屏幕外框（letterbox 边界）
        canvas.drawRect(letterbox, frameOutlinePaint)

        if (rect.isEmpty) return

        canvas.drawRoundRect(rect, 8f, 8f, fillPaint)
        borderPaint.strokeWidth = borderStrokePx
        canvas.drawRoundRect(rect, 8f, 8f, borderPaint)

        canvas.drawCircle(rect.left, rect.top, cornerIndicatorPx, handlePaint)
        canvas.drawCircle(rect.right, rect.top, cornerIndicatorPx, handlePaint)
        canvas.drawCircle(rect.left, rect.bottom, cornerIndicatorPx, handlePaint)
        canvas.drawCircle(rect.right, rect.bottom, cornerIndicatorPx, handlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (mode == Mode.RUNTIME) {
            // 让步模式：不消费触控（配合 LayoutParams 的 FLAG_NOT_TOUCHABLE 让事件透传到底层）
            return !bypassRuntime
        }
        return handleEditTouch(event)
    }

    private fun handleEditTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureMode = hitTest(event.x, event.y)
                if (gestureMode == GestureMode.NONE) return false
                lastTouchX = event.x
                lastTouchY = event.y
                initialRect.set(rect)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (gestureMode == GestureMode.NONE) return false
                applyGesture(event.x - lastTouchX, event.y - lastTouchY)
                clampRect()
                invalidate()
                onAreaChangedListener?.invoke(RectF(rect), false)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (gestureMode != GestureMode.NONE) {
                    onAreaChangedListener?.invoke(RectF(rect), true)
                    gestureMode = GestureMode.NONE
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
            }
        }
        return false
    }

    private fun hitTest(x: Float, y: Float): GestureMode {
        val r = rect
        val rSq = handleRadiusPx * handleRadiusPx
        fun dSq(px: Float, py: Float): Float {
            val ddx = x - px; val ddy = y - py
            return ddx * ddx + ddy * ddy
        }
        return when {
            dSq(r.left, r.top) <= rSq -> GestureMode.RESIZE_TL
            dSq(r.right, r.top) <= rSq -> GestureMode.RESIZE_TR
            dSq(r.left, r.bottom) <= rSq -> GestureMode.RESIZE_BL
            dSq(r.right, r.bottom) <= rSq -> GestureMode.RESIZE_BR
            r.contains(x, y) -> GestureMode.MOVE
            else -> GestureMode.NONE
        }
    }

    private fun applyGesture(dx: Float, dy: Float) {
        when (gestureMode) {
            GestureMode.MOVE -> rect.set(
                initialRect.left + dx, initialRect.top + dy,
                initialRect.right + dx, initialRect.bottom + dy
            )
            GestureMode.RESIZE_TL -> {
                rect.set(initialRect.left + dx, initialRect.top + dy, initialRect.right, initialRect.bottom)
                normalizeRect()
            }
            GestureMode.RESIZE_TR -> {
                rect.set(initialRect.left, initialRect.top + dy, initialRect.right + dx, initialRect.bottom)
                normalizeRect()
            }
            GestureMode.RESIZE_BL -> {
                rect.set(initialRect.left + dx, initialRect.top, initialRect.right, initialRect.bottom + dy)
                normalizeRect()
            }
            GestureMode.RESIZE_BR -> {
                rect.set(initialRect.left, initialRect.top, initialRect.right + dx, initialRect.bottom + dy)
                normalizeRect()
            }
            GestureMode.NONE -> Unit
        }
    }

    private fun normalizeRect() {
        if (rect.left > rect.right) { val t = rect.left; rect.left = rect.right; rect.right = t }
        if (rect.top > rect.bottom) { val t = rect.top; rect.top = rect.bottom; rect.bottom = t }
    }

    fun getCurrentRectF(): RectF = RectF(rect)
}
