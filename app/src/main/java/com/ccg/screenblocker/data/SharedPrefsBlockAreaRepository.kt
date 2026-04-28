package com.ccg.screenblocker.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.ccg.screenblocker.model.BlockArea

/**
 * 基于 SharedPreferences 的实现。
 */
class SharedPrefsBlockAreaRepository(context: Context) : BlockAreaRepository {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun loadOrDefault(displayWidthPx: Int, displayHeightPx: Int): BlockArea {
        val schemaVersion = prefs.getInt(KEY_SCHEMA_VERSION, 0)
        if (schemaVersion < SCHEMA_VERSION) {
            return BlockArea.default(displayWidthPx, displayHeightPx)
        }
        val w = prefs.getInt(KEY_WIDTH, -1)
        val h = prefs.getInt(KEY_HEIGHT, -1)
        if (w <= 0 || h <= 0) {
            return BlockArea.default(displayWidthPx, displayHeightPx)
        }
        return BlockArea(
            leftPx = prefs.getInt(KEY_LEFT, 0),
            topPx = prefs.getInt(KEY_TOP, 0),
            widthPx = w,
            heightPx = h,
            savedDisplayWidthPx = prefs.getInt(KEY_SAVED_DISPLAY_W, displayWidthPx),
            savedDisplayHeightPx = prefs.getInt(KEY_SAVED_DISPLAY_H, displayHeightPx)
        )
    }

    override fun save(area: BlockArea) {
        prefs.edit {
            putInt(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
            putInt(KEY_LEFT, area.leftPx)
            putInt(KEY_TOP, area.topPx)
            putInt(KEY_WIDTH, area.widthPx)
            putInt(KEY_HEIGHT, area.heightPx)
            putInt(KEY_SAVED_DISPLAY_W, area.savedDisplayWidthPx)
            putInt(KEY_SAVED_DISPLAY_H, area.savedDisplayHeightPx)
        }
    }

    override fun clear() {
        prefs.edit { clear() }
    }

    companion object {
        private const val PREFS_NAME = "block_area_prefs"
        private const val SCHEMA_VERSION = 1

        private const val KEY_SCHEMA_VERSION = "block_area_schema_version"
        private const val KEY_LEFT = "block_area_left_px"
        private const val KEY_TOP = "block_area_top_px"
        private const val KEY_WIDTH = "block_area_width_px"
        private const val KEY_HEIGHT = "block_area_height_px"
        private const val KEY_SAVED_DISPLAY_W = "block_area_saved_display_width_px"
        private const val KEY_SAVED_DISPLAY_H = "block_area_saved_display_height_px"
    }
}
