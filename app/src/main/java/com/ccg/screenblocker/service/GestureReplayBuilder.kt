package com.ccg.screenblocker.service

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import com.ccg.screenblocker.model.RecordedGesture
import com.ccg.screenblocker.model.Stroke
import kotlin.math.max

/**
 * 纯逻辑：将 RecordedGesture 重建为 GestureDescription，按当前 display rescale。
 *
 * 拆出独立 object 便于纯 JVM 单元测试（不依赖 AccessibilityService 实例）。
 * 注：StrokeDescription / GestureDescription 的实例化依赖 framework，但坐标计算可单独测试。
 */
object GestureReplayBuilder {

    /** 计算 (sx, sy)；savedDisplay==currentDisplay → (1f, 1f) 跳过乘法 */
    fun computeScale(rec: RecordedGesture, currentW: Int, currentH: Int): Pair<Float, Float> {
        val sx = if (currentW == rec.savedDisplayWidthPx) 1f
        else currentW.toFloat() / rec.savedDisplayWidthPx.toFloat()
        val sy = if (currentH == rec.savedDisplayHeightPx) 1f
        else currentH.toFloat() / rec.savedDisplayHeightPx.toFloat()
        return sx to sy
    }

    /** 单 stroke 时序：startTime = first.tMs；duration = max(1, last.tMs - first.tMs) */
    fun strokeTiming(stroke: Stroke): Pair<Long, Long> {
        val startTime = stroke.points.first().tMs
        val duration = max(1L, stroke.points.last().tMs - stroke.points.first().tMs)
        return startTime to duration
    }

    /** 按比例 rescale 单点 */
    fun rescalePoint(xPx: Float, yPx: Float, sx: Float, sy: Float): Pair<Float, Float> =
        (xPx * sx) to (yPx * sy)

    /** 构造完整 GestureDescription（仅在 framework 可用时调用） */
    fun build(rec: RecordedGesture, currentW: Int, currentH: Int): GestureDescription {
        val (sx, sy) = computeScale(rec, currentW, currentH)
        val builder = GestureDescription.Builder()
        for (stroke in rec.strokes) {
            val path = Path()
            val (x0, y0) = rescalePoint(stroke.points[0].xPx, stroke.points[0].yPx, sx, sy)
            path.moveTo(x0, y0)
            for (i in 1 until stroke.points.size) {
                val (x, y) = rescalePoint(stroke.points[i].xPx, stroke.points[i].yPx, sx, sy)
                path.lineTo(x, y)
            }
            val (startTime, duration) = strokeTiming(stroke)
            builder.addStroke(GestureDescription.StrokeDescription(path, startTime, duration))
        }
        return builder.build()
    }
}
