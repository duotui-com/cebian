package com.slideindex.app.widget

object WidgetGridMetrics {
    fun computeGridStepPx(innerWidthPx: Int, columnCount: Int): Int {
        if (columnCount <= 0) return 1
        return (innerWidthPx / columnCount).coerceAtLeast(1)
    }

    /**
     * 决定画布实际使用的单元边长。
     *
     * [desiredCellPx] > 0 表示用户显式设置了「单元网格宽度」。此时只有当固定宽度乘列数
     * 放不进 [innerWidthPx] 时才收缩，避免最右一列被容器裁掉（点不到、也拉不过去）；
     * 放得下就保持用户设置，不做多余缩放。
     */
    fun resolveGridStepPx(innerWidthPx: Int, columnCount: Int, desiredCellPx: Int): Int {
        if (columnCount <= 0) return 1
        if (desiredCellPx <= 0) return computeGridStepPx(innerWidthPx, columnCount)
        if (innerWidthPx <= 0) return desiredCellPx
        if (desiredCellPx * columnCount <= innerWidthPx) return desiredCellPx
        return computeGridStepPx(innerWidthPx, columnCount)
    }
}
