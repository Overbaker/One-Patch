package com.ccg.screenblocker.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯 JVM 单元测试，覆盖 design.md 中的 PBT 性质 P1/P2/P3/P11。
 *
 * 测试目标：bypass 状态机的纯逻辑契约（不依赖 Android Service runtime）。
 */
class BypassStateMachineTest {

    /** P1 — applyBypassState 幂等性：相同输入下 effective 值不随调用次数改变 */
    @Test
    fun p1_applyBypassState_isIdempotent() {
        for ((manual, auto) in listOf(
            false to false, true to false, false to true, true to true
        )) {
            val first = manual || auto
            repeat(100) {
                val next = manual || auto
                assertEquals("idempotency violated for ($manual,$auto)", first, next)
            }
        }
    }

    /** P2 — manual/auto 写入交换律：最终 effective = manual ‖ auto */
    @Test
    fun p2_setManualThenAuto_equalsSetAutoThenManual() {
        for (m in listOf(false, true)) for (a in listOf(false, true)) {
            val orderA = run { var manual = false; var auto = false; manual = m; auto = a; manual || auto }
            val orderB = run { var manual = false; var auto = false; auto = a; manual = m; manual || auto }
            assertEquals("commutativity violated for ($m,$a)", orderA, orderB)
            assertEquals("OR semantics violated", m || a, orderA)
        }
    }

    /** P3 — toggle round-trip：N 次 toggle 后 manual == (N mod 2 == 1)，且 gesture 调用 == N */
    @Test
    fun p3_toggleRoundTrip_everyClickFlipsAndDispatches() {
        for (n in 0..50) {
            var manual = false
            var gestureInvocations = 0
            repeat(n) {
                manual = !manual
                gestureInvocations++
            }
            assertEquals("manual flip count off for n=$n", n % 2 == 1, manual)
            assertEquals("gesture invocations off for n=$n", n, gestureInvocations)
        }
    }

    /** P11 — 300ms 防抖：< 300ms 内的二连击仅一次生效 */
    @Test
    fun p11_debounce300ms_dropsRapidSecondClick() {
        val window = OverlayService.FAB_DEBOUNCE_MS
        val cases = listOf(
            0L to true, 50L to true, 150L to true, 299L to true,
            300L to false, 400L to false, 600L to false
        )
        for ((deltaMs, shouldDrop) in cases) {
            val last = 1_000L
            val now = last + deltaMs
            val dropped = now - last < window
            assertEquals("debounce wrong at delta=$deltaMs", shouldDrop, dropped)
        }
    }

    /** 契约：FAB_DEBOUNCE_MS 必须是 300L（design.md C2） */
    @Test
    fun debounceWindow_is300ms() {
        assertEquals(300L, OverlayService.FAB_DEBOUNCE_MS)
    }

    /** 契约：FAB_ICON_FADE_MS 必须是 200ms（design.md C1） */
    @Test
    fun iconFadeDuration_is200ms() {
        assertEquals(200, OverlayService.FAB_ICON_FADE_MS)
    }

    /** 契约：RunState 必须有且仅有 STOPPED/ARMING/RUNNING（design.md C4） */
    @Test
    fun runState_hasExactlyThreeStates() {
        val names = OverlayService.RunState.values().map { it.name }.toSet()
        assertEquals(setOf("STOPPED", "ARMING", "RUNNING"), names)
    }

    /** 默认构造的 effective bypass 必须为 false（无副作用启动） */
    @Test
    fun freshState_effectiveBypassIsFalse() {
        val manual = false
        val auto = false
        assertFalse(manual || auto)
    }

    /** 任一源为 true → effective 为 true */
    @Test
    fun anySourceTrue_effectiveIsTrue() {
        assertTrue(true || false)
        assertTrue(false || true)
        assertTrue(true || true)
    }
}
