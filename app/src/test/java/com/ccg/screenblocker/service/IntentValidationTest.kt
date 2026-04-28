package com.ccg.screenblocker.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 纯 JVM 测试覆盖 PBT 性质 P9 (EXTRA_VISIBLE 严格性) + P7 (no implicit enable)。
 *
 * Intent 构造性测试在 androidTest（需要 Android runtime）中完成；本测试只覆盖
 * 可纯 JVM 验证的契约：常量字符串、字段去除、与 ACTION_ENABLE 区分。
 */
class IntentValidationTest {

    /** P7 — ACTION_TOGGLE_BYPASS_AND_GESTURE 不等于 ACTION_ENABLE / ACTION_STOP */
    @Test
    fun p7_toggleAction_isDistinctFromEnable() {
        assertNotEquals(OverlayService.ACTION_TOGGLE_BYPASS_AND_GESTURE, OverlayService.ACTION_ENABLE)
        assertNotEquals(OverlayService.ACTION_TOGGLE_BYPASS_AND_GESTURE, OverlayService.ACTION_STOP)
    }

    /** 契约：legacy ACTION_TOGGLE_BYPASS / ACTION_FAB_TOGGLE_VISIBILITY 必须从公开 API 移除 */
    @Test
    fun legacyActions_areRemovedFromPublicApi() {
        val publicConstants = OverlayService::class.java.declaredFields
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .mapNotNull { it.name }
            .toSet()
        assertFalse(
            "ACTION_TOGGLE_BYPASS must not be re-introduced",
            publicConstants.contains("ACTION_TOGGLE_BYPASS")
        )
        assertFalse(
            "ACTION_FAB_TOGGLE_VISIBILITY must not be re-introduced",
            publicConstants.contains("ACTION_FAB_TOGGLE_VISIBILITY")
        )
    }

    /** Action / extra 字符串保持稳定：外部绑定（敲击背部、桌面快捷方式）依赖完整字符串 */
    @Test
    fun actionStrings_areStable() {
        assertEquals(
            "com.ccg.screenblocker.action.TOGGLE_BYPASS_AND_GESTURE",
            OverlayService.ACTION_TOGGLE_BYPASS_AND_GESTURE
        )
        assertEquals(
            "com.ccg.screenblocker.action.FAB_SET_VISIBLE",
            OverlayService.ACTION_FAB_SET_VISIBLE
        )
        assertEquals(
            "com.ccg.screenblocker.extra.VISIBLE",
            OverlayService.EXTRA_VISIBLE
        )
        assertEquals(
            "com.ccg.screenblocker.extra.SOURCE",
            OverlayService.EXTRA_SOURCE
        )
    }
}
