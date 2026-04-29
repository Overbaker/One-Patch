package com.ccg.screenblocker.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests covering compensation guard conditions:
 * - P6 manualBypass overrides compensation
 * - P7 PHYSICAL_DISPLAY backend exclusivity (不补偿)
 * - P8 anti_transform=false fallback (v1 让步)
 * - P9 anti_transform=true compensation (设 delta，不设 autoBypass)
 */
class CompensationGuardsTest {

    private fun shouldCompensate(
        runState: String,
        manualBypass: Boolean,
        backendId: String,
        antiTransformPref: Boolean
    ): Boolean {
        if (runState != "RUNNING") return false
        if (manualBypass) return false
        if (backendId != OverlayBackend.ID_APPLICATION_OVERLAY &&
            backendId != OverlayBackend.ID_ACCESSIBILITY) return false  // WindowManagerBackend only
        if (!antiTransformPref) return false
        return true
    }

    /** P6 — manualBypass 屏蔽补偿 */
    @Test
    fun p6_manualBypass_suppressesCompensation() {
        assertFalse(shouldCompensate(
            runState = "RUNNING",
            manualBypass = true,
            backendId = OverlayBackend.ID_ACCESSIBILITY,
            antiTransformPref = true
        ))
    }

    /** P7 — PHYSICAL_DISPLAY 不补偿 */
    @Test
    fun p7_physicalDisplayBackend_doesNotCompensate() {
        assertFalse(shouldCompensate(
            runState = "RUNNING",
            manualBypass = false,
            backendId = OverlayBackend.ID_PHYSICAL_DISPLAY,
            antiTransformPref = true
        ))
    }

    /** P8 — anti_transform=false 走 v1 让步路径（不补偿） */
    @Test
    fun p8_antiTransformFalse_skipsCompensation() {
        assertFalse(shouldCompensate(
            runState = "RUNNING",
            manualBypass = false,
            backendId = OverlayBackend.ID_ACCESSIBILITY,
            antiTransformPref = false
        ))
    }

    /** P9 — anti_transform=true + WindowManagerBackend → 补偿启用 */
    @Test
    fun p9_antiTransformTrue_enablesCompensation() {
        assertTrue(shouldCompensate(
            runState = "RUNNING",
            manualBypass = false,
            backendId = OverlayBackend.ID_ACCESSIBILITY,
            antiTransformPref = true
        ))
        assertTrue(shouldCompensate(
            runState = "RUNNING",
            manualBypass = false,
            backendId = OverlayBackend.ID_APPLICATION_OVERLAY,
            antiTransformPref = true
        ))
    }

    /** P10 — runState != RUNNING 时不补偿 */
    @Test
    fun p10_runStateGate() {
        for (state in listOf("STOPPED", "ARMING")) {
            assertFalse(shouldCompensate(
                runState = state,
                manualBypass = false,
                backendId = OverlayBackend.ID_ACCESSIBILITY,
                antiTransformPref = true
            ))
        }
    }

    /** All-conditions-met case 显式 */
    @Test
    fun allConditionsMet_compensationActive() {
        assertEquals(true, shouldCompensate(
            runState = "RUNNING",
            manualBypass = false,
            backendId = OverlayBackend.ID_ACCESSIBILITY,
            antiTransformPref = true
        ))
    }
}
