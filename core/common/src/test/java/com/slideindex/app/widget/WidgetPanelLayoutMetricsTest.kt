package com.slideindex.app.widget

import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetPanelLayoutMetricsTest {

    /** MEIZU 21 实测参数：1080×2340 / density 440。 */
    private val density = 2.75f
    private val screenWidthPx = 1080

    private fun page(columnCount: Int, items: List<WidgetPanelItem> = listOf(sampleItem())) =
        WidgetPanelPage(
            id = 2,
            columnCount = columnCount,
            visibleRowCount = 7,
            cellWidthDp = 65,
            marginTopDp = 135,
            items = items,
        )

    private fun sampleItem() = WidgetPanelItem(
        appWidgetId = -1,
        x = 0,
        y = 0,
        spanX = 1,
        spanY = 1,
        itemType = ITEM_TYPE_APP,
        packageName = "com.example",
        className = "com.example.Main",
    )

    @Test
    fun `panel box width follows the page column count`() {
        val five = WidgetPanelLayoutMetrics.computePanelBox(screenWidthPx, page(5), density, pageCount = 1)
        val four = WidgetPanelLayoutMetrics.computePanelBox(screenWidthPx, page(4), density, pageCount = 1)

        // step = round(65 × 2.75) = 179；内边距 12dp×2 + 4dp×2 = 66 + 22 = 88。
        assertEquals(179 * 5 + 88, five.widthPx)
        assertEquals(179 * 4 + 88, four.widthPx)
        assertTrue(
            "5 列的面板必须比 4 列宽，否则切页后会裁掉最右一列",
            five.widthPx > four.widthPx,
        )
    }

    @Test
    fun `panel box height adds indicator and empty hint only when needed`() {
        val padding = (12f * density).roundToInt() * 2
        val viewport = 7 * 179 + (4f * density).roundToInt() * 2

        val single = WidgetPanelLayoutMetrics.computePanelBox(screenWidthPx, page(5), density, pageCount = 1)
        val multi = WidgetPanelLayoutMetrics.computePanelBox(screenWidthPx, page(5), density, pageCount = 3)
        val empty = WidgetPanelLayoutMetrics.computePanelBox(
            screenWidthPx,
            page(5, items = emptyList()),
            density,
            pageCount = 1,
        )

        assertEquals(padding + viewport, single.heightPx)
        assertEquals(padding + viewport + (14f * density).roundToInt(), multi.heightPx)
        assertEquals(padding + viewport + (20f * density).roundToInt(), empty.heightPx)
    }

    @Test
    fun `panel box top margin follows the page setting`() {
        val box = WidgetPanelLayoutMetrics.computePanelBox(screenWidthPx, page(5), density, pageCount = 1)
        assertEquals((135f * density).roundToInt(), box.topMarginPx)
    }
}
