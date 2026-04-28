package com.ccg.screenblocker.model

import kotlin.math.max

/**
 * 用户录制的单手模式触发手势。
 *
 * - 坐标 = 录制时绝对像素 + saved display size，回放时按当前 displayPx rescale。
 * - 多 stroke = 用户在录制中 lift-and-repress 产生的多段 finger-down。
 * - tMsFromGestureStart 以「整段手势」第一点为 0，跨 stroke 累计；保留 inter-stroke gap。
 */
data class RecordedGesture(
    val savedDisplayWidthPx: Int,
    val savedDisplayHeightPx: Int,
    val strokes: List<Stroke>
) {
    init {
        require(savedDisplayWidthPx > 0) { "savedDisplayWidthPx must be > 0" }
        require(savedDisplayHeightPx > 0) { "savedDisplayHeightPx must be > 0" }
    }
}

data class Stroke(val points: List<Point>) {
    init {
        require(points.size >= 2) { "stroke must have at least 2 points" }
    }

    /** 该 stroke 内部时长；回放 StrokeDescription 要求 ≥ 1ms。 */
    val durationMs: Long
        get() = max(1L, points.last().tMs - points.first().tMs)
}

data class Point(val xPx: Float, val yPx: Float, val tMs: Long)
