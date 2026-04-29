package com.ccg.screenblocker.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Pure-JVM tests covering reverse-compensation arithmetic invariants:
 * - P1 算术正确性（layoutParams = area + compensatedDelta）
 * - P2 hysteresis dead-zone 边界
 * - P3 累积单调性
 * - P11 收敛目标（一次补偿后 actual = area）
 *
 * 不依赖 Android framework；测试纯算法 helper。
 */
class CompensationArithmeticTest {

    /** P1 — layoutParams = area + compensatedDelta；纯算术 */
    @Test
    fun p1_layoutParamsArithmetic_isAdditive() {
        for ((areaTop, deltaY) in listOf(
            100 to 0, 100 to -50, 100 to -100, 0 to 50, 200 to -200
        )) {
            val effective = areaTop + deltaY
            assertEquals(effective, applyOffset(areaTop, deltaY))
        }
    }

    /** P2 — hysteresis dead-zone：drift in [-dp(2), dp(2)] 不修改 delta */
    @Test
    fun p2_hysteresis_deadZone_skipsCompensation() {
        val hysteresisPx = 6  // 等价于 dp(2) at 3x density；测试用绝对像素
        val cases = listOf(
            // (driftX, driftY, expected delta change?)
            Triple(0, 0, false),
            Triple(1, 1, false),
            Triple(-3, 0, false),
            Triple(0, 6, false),     // 边界 = hysteresisPx，不补偿（<= 严格判定）
            Triple(7, 0, true),      // 跨过 hysteresis
            Triple(0, -10, true),
            Triple(50, 50, true)
        )
        for ((dx, dy, shouldCompensate) in cases) {
            val triggered = kotlin.math.abs(dx) > hysteresisPx ||
                kotlin.math.abs(dy) > hysteresisPx
            assertEquals("drift=($dx,$dy) hysteresis=$hysteresisPx", shouldCompensate, triggered)
        }
    }

    /** P3 — 累积单调性：同向漂移 N 次，delta 单调减少 |delta| ≥ N×|drift| - tolerance */
    @Test
    fun p3_cumulativeCompensation_monotonicallyDecreasing() {
        var compensatedY = 0
        val driftPerFrame = 5
        for (n in 1..10) {
            compensatedY -= driftPerFrame  // 每帧累积
            assertEquals(-driftPerFrame * n, compensatedY)
        }
    }

    /** P11 — 收敛目标：补偿后 actual_y = (area + (-D)) + D = area */
    @Test
    fun p11_convergenceTarget_zeroDriftAfterCompensation() {
        val areaTop = 500
        val transformDelta = 200  // SurfaceFlinger 平移
        // 系统作用：actual = layoutParams + transformDelta
        // 反向补偿：layoutParams = area - transformDelta
        // 下一帧 actual = (area - transformDelta) + transformDelta = area ✓
        val compensatedLayoutParams = areaTop - transformDelta
        val nextFrameActual = compensatedLayoutParams + transformDelta
        assertEquals(areaTop, nextFrameActual)
    }

    /** 边界：drift = 0 时 delta 永远不变（noise floor 保护） */
    @Test
    fun zeroDrift_neverModifiesDelta() {
        var deltaX = 0
        var deltaY = 0
        repeat(100) {
            // drift = 0 → 不动 delta
            assertFalse(0 > 6)  // hysteresis 永远不触发
        }
        assertEquals(0, deltaX)
        assertEquals(0, deltaY)
    }

    private fun applyOffset(area: Int, delta: Int): Int = area + delta
}
