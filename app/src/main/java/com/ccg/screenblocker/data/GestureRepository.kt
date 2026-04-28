package com.ccg.screenblocker.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.ccg.screenblocker.model.Point
import com.ccg.screenblocker.model.RecordedGesture
import com.ccg.screenblocker.model.Stroke

/**
 * 单一槽位的录制手势仓库。
 *
 * - 沿用 SharedPrefsBlockAreaRepository 的 schema-version + 扁平 key 模式
 * - 编码：strokes 用 ";"、points 用 ","、字段 "x:y:t"（无 JSON）
 * - 校验：strokes ≤ 10、Σduration ≤ 60_000ms、stroke ≥ 2 点、duration ≥ 1ms
 */
class GestureRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun has(): Boolean =
        prefs.contains(KEY_STROKES_PAYLOAD) &&
            prefs.getInt(KEY_SCHEMA_VERSION, -1) == SCHEMA_VERSION

    fun load(): RecordedGesture? {
        val hasPayload = prefs.contains(KEY_STROKES_PAYLOAD)
        if (!hasPayload) return null
        val schemaOk = prefs.getInt(KEY_SCHEMA_VERSION, -1) == SCHEMA_VERSION
        if (!schemaOk) {
            clear()
            return null
        }
        val w = prefs.getInt(KEY_SAVED_DISPLAY_W, -1)
        val h = prefs.getInt(KEY_SAVED_DISPLAY_H, -1)
        val payload = prefs.getString(KEY_STROKES_PAYLOAD, null)
        if (w <= 0 || h <= 0 || payload.isNullOrEmpty()) {
            clear()
            return null
        }
        return runCatching { RecordedGesture(w, h, decode(payload)) }
            .onFailure { clear() }
            .getOrNull()
    }

    fun save(gesture: RecordedGesture) {
        validate(gesture)
        prefs.edit {
            putInt(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
            putInt(KEY_SAVED_DISPLAY_W, gesture.savedDisplayWidthPx)
            putInt(KEY_SAVED_DISPLAY_H, gesture.savedDisplayHeightPx)
            putInt(KEY_STROKE_COUNT, gesture.strokes.size)
            putString(KEY_STROKES_PAYLOAD, encode(gesture.strokes))
        }
    }

    fun clear() {
        prefs.edit {
            remove(KEY_SCHEMA_VERSION)
            remove(KEY_SAVED_DISPLAY_W)
            remove(KEY_SAVED_DISPLAY_H)
            remove(KEY_STROKE_COUNT)
            remove(KEY_STROKES_PAYLOAD)
        }
    }

    companion object {
        private const val PREFS_NAME = "gesture_prefs"
        const val SCHEMA_VERSION = 1
        const val MAX_STROKES = 10
        const val MAX_TOTAL_DURATION_MS = 60_000L

        private const val KEY_SCHEMA_VERSION = "schema_version"
        private const val KEY_SAVED_DISPLAY_W = "saved_w"
        private const val KEY_SAVED_DISPLAY_H = "saved_h"
        private const val KEY_STROKE_COUNT = "stroke_count"
        private const val KEY_STROKES_PAYLOAD = "strokes"

        /**
         * 校验录制是否在 GestureDescription 限制内。
         * 任一项越界 → 抛 IllegalArgumentException，由调用者通过 Snackbar 提示用户重录。
         */
        fun validate(gesture: RecordedGesture) {
            require(gesture.strokes.isNotEmpty()) { "no strokes" }
            require(gesture.strokes.size <= MAX_STROKES) {
                "too many strokes: ${gesture.strokes.size} > $MAX_STROKES"
            }
            val totalDuration = gesture.strokes.sumOf { it.durationMs }
            require(totalDuration <= MAX_TOTAL_DURATION_MS) {
                "total stroke duration $totalDuration ms exceeds $MAX_TOTAL_DURATION_MS ms"
            }
            // wall-clock：replay 用 absolute startTime + duration，长 inter-stroke gap 也计入
            val wallClockMs = gesture.strokes.last().points.last().tMs -
                gesture.strokes.first().points.first().tMs
            require(wallClockMs <= MAX_TOTAL_DURATION_MS) {
                "wall-clock duration $wallClockMs ms exceeds $MAX_TOTAL_DURATION_MS ms"
            }
            for ((i, stroke) in gesture.strokes.withIndex()) {
                require(stroke.points.size >= 2) { "stroke[$i] has < 2 points" }
                require(stroke.durationMs >= 1L) { "stroke[$i] duration < 1ms" }
            }
        }

        internal fun encode(strokes: List<Stroke>): String =
            strokes.joinToString(";") { stroke ->
                stroke.points.joinToString(",") { "${it.xPx}:${it.yPx}:${it.tMs}" }
            }

        internal fun decode(payload: String): List<Stroke> =
            payload.split(";").map { strokePart ->
                val points = strokePart.split(",").map { pointPart ->
                    val parts = pointPart.split(":")
                    require(parts.size == 3) { "invalid point: $pointPart" }
                    Point(parts[0].toFloat(), parts[1].toFloat(), parts[2].toLong())
                }
                Stroke(points)
            }
    }
}
