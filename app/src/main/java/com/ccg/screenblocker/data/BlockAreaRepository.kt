package com.ccg.screenblocker.data

import com.ccg.screenblocker.model.BlockArea

/**
 * 屏蔽区域配置的持久化接口。
 */
interface BlockAreaRepository {
    /**
     * 加载已保存的矩形；若不存在或无效，返回当前显示尺寸下的默认矩形。
     */
    fun loadOrDefault(displayWidthPx: Int, displayHeightPx: Int): BlockArea

    /**
     * 保存矩形配置。
     */
    fun save(area: BlockArea)

    /**
     * 清除已保存的配置。
     */
    fun clear()
}
