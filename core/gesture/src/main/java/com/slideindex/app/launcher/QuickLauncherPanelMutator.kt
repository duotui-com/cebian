package com.slideindex.app.launcher

import com.slideindex.app.gesture.GestureAction
import com.slideindex.app.gesture.GestureRule
import java.util.UUID

object QuickLauncherPanelMutator {
    fun addPanel(
        panels: List<QuickLauncherPanel>,
        defaultColumns: Int = 3,
        defaultRows: Int = 4,
        name: String = QuickLauncherPanelDefaults.nextPanelName(panels.size),
    ): List<QuickLauncherPanel>? {
        val effective = QuickLauncherPanelDefaults.effectivePanels(panels)
        if (effective.size >= QuickLauncherPanelDefaults.MAX_PANELS) return null
        return effective + QuickLauncherPanelDefaults.defaultPanel(
            name = name,
            columnsPerPage = defaultColumns,
            rowsPerPage = defaultRows,
            id = UUID.randomUUID().toString(),
        )
    }

    fun duplicatePanel(panels: List<QuickLauncherPanel>, panelId: String): List<QuickLauncherPanel>? {
        val effective = QuickLauncherPanelDefaults.effectivePanels(panels)
        if (effective.size >= QuickLauncherPanelDefaults.MAX_PANELS) return null
        val source = effective.firstOrNull { it.id == panelId } ?: return null
        val copyName = source.name.ifBlank {
            QuickLauncherPanelDefaults.nextPanelName(effective.size)
        } + " (copy)"
        return effective + source.copy(
            id = UUID.randomUUID().toString(),
            name = copyName,
        )
    }

    fun removePanel(panels: List<QuickLauncherPanel>, panelId: String): List<QuickLauncherPanel>? {
        val effective = QuickLauncherPanelDefaults.effectivePanels(panels)
        if (effective.size <= 1) return null
        return effective.filterNot { it.id == panelId }
    }

    fun replacePanel(
        panels: List<QuickLauncherPanel>,
        panelId: String,
        updated: QuickLauncherPanel,
    ): List<QuickLauncherPanel> {
        val effective = QuickLauncherPanelDefaults.effectivePanels(panels)
        val index = effective.indexOfFirst { it.id == panelId }
        if (index < 0) return effective
        return effective.toMutableList().also { it[index] = updated.copy(id = panelId) }
    }

    fun updatePanelItems(
        panels: List<QuickLauncherPanel>,
        panelId: String,
        items: List<QuickLauncherItem>,
    ): List<QuickLauncherPanel> {
        val effective = QuickLauncherPanelDefaults.effectivePanels(panels)
        val index = effective.indexOfFirst { it.id == panelId }
        if (index < 0) return effective
        return effective.toMutableList().also { it[index] = effective[index].copy(items = items) }
    }

    fun remapQuickLauncherAction(action: GestureAction, removedPanelId: String, fallbackPanelId: String): GestureAction {
        if (action !is GestureAction.QuickLauncher) return action
        if (action.panelId != removedPanelId) return action
        return action.copy(panelId = fallbackPanelId)
    }

    fun sanitizeGestureRules(
        rules: List<GestureRule>,
        validPanelIds: Set<String>,
        fallbackPanelId: String,
    ): List<GestureRule> = rules.map { rule ->
        val action = rule.action
        if (action is GestureAction.QuickLauncher &&
            action.panelId.isNotBlank() &&
            action.panelId !in validPanelIds
        ) {
            rule.copy(action = action.copy(panelId = fallbackPanelId))
        } else {
            rule
        }
    }

    fun sanitizeQuickLauncherAction(
        action: GestureAction,
        validPanelIds: Set<String>,
        fallbackPanelId: String,
    ): GestureAction {
        if (action !is GestureAction.QuickLauncher) return action
        if (action.panelId.isBlank() || action.panelId in validPanelIds) return action
        return action.copy(panelId = fallbackPanelId)
    }

    /**
     * 把「打开快速启动器」动作里空/失效的面板引用钉到当前有效面板上。
     *
     * 空引用在运行期等价于「列表里的第一个面板」，一旦用户新增、删除或调整面板顺序就会漂移；
     * 写入时归一化成具体 id，绑定关系才稳定。非快速启动器动作原样返回。
     */
    fun normalizeQuickLauncherAction(action: GestureAction, panels: List<QuickLauncherPanel>): GestureAction {
        if (action !is GestureAction.QuickLauncher) return action
        val resolved = QuickLauncherPanelDefaults.resolvePanelId(panels, action.panelId)
        return if (resolved == action.panelId) action else action.copy(panelId = resolved)
    }

    /** 归一化条目内的快速启动器动作；文件夹会递归处理子项。其他类型原样返回。 */
    fun normalizeQuickLauncherItem(item: QuickLauncherItem, panels: List<QuickLauncherPanel>): QuickLauncherItem =
        when (item.type) {
            QuickLauncherItemType.ACTION -> {
                val action = QuickLauncherItemCodec.parseActionPayload(item.payload)
                if (action == null) {
                    item
                } else {
                    val normalized = normalizeQuickLauncherAction(action, panels)
                    if (normalized == action) {
                        item
                    } else {
                        item.copy(payload = QuickLauncherItemCodec.encodeActionPayload(normalized))
                    }
                }
            }
            QuickLauncherItemType.FOLDER -> {
                val children = item.folderItems()
                val normalized = children.map { normalizeQuickLauncherItem(it, panels) }
                if (normalized == children) item else item.withFolderItems(normalized)
            }
            else -> item
        }

    fun normalizeQuickLauncherItems(
        items: List<QuickLauncherItem>,
        panels: List<QuickLauncherPanel>,
    ): List<QuickLauncherItem> {
        if (items.isEmpty()) return items
        return items.map { normalizeQuickLauncherItem(it, panels) }
    }
}
