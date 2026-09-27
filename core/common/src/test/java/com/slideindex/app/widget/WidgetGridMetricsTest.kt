package com.slideindex.app.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetGridMetricsTest {

    @Test
    fun `auto width divides available width by column count`() {
        assertEquals(
            144,
            WidgetGridMetrics.resolveGridStepPx(innerWidthPx = 720, columnCount = 5, desiredCellPx = 0),
        )
    }

    @Test
    fun `explicit cell width is kept when the grid fits`() {
        // 5 列 × 179px = 895 ≤ 961：保持用户设置，不做多余缩放。
        assertEquals(
            179,
            WidgetGridMetrics.resolveGridStepPx(innerWidthPx = 961, columnCount = 5, desiredCellPx = 179),
        )
    }

    @Test
    fun `explicit cell width shrinks instead of clipping the last column`() {
        // 5 列 × 179px = 895 > 800：收缩到 160，保证最右一列仍在容器内（点得到、拉得到）。
        assertEquals(
            160,
            WidgetGridMetrics.resolveGridStepPx(innerWidthPx = 800, columnCount = 5, desiredCellPx = 179),
        )
    }

    @Test
    fun `unknown width falls back to the explicit cell width`() {
        assertEquals(
            179,
            WidgetGridMetrics.resolveGridStepPx(innerWidthPx = 0, columnCount = 5, desiredCellPx = 179),
        )
    }

    @Test
    fun `invalid column count never returns zero`() {
        assertEquals(
            1,
            WidgetGridMetrics.resolveGridStepPx(innerWidthPx = 800, columnCount = 0, desiredCellPx = 179),
        )
    }
}
