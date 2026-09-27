package com.slideindex.app.widget

import android.view.View
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 面板「少一列」回归测试：
 * 画布曾经会在「条目和几何同一次变化」时吞掉列数，且因为 bindKey 已推进而永不重试，
 * 直到发生一次整体重绑（切页/重进）才恢复正常。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class WidgetCanvasLayoutGeometryTest {

    private val context get() = RuntimeEnvironment.getApplication()

    private fun app(id: Int, x: Int, y: Int, spanX: Int = 1, spanY: Int = 1) = WidgetPanelItem(
        appWidgetId = id,
        x = x,
        y = y,
        spanX = spanX,
        spanY = spanY,
        itemType = ITEM_TYPE_APP,
        packageName = "pkg.$id",
        className = "pkg.$id.Main",
    )

    private fun page(
        columnCount: Int,
        items: List<WidgetPanelItem>,
        id: Long = 2,
        cellWidthDp: Int = 65,
    ) = WidgetPanelPage(
        id = id,
        columnCount = columnCount,
        visibleRowCount = 7,
        cellWidthDp = cellWidthDp,
        items = items,
    )

    @Test
    fun `items signature ignores list order`() {
        val first = page(5, listOf(app(1, 0, 0), app(2, 1, 0)))
        val reordered = page(5, listOf(app(2, 1, 0), app(1, 0, 0)))

        assertEquals(
            WidgetCanvasLayoutGeometry.itemsSignature(first),
            WidgetCanvasLayoutGeometry.itemsSignature(reordered),
        )
    }

    @Test
    fun `plan rebinds everything when the page id changes`() {
        val previous = page(4, listOf(app(7, 0, 0)))
        val next = page(4, listOf(app(7, 0, 0)), id = 3)

        assertEquals(
            CanvasBindPlan.FullRebind,
            WidgetCanvasLayoutGeometry.planBind(WidgetCanvasLayoutGeometry.bindKeyFor(previous), next),
        )
    }

    @Test
    fun `plan does nothing when the page is unchanged`() {
        val current = page(5, listOf(app(7, 0, 0)))

        assertEquals(
            CanvasBindPlan.None,
            WidgetCanvasLayoutGeometry.planBind(WidgetCanvasLayoutGeometry.bindKeyFor(current), current),
        )
    }

    @Test
    fun `plan applies geometry when only the column count moved`() {
        val previous = page(4, listOf(app(7, 0, 0)))
        val next = page(5, listOf(app(7, 0, 0)))

        assertEquals(
            CanvasBindPlan.ApplyGeometry,
            WidgetCanvasLayoutGeometry.planBind(WidgetCanvasLayoutGeometry.bindKeyFor(previous), next),
        )
    }

    @Test
    fun `added items and new column count both land on the canvas`() {
        val layout = WidgetCanvasLayout(context)
        layout.bindIfNeeded(page(4, listOf(app(7, 0, 0))), context)
        assertEquals(4, layout.pageColumnCount)

        // 同一次下发里条目变了、列数也变了：修复前列数会被吞掉，网格只画 4 列。
        layout.bindIfNeeded(page(5, listOf(app(7, 0, 0), app(8, 1, 0))), context)

        assertEquals(5, layout.pageColumnCount)
        assertEquals(5, layout.currentPage?.columnCount)
    }

    @Test
    fun `slider geometry wins while a just committed local resize is kept`() {
        val layout = WidgetCanvasLayout(context)
        layout.bindIfNeeded(page(4, listOf(app(7, 0, 0, spanX = 4, spanY = 2))), context)
        assertEquals(4, layout.pageColumnCount)

        // canvas 本地刚把卡片拉到 4 格宽；此时设置页把列数滑到 5。
        // 条目按本地保留，但列数必须立刻跟着设置走（修复前会被静默丢弃）。
        layout.bindIfNeeded(page(5, listOf(app(7, 0, 0, spanX = 5, spanY = 2))), context)

        assertEquals(5, layout.pageColumnCount)
        assertEquals(4, layout.currentPage?.items?.first()?.spanX)
    }

    @Test
    fun `shrinking the column count also clamps the kept local items`() {
        val layout = WidgetCanvasLayout(context)
        layout.bindIfNeeded(page(5, listOf(app(7, 0, 0, spanX = 5, spanY = 2))), context)
        assertEquals(5, layout.pageColumnCount)

        layout.bindIfNeeded(page(4, listOf(app(7, 0, 0, spanX = 4, spanY = 2))), context)

        assertEquals(4, layout.pageColumnCount)
        assertEquals(4, layout.currentPage?.items?.first()?.spanX)
    }

    @Test
    fun `measure keeps the fixed cell width when the grid fits`() {
        val layout = WidgetCanvasLayout(context)
        layout.bindIfNeeded(page(5, listOf(app(7, 0, 0))), context)
        val desiredCellPx = (65f * context.resources.displayMetrics.density).roundToInt()

        layout.measure(
            View.MeasureSpec.makeMeasureSpec(desiredCellPx * 5 + 24, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY),
        )

        assertEquals(desiredCellPx, layout.gridStepPx)
    }

    @Test
    fun `measure shrinks instead of clipping the last column`() {
        val layout = WidgetCanvasLayout(context)
        layout.bindIfNeeded(page(5, listOf(app(7, 0, 0))), context)
        val desiredCellPx = (65f * context.resources.displayMetrics.density).roundToInt()
        val widthPx = desiredCellPx * 5 - 1

        layout.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY),
        )

        assertTrue("固定宽度放不下时必须收缩，而不是让最右一列溢出被裁", layout.gridStepPx < desiredCellPx)
        assertTrue("5 列必须全部落在容器宽度内", layout.gridStepPx * 5 <= widthPx)
    }
}
