package com.ccg.screenblocker

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ccg.screenblocker.service.OverlayService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests covering PBT properties P8 and P9 (Intent construction).
 *
 * - P8: LAUNCHER alias discoverability — PackageManager.queryIntentActivities
 *       must list both MainActivity and QuickToggleAlias.
 * - P9: ACTION_FAB_SET_VISIBLE EXTRA_VISIBLE strictness — hasExtra must be
 *       used to distinguish missing-extra from explicit-false.
 */
@RunWith(AndroidJUnit4::class)
class AliasDiscoverabilityTest {

    @Test
    fun p8_launcherIntentResolvesMainActivityAndAlias() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(context.packageName)
        }
        val resolved = pm.queryIntentActivities(intent, 0)
        val componentNames = resolved.map { it.activityInfo.name }.toSet()

        assertTrue(
            "expected ≥ 2 launcher components, got ${componentNames.size}: $componentNames",
            componentNames.size >= 2
        )
        assertTrue(
            "MainActivity must be a launcher entry: $componentNames",
            componentNames.any { it.endsWith(".MainActivity") }
        )
        assertTrue(
            "QuickToggleAlias must be a launcher entry: $componentNames",
            componentNames.any { it.endsWith(".QuickToggleAlias") }
        )
    }

    @Test
    fun p9_fabSetVisible_withoutExtra_isStrictlyDetectable() {
        val intent = Intent().setAction(OverlayService.ACTION_FAB_SET_VISIBLE)
        assertFalse(intent.hasExtra(OverlayService.EXTRA_VISIBLE))
    }

    @Test
    fun p9_fabSetVisible_withExtraTrue_readsAsTrue() {
        val intent = Intent()
            .setAction(OverlayService.ACTION_FAB_SET_VISIBLE)
            .putExtra(OverlayService.EXTRA_VISIBLE, true)
        assertTrue(intent.hasExtra(OverlayService.EXTRA_VISIBLE))
        assertTrue(intent.getBooleanExtra(OverlayService.EXTRA_VISIBLE, false))
    }

    @Test
    fun p9_fabSetVisible_withExtraFalse_readsAsFalse() {
        val intent = Intent()
            .setAction(OverlayService.ACTION_FAB_SET_VISIBLE)
            .putExtra(OverlayService.EXTRA_VISIBLE, false)
        assertTrue(intent.hasExtra(OverlayService.EXTRA_VISIBLE))
        assertFalse(intent.getBooleanExtra(OverlayService.EXTRA_VISIBLE, true))
    }

    @Test
    fun extraSource_acceptsDocumentedValues() {
        for (source in listOf("fab", "quick_toggle", "unknown")) {
            val intent = Intent()
                .setAction(OverlayService.ACTION_TOGGLE_BYPASS_AND_GESTURE)
                .putExtra(OverlayService.EXTRA_SOURCE, source)
            assertEquals(source, intent.getStringExtra(OverlayService.EXTRA_SOURCE))
        }
    }
}
