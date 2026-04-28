package com.ccg.screenblocker.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import androidx.core.content.ContextCompat
import com.ccg.screenblocker.R
import com.ccg.screenblocker.model.Stroke
import com.ccg.screenblocker.util.DisplayHelper

/**
 * 录制时实时绘制手势轨迹（draw-only；触控由 Activity dispatchTouchEvent 处理）。
 *
 * - 颜色：rect_handle
 * - 帧率：Choreographer 合并 invalidate 至 ≤ 60fps，避免高刷设备抖动
 * - 数据：completedStrokes 通过 setStrokes 写入；activeStroke 通过 setActiveStroke 写入
 */
class GestureTrailView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = DisplayHelper.dpF(context, 3f)
        color = ContextCompat.getColor(context, R.color.rect_handle)
    }

    private val path = Path()
    private var completed: List<Stroke> = emptyList()
    private var active: List<PointXY> = emptyList()
    private var pendingFrame = false

    fun setStrokes(strokes: List<Stroke>, activePoints: List<PointXY>) {
        completed = strokes
        active = activePoints
        scheduleInvalidate()
    }

    fun reset() {
        completed = emptyList()
        active = emptyList()
        scheduleInvalidate()
    }

    private fun scheduleInvalidate() {
        if (pendingFrame) return
        pendingFrame = true
        Choreographer.getInstance().postFrameCallback {
            pendingFrame = false
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        path.rewind()
        for (stroke in completed) {
            stroke.points.firstOrNull()?.let { path.moveTo(it.xPx, it.yPx) }
            for (i in 1 until stroke.points.size) {
                val p = stroke.points[i]
                path.lineTo(p.xPx, p.yPx)
            }
        }
        if (active.isNotEmpty()) {
            path.moveTo(active[0].x, active[0].y)
            for (i in 1 until active.size) {
                path.lineTo(active[i].x, active[i].y)
            }
        }
        canvas.drawPath(path, paint)
    }

    /** 临时点（活动 stroke），仅 x/y 用于绘制；时间戳由 Activity 持有。 */
    data class PointXY(val x: Float, val y: Float)
}
