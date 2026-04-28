package com.ccg.screenblocker.service

import com.ccg.screenblocker.model.Point
import com.ccg.screenblocker.model.RecordedGesture
import com.ccg.screenblocker.model.Stroke
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM tests covering P2 (identity scaling), P3 (proportional scaling),
 * P4 (multi-stroke timeline preservation).
 *
 * Tests target GestureReplayBuilder pure helpers (computeScale / strokeTiming /
 * rescalePoint) — does not touch GestureDescription Android instance.
 */
class GestureReplayBuilderTest {

    /** P2 — savedDisplay == currentDisplay → (1f, 1f) and exact identity per point */
    @Test
    fun p2_identityScale_whenDisplayMatches() {
        val rec = RecordedGesture(
            1080, 2400,
            listOf(Stroke(listOf(Point(540f, 2300f, 0L), Point(541f, 2299f, 50L))))
        )
        val (sx, sy) = GestureReplayBuilder.computeScale(rec, 1080, 2400)
        assertEquals(1f, sx, 0f)
        assertEquals(1f, sy, 0f)
        for (p in rec.strokes[0].points) {
            val (x, y) = GestureReplayBuilder.rescalePoint(p.xPx, p.yPx, sx, sy)
            assertEquals(p.xPx, x, 0f)
            assertEquals(p.yPx, y, 0f)
        }
    }

    /** P3 — proportional scaling 0.5x .. 2.0x per axis */
    @Test
    fun p3_proportionalScale_independentAxes() {
        val cases = listOf(
            Triple(1080 to 2400, 540 to 2400, 0.5f to 1.0f),
            Triple(1080 to 2400, 1080 to 4800, 1.0f to 2.0f),
            Triple(1080 to 2400, 2160 to 1200, 2.0f to 0.5f)
        )
        for ((saved, current, expected) in cases) {
            val rec = RecordedGesture(saved.first, saved.second,
                listOf(Stroke(listOf(Point(100f, 200f, 0L), Point(300f, 400f, 50L)))))
            val (sx, sy) = GestureReplayBuilder.computeScale(rec, current.first, current.second)
            assertEquals(expected.first, sx, 1e-6f)
            assertEquals(expected.second, sy, 1e-6f)
            val (x, y) = GestureReplayBuilder.rescalePoint(100f, 200f, sx, sy)
            assertEquals(100f * expected.first, x, 1e-3f)
            assertEquals(200f * expected.second, y, 1e-3f)
        }
    }

    /** P4 — strokeTiming preserves first-point absolute offset; duration ≥ 1ms */
    @Test
    fun p4_strokeTiming_preservesFirstPointOffset() {
        val s1 = Stroke(listOf(Point(0f, 0f, 0L), Point(10f, 10f, 50L)))
        val s2 = Stroke(listOf(Point(20f, 20f, 200L), Point(30f, 30f, 280L)))
        val s3 = Stroke(listOf(Point(40f, 40f, 500L), Point(50f, 50f, 600L)))
        val (start1, dur1) = GestureReplayBuilder.strokeTiming(s1)
        val (start2, dur2) = GestureReplayBuilder.strokeTiming(s2)
        val (start3, dur3) = GestureReplayBuilder.strokeTiming(s3)
        assertEquals(0L, start1)
        assertEquals(50L, dur1)
        assertEquals(200L, start2)
        assertEquals(80L, dur2)
        assertEquals(500L, start3)
        assertEquals(100L, dur3)
        // gap between strokes preserved: stroke2.start - (stroke1.start + stroke1.duration) == 150
        assertEquals(150L, start2 - (start1 + dur1))
    }

    /** P4 — minimum stroke duration = 1ms even if last.t == first.t */
    @Test
    fun p4_strokeTiming_minimumDurationIs1ms() {
        val s = Stroke(listOf(Point(0f, 0f, 100L), Point(10f, 10f, 100L)))
        val (start, duration) = GestureReplayBuilder.strokeTiming(s)
        assertEquals(100L, start)
        assertEquals(1L, duration)
    }

    /** Scale 0.5x both axes */
    @Test
    fun rescalePoint_appliesScaleCorrectly() {
        val (x, y) = GestureReplayBuilder.rescalePoint(200f, 400f, 0.5f, 0.5f)
        assertEquals(100f, x, 0f)
        assertEquals(200f, y, 0f)
    }
}
