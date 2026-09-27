package com.slideindex.app.widget

import android.content.Context
import com.slideindex.app.widget.WidgetCanvasLayout.BindKey

internal object WidgetCanvasLayoutGeometry {
    fun bindKeyFor(page: WidgetPanelPage): BindKey = BindKey(
        pageId = page.id,
        itemsSignature = itemsSignature(page),
        columnCount = page.columnCount,
        rowCount = page.rowCount,
    )

    /**
     * 条目签名。**与顺序无关**：子 View 是按 appWidgetId 匹配的，条目在列表里的先后
     * 只影响 z 序，不代表内容变化；早期按原顺序拼串会让「本地刚提交的编辑」与
     * 「外部回灌的同一批条目」签名字面不同，从而把画布永久钉在旧页面上。
     */
    fun itemsSignature(page: WidgetPanelPage): String =
        page.items
            .sortedBy { it.appWidgetId }
            .joinToString("|") {
                "${it.appWidgetId}:${it.x},${it.y},${it.spanX},${it.spanY}"
            }

    fun syncItemsFromPage(layout: WidgetCanvasLayout, page: WidgetPanelPage) {
        for (i in 0 until layout.childCount) {
            val child = layout.getChildAt(i) as? WidgetCardContainer ?: continue
            val updated = page.items.find { it.appWidgetId == child.item.appWidgetId } ?: continue
            if (child.item == updated) continue
            child.syncItem(updated)
            if (!child.isPreviewingResize()) {
                child.post { child.refreshWidgetLayout(force = true) }
            }
        }
    }

    fun applyPageGeometry(layout: WidgetCanvasLayout, page: WidgetPanelPage) {
        layout.canvasPage = page
        layout.pageColumnCount = page.columnCount
        layout.syncDynamicRowCount()
        syncItemsFromPage(layout, page)
        layout.requestLayout()
        layout.invalidate()
        layout.post { layout.refreshAllWidgetLayouts() }
    }

    fun applyPageItems(layout: WidgetCanvasLayout, page: WidgetPanelPage, hostContext: Context) {
        layout.canvasPage = page
        // 几何必须跟着一起应用：本分支只换子 View，若漏掉列数/行数，
        // 画布会停在旧几何（少画一列网格、拉伸到不了那一列），且因为 bindKey 已推进不再重试。
        layout.pageColumnCount = page.columnCount
        var structureChanged = false
        for (i in layout.childCount - 1 downTo 0) {
            val child = layout.getChildAt(i) as? WidgetCardContainer ?: continue
            if (page.items.none { it.appWidgetId == child.item.appWidgetId }) {
                layout.removeViewAt(i)
                structureChanged = true
            }
        }
        syncItemsFromPage(layout, page)
        for (item in page.items) {
            if (layout.findChildByWidgetId(item.appWidgetId) == null) {
                layout.addWidgetCard(hostContext, item)
                structureChanged = true
            }
        }
        layout.syncDynamicRowCount()
        layout.requestLayout()
        layout.invalidate()
        if (structureChanged) {
            layout.post { layout.refreshAllWidgetLayouts() }
        }
    }

    fun resolvePageForBind(layout: WidgetCanvasLayout, incoming: WidgetPanelPage): WidgetPanelPage {
        val local = layout.canvasPage ?: return incoming
        if (local.id != incoming.id) return incoming
        if (itemsSignature(local) == itemsSignature(incoming)) return incoming

        val incomingIds = incoming.items.map { it.appWidgetId }.toSet()
        val localIds = local.items.map { it.appWidgetId }.toSet()
        if (incomingIds != localIds) {
            return incoming
        }
        // 同一批小组件：canvas 可能比 Compose 状态更早（刚提交的拖拽/缩放），条目以本地为准；
        // 但几何（列数/行数/单元宽度/边距）永远以传入页面为准，否则设置页的滑条会被静默丢弃。
        // 列数变窄时本地条目要跟着收敛，避免留下超出网格的宽卡片。
        return WidgetPanelGridLogic.fitPageToGrid(
            local.copy(
                columnCount = incoming.columnCount,
                rowCount = incoming.rowCount,
                visibleRowCount = incoming.visibleRowCount,
                cellWidthDp = incoming.cellWidthDp,
                marginLeftDp = incoming.marginLeftDp,
                marginTopDp = incoming.marginTopDp,
                overlayAlpha = incoming.overlayAlpha,
                blurEnabled = incoming.blurEnabled,
            ),
        )
    }

