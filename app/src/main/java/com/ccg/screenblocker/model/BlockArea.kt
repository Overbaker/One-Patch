package com.ccg.screenblocker.model

/**
 * 屏蔽区域数据模型，所有坐标使用屏幕物理像素 (px)。
 *
 * - leftPx/topPx 相对屏幕左上角
 * - savedDisplayWidthPx/savedDisplayHeightPx 用于跨设备/跨分辨率重新缩放
 */
data class BlockArea(
    val leftPx: Int,
    val topPx: Int,
    val widthPx: Int,
    val heightPx: Int,
    val savedDisplayWidthPx: Int,
    val savedDisplayHeightPx: Int
) {
    /** 矩形右边界 */
    val rightPx: Int get() = leftPx + widthPx

    /** 矩形下边界 */
    val bottomPx: Int get() = topPx + heightPx

    /**
     * 将矩形限制在合法范围内：
     * - 不超出 [0, displayWidthPx] x [0, displayHeightPx]
     * - 宽高不超过 displayWidth/Height - 2*marginPx
     * - 宽高不小于 minSizePx
     */
    fun clamp(
        displayWidthPx: Int,
        displayHeightPx: Int,
        marginPx: Int,
        minSizePx: Int
    ): BlockArea {
        val maxWidth = (displayWidthPx - marginPx).coerceAtLeast(minSizePx)
        val maxHeight = (displayHeightPx - marginPx).coerceAtLeast(minSizePx)

        var w = widthPx.coerceIn(minSizePx, maxWidth)
        var h = heightPx.coerceIn(minSizePx, maxHeight)
        var l = leftPx.coerceIn(0, (displayWidthPx - w).coerceAtLeast(0))
        var t = topPx.coerceIn(0, (displayHeightPx - h).coerceAtLeast(0))

        // 二次校验（极端尺寸下兜底）
        if (l + w > displayWidthPx) {
            w = displayWidthPx - l
        }
        if (t + h > displayHeightPx) {
            h = displayHeightPx - t
        }

        return copy(
            leftPx = l,
            topPx = t,
            widthPx = w,
            heightPx = h,
            savedDisplayWidthPx = displayWidthPx,
            savedDisplayHeightPx = displayHeightPx
        )
    }

    /**
     * 当屏幕分辨率与上次保存时不同，按比例换算坐标，避免错位。
     */
    fun rescaleIfNeeded(newDisplayWidthPx: Int, newDisplayHeightPx: Int): BlockArea {
        if (newDisplayWidthPx == savedDisplayWidthPx &&
            newDisplayHeightPx == savedDisplayHeightPx
        ) {
            return this
        }
        if (savedDisplayWidthPx <= 0 || savedDisplayHeightPx <= 0) {
            return copy(
                savedDisplayWidthPx = newDisplayWidthPx,
                savedDisplayHeightPx = newDisplayHeightPx
            )
        }

        val sx = newDisplayWidthPx.toFloat() / savedDisplayWidthPx.toFloat()
        val sy = newDisplayHeightPx.toFloat() / savedDisplayHeightPx.toFloat()

        return BlockArea(
            leftPx = (leftPx * sx).toInt(),
            topPx = (topPx * sy).toInt(),
            widthPx = (widthPx * sx).toInt(),
            heightPx = (heightPx * sy).toInt(),
            savedDisplayWidthPx = newDisplayWidthPx,
            savedDisplayHeightPx = newDisplayHeightPx
        )
    }

    /** 检查矩形是否合法（最小尺寸） */
    fun isValid(minSizePx: Int): Boolean = widthPx >= minSizePx && heightPx >= minSizePx

    companion object {
        /**
         * 创建默认矩形（屏幕中心、约 60%x20% 大小）
         */
        fun default(displayWidthPx: Int, displayHeightPx: Int): BlockArea {
            val w = (displayWidthPx * 0.6f).toInt().coerceAtLeast(360)
            val h = (displayHeightPx * 0.2f).toInt().coerceAtLeast(200)
            val l = (displayWidthPx - w) / 2
            val t = (displayHeightPx - h) / 2
            return BlockArea(
                leftPx = l,
                topPx = t,
                widthPx = w,
                heightPx = h,
                savedDisplayWidthPx = displayWidthPx,
                savedDisplayHeightPx = displayHeightPx
            )
        }
    }
}
