package com.ccg.screenblocker.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM tests covering compensation state lifecycle:
 * - P4 reset on stop
 * - P5 reset on config change
 * - P10 RunState gate
 *
 * Uses a fake state machine stub to model OverlayService's compensatedDeltaX/Y reset semantics.
 */
class CompensationStateLifecycleTest {

    private class FakeCompensationStateMachine {
        var compensatedDeltaX: Int = 0
        var compensatedDeltaY: Int = 0
        var runState: String = "STOPPED"

        fun freshAttach(areaLeft: Int, areaTop: Int) {
            runState = "RUNNING"
            compensatedDeltaX = 0
            compensatedDeltaY = 0
        }

        fun handleStop() {
            runState = "STOPPED"
            compensatedDeltaX = 0
            compensatedDeltaY = 0
        }

        fun configChange(newAreaLeft: Int, newAreaTop: Int) {
            // 重置补偿量
            compensatedDeltaX = 0
            compensatedDeltaY = 0
        }

        fun applyDrift(driftX: Int, driftY: Int) {
            if (runState != "RUNNING") return  // RunState gate
            compensatedDeltaX -= driftX
            compensatedDeltaY -= driftY
        }
    }

    /** P4 — handleStop 后 compensated = 0 */
    @Test
    fun p4_handleStop_resetsCompensation() {
        val sm = FakeCompensationStateMachine()
        sm.freshAttach(100, 200)
        sm.applyDrift(50, 100)
        assertEquals(-50, sm.compensatedDeltaX)
        assertEquals(-100, sm.compensatedDeltaY)
        sm.handleStop()
        assertEquals(0, sm.compensatedDeltaX)
        assertEquals(0, sm.compensatedDeltaY)
    }

    /** P5 — config change 后 compensated = 0 */
    @Test
    fun p5_configChange_resetsCompensation() {
        val sm = FakeCompensationStateMachine()
        sm.freshAttach(100, 200)
        sm.applyDrift(30, -40)
        assertEquals(-30, sm.compensatedDeltaX)
        assertEquals(40, sm.compensatedDeltaY)
        sm.configChange(150, 250)  // 新 area
        assertEquals(0, sm.compensatedDeltaX)
        assertEquals(0, sm.compensatedDeltaY)
    }

    /** P10 — runState != RUNNING 时 detector 跳过 */
    @Test
    fun p10_runStateGate_blocksCompensationWhenNotRunning() {
        val sm = FakeCompensationStateMachine()
        // STOPPED 状态：drift 不触发补偿
        sm.applyDrift(100, 200)
        assertEquals(0, sm.compensatedDeltaX)
        assertEquals(0, sm.compensatedDeltaY)
    }

    /** Fresh attach 后 compensated 显式归零（P4 镜像） */
    @Test
    fun freshAttach_startsFromZero() {
        val sm = FakeCompensationStateMachine()
        sm.compensatedDeltaX = -999  // 模拟脏状态
        sm.compensatedDeltaY = -888
        sm.freshAttach(0, 0)
        assertEquals(0, sm.compensatedDeltaX)
        assertEquals(0, sm.compensatedDeltaY)
    }
}
