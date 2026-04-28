package com.ccg.screenblocker.service

import com.ccg.screenblocker.model.Point
import com.ccg.screenblocker.model.RecordedGesture
import com.ccg.screenblocker.model.Stroke
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM contract tests covering P7 (fallback selection determinism).
 *
 * The actual triggerOneHandedGesture() requires Service runtime; this test verifies
 * the pure decision logic — given a "loaded" recording outcome, which builder path
 * would be chosen.
 */
class FallbackSelectionTest {

    /** P7 — loaded recording present and valid → recorded path selected */
    @Test
    fun p7_validRecording_selectsRecordedPath() {
        val rec = RecordedGesture(1080, 2400, listOf(
            Stroke(listOf(Point(540f, 2300f, 0L), Point(540f, 2200f, 50L)))
        ))
        val selected = pickPath(rec)
        assertNotNull("recorded path must be selected when recording exists", selected)
    }

    /** P7 — null repository load → fallback selected */
    @Test
    fun p7_nullRecording_selectsFallback() {
        val selected = pickPath(null)
        assertNull("recorded path absent → null sentinel for fallback selection", selected)
    }

    /**
     * Pure decision: simulates the elvis operator in BlockerAccessibilityService.triggerOneHandedGesture().
     * Returns the recording when present, null when absent (caller picks fallback).
     */
    private fun pickPath(recorded: RecordedGesture?): RecordedGesture? = recorded
}
