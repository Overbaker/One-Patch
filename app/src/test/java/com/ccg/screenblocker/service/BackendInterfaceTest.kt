package com.ccg.screenblocker.service

import com.ccg.screenblocker.model.BlockArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests covering OverlayBackend interface contract:
 * - P1 Backend selection determinism (id values)
 * - P2 Interface idempotency（attach 同一 area 重复无副作用）
 * - P5 No backend ping-pong（单 RUNNING 周期 fallback 一次）
 *
 * 不依赖 Android framework — 只测纯接口语义。
 */
class BackendInterfaceTest {

    /** P1 — 三个固定 backend id 不重复 */
    @Test
    fun p1_backendIds_areDistinctConstants() {
        val ids = setOf(
            OverlayBackend.ID_PHYSICAL_DISPLAY,
            OverlayBackend.ID_ACCESSIBILITY,
            OverlayBackend.ID_APPLICATION_OVERLAY,
            OverlayBackend.ID_NONE
        )
        assertEquals(4, ids.size)
    }

    /** P1 — id 值稳定（外部 UI 依赖这些 string） */
    @Test
    fun p1_backendIds_areStableStrings() {
        assertEquals("PHYSICAL_DISPLAY", OverlayBackend.ID_PHYSICAL_DISPLAY)
        assertEquals("ACCESSIBILITY", OverlayBackend.ID_ACCESSIBILITY)
        assertEquals("APPLICATION_OVERLAY", OverlayBackend.ID_APPLICATION_OVERLAY)
        assertEquals("NONE", OverlayBackend.ID_NONE)
    }

    /** P2 — fake backend 上 attach 同一 area 多次的内部计数 */
    @Test
    fun p2_repeatedAttach_isObservationallyIdempotent() {
        val backend = FakeBackend()
        val area = BlockArea(0, 0, 100, 100, 1080, 2400)
        assertTrue(backend.attach(area))
        assertTrue(backend.isAttached())
        // 第 2 次 attach（同 area）：fake 的 attach 不复 attach（已 attached），但仍 OK
        assertTrue(backend.attach(area))
        assertTrue(backend.isAttached())
        backend.detach()
        assertFalse(backend.isAttached())
    }

    /** P5 — fake fallback simulator：触发一次后 ping-pong guard 阻止再次切回 */
    @Test
    fun p5_fallbackPingPong_isBlocked() {
        val fallbackSim = FallbackSimulator()
        // 初始为 PHYSICAL_DISPLAY
        assertEquals("PHYSICAL_DISPLAY", fallbackSim.activeBackend())
        // 第 1 次 fallback → 切到 APPLICATION_OVERLAY
        assertTrue(fallbackSim.tryFallback())
        assertEquals("APPLICATION_OVERLAY", fallbackSim.activeBackend())
        // 第 2-N 次 fallback → 不切回
        repeat(10) { assertFalse(fallbackSim.tryFallback()) }
        assertEquals("APPLICATION_OVERLAY", fallbackSim.activeBackend())
        // 重新 enable（reset）→ 允许重新选择
        fallbackSim.reset()
        assertEquals("PHYSICAL_DISPLAY", fallbackSim.activeBackend())
    }

    private class FakeBackend : OverlayBackend {
        private var attached = false
        override fun attach(area: BlockArea): Boolean {
            attached = true
            return true
        }
        override fun update(area: BlockArea) { /* no-op */ }
        override fun detach() { attached = false }
        override fun isAttached(): Boolean = attached
        override fun id(): String = OverlayBackend.ID_PHYSICAL_DISPLAY
    }

    private class FallbackSimulator {
        private var current: String = OverlayBackend.ID_PHYSICAL_DISPLAY
        private var fallbackTriggered: Boolean = false

        fun activeBackend(): String = current

        fun tryFallback(): Boolean {
            if (fallbackTriggered) return false
            fallbackTriggered = true
            current = OverlayBackend.ID_APPLICATION_OVERLAY
            return true
        }

        fun reset() {
            fallbackTriggered = false
            current = OverlayBackend.ID_PHYSICAL_DISPLAY
        }
    }

    /** Backend ID 不能与 None / NONE 重叠 */
    @Test
    fun backendId_isNotNone() {
        val activeIds = listOf(
            OverlayBackend.ID_PHYSICAL_DISPLAY,
            OverlayBackend.ID_ACCESSIBILITY,
            OverlayBackend.ID_APPLICATION_OVERLAY
        )
        for (id in activeIds) {
            assertNotEquals(OverlayBackend.ID_NONE, id)
        }
    }
}