    fun bindIfNeeded(layout: WidgetCanvasLayout, page: WidgetPanelPage, hostContext: Context) {
        layout.canvasHostContext = hostContext
        if (layout.canvasInteractionActive || layout.draggingChild != null || layout.anyChildPreviewingResize()) return
        val resolvedPage = resolvePageForBind(layout, page)
        val key = bindKeyFor(resolvedPage)
        when (planBind(layout.lastBindKey, resolvedPage)) {
            CanvasBindPlan.FullRebind -> {
                // bind() 自己会写 lastBindKey。
                layout.bind(resolvedPage, hostContext)
            }
            CanvasBindPlan.ApplyItems -> {
                applyPageItems(layout, resolvedPage, hostContext)
                layout.lastBindKey = key
            }
            CanvasBindPlan.ApplyGeometry -> {
                applyPageGeometry(layout, resolvedPage)
                layout.lastBindKey = key
            }
            CanvasBindPlan.None -> Unit
        }
    }

    /**
     * 决定本次 [bindIfNeeded] 该做什么。
     *
     * 关键点：`lastBindKey` 只在真正应用之后才推进（见 [bindIfNeeded]）。否则
     * 「条目和几何同时变化」时走了 items 分支、几何被吞掉，下一次进来又判定为
     * 「没变化」，画布就永久停在旧列数上。
     */
    fun planBind(previous: BindKey?, resolvedPage: WidgetPanelPage): CanvasBindPlan {
        val key = bindKeyFor(resolvedPage)
        return when {
            previous == null || previous.pageId != key.pageId -> CanvasBindPlan.FullRebind
            previous.itemsSignature != key.itemsSignature -> CanvasBindPlan.ApplyItems
            previous != key -> CanvasBindPlan.ApplyGeometry
            else -> CanvasBindPlan.None
        }
    }

    fun updateHoverCell(layout: WidgetCanvasLayout, x: Float, y: Float) {
        val step = layout.currentGridStepPx
        if (step <= 0) return
        val item = layout.draggingItem ?: return
        val topLeftX = x - layout.dragTouchOffsetX - layout.paddingLeft
        val topLeftY = y - layout.dragTouchOffsetY - layout.paddingTop
        // spanX 可能大于当前列数（例如切到更窄的列数设置后残留的宽卡片），
        // 此时 coerceIn 的上界为负会抛 "Cannot coerce value to an empty range"。
        val newHoverX = kotlin.math.round(topLeftX / step)
            .toInt()
            .coerceIn(0, (layout.pageColumnCount - item.spanX).coerceAtLeast(0))
        val candidateHoverY = kotlin.math.round(topLeftY / step).toInt().coerceAtLeast(0)
        layout.ensureBufferRowsBelow(candidateHoverY + item.spanY)
        val newHoverY = candidateHoverY.coerceIn(0, (layout.pageRowCount - item.spanY).coerceAtLeast(0))
        if (layout.hoverCellX != newHoverX || layout.hoverCellY != newHoverY) {
            layout.hoverCellX = newHoverX
            layout.hoverCellY = newHoverY
            layout.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        }
    }
}

/** [WidgetCanvasLayoutGeometry.bindIfNeeded] 本次要执行的动作。 */
internal enum class CanvasBindPlan {
    /** 页面与上次绑定完全一致，什么都不用做。 */
    None,

    /** 页面对不上（首绑或换页），整体重绑。 */
    FullRebind,

    /** 条目变了，重建/同步子 View（同时会应用几何）。 */
    ApplyItems,

    /** 只有几何变了（列数/行数/单元宽度等），就地重排。 */
    ApplyGeometry,
}
