package com.ccg.screenblocker.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Static-analysis tests covering P13 (signal source uniqueness) and P16 (no reflection).
 *
 * 验证 OverlayService.detectDisplacementAndBypass 函数体内：
 * - 仅引用 getLocationOnScreen / 既有 area / compensatedDelta / displaceDetector
 * - 不引用 Configuration / WindowInsets / DisplayManager / ContentObserver / Settings.System / View.matrix / @hide 反射
 */
class DetectionSignalSourceTest {

    private val overlayServiceFile by lazy {
        File("src/main/java/com/ccg/screenblocker/service/OverlayService.kt")
            .takeIf { it.exists() }
            ?: File("../app/src/main/java/com/ccg/screenblocker/service/OverlayService.kt")
                .takeIf { it.exists() }
            ?: File("app/src/main/java/com/ccg/screenblocker/service/OverlayService.kt")
    }

    private fun extractDetectorBody(): String {
        val source = overlayServiceFile.readText()
        val startMarker = "private fun detectDisplacementAndBypass()"
        val startIdx = source.indexOf(startMarker)
        check(startIdx >= 0) { "detectDisplacementAndBypass function not found" }
        // 简单的匹配花括号深度
        var depth = 0
        var i = source.indexOf('{', startIdx)
        check(i > 0) { "function body brace not found" }
        val bodyStart = i
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(bodyStart, i + 1)
                }
            }
            i++
        }
        error("function body not balanced")
    }

    /** P13 — 函数体不引用 Configuration / WindowInsets / DisplayManager / Settings / matrix */
    @Test
    fun p13_signalSourceUniqueness_noForbiddenApis() {
        val body = extractDetectorBody()
        val forbidden = listOf(
            "Configuration",
            "onApplyWindowInsetsListener",
            "DisplayManager",
            "ContentObserver",
            "Settings.System.getInt",
            "getMatrix(",
            "getRootWindowInsets"
        )
        for (token in forbidden) {
            assertFalse(
                "detectDisplacementAndBypass body must not reference '$token'",
                body.contains(token)
            )
        }
    }

    /** P13 — 函数体确实使用 getLocationOnScreen */
    @Test
    fun p13_detectorUsesLocationOnScreen() {
        val body = extractDetectorBody()
        assertTrue(body.contains("getLocationOnScreen"))
    }

    /** P16 — 项目源码 0 命中反射 / @hide 字段访问 */
    @Test
    fun p16_noReflectionOrHiddenApiAccess() {
        val sourceRoot = File("src/main/java/com/ccg/screenblocker")
            .takeIf { it.exists() }
            ?: File("../app/src/main/java/com/ccg/screenblocker")
                .takeIf { it.exists() }
            ?: File("app/src/main/java/com/ccg/screenblocker")
        val forbidden = listOf(
            "mTranslator",
            "mInvCompatScale",
            "Class.forName(\"android.view.ViewRootImpl",
            "getDeclaredField(\"mTranslator",
            "getDeclaredField(\"mInvCompatScale"
        )
        sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val content = file.readText()
                for (token in forbidden) {
                    assertFalse(
                        "Forbidden token '$token' found in ${file.name}",
                        content.contains(token)
                    )
                }
            }
    }
}
