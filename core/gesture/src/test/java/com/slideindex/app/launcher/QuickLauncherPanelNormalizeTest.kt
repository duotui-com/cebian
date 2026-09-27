package com.slideindex.app.launcher

import com.slideindex.app.gesture.GestureAction
import org.junit.Assert.assertEquals
import org.junit.Test

/** 「打开快速启动器」动作的空/失效面板引用要钉到当前有效面板，避免跟随面板顺序漂移。 */
class QuickLauncherPanelNormalizeTest {
    private val panels = listOf(
        QuickLauncherPanel(id = "first-panel", name = "First"),
        QuickLauncherPanel(id = "second-panel", name = "Second"),
    )

    @Test
    fun blankPanelIdPinsToFirstPanel() {
        val normalized = QuickLauncherPanelMutator.normalizeQuickLauncherAction(
            GestureAction.QuickLauncher(""),
            panels,
        )

        assertEquals(GestureAction.QuickLauncher("first-panel"), normalized)
    }

    @Test
    fun unknownPanelIdPinsToFirstPanel() {
        val normalized = QuickLauncherPanelMutator.normalizeQuickLauncherAction(
            GestureAction.QuickLauncher("removed-panel"),
            panels,
        )

        assertEquals(GestureAction.QuickLauncher("first-panel"), normalized)
    }

    @Test
    fun knownPanelIdIsKept() {
        val action = GestureAction.QuickLauncher("second-panel")

        assertEquals(action, QuickLauncherPanelMutator.normalizeQuickLauncherAction(action, panels))
    }

    @Test
    fun nonQuickLauncherActionIsUntouched() {
        assertEquals(GestureAction.Back, QuickLauncherPanelMutator.normalizeQuickLauncherAction(GestureAction.Back, panels))
    }

    @Test
    fun blankPanelIdPinsToDefaultPanelWhenNoPanelConfigured() {
        val normalized = QuickLauncherPanelMutator.normalizeQuickLauncherAction(GestureAction.QuickLauncher(""), emptyList())

        assertEquals(GestureAction.QuickLauncher(QuickLauncherPanelDefaults.DEFAULT_PANEL_ID), normalized)
    }

    @Test
    fun itemBlankPanelIdIsPinnedToFirstPanel() {
        val item = QuickLauncherItem.action(GestureAction.QuickLauncher(""), "快速启动器")

        val normalized = QuickLauncherPanelMutator.normalizeQuickLauncherItem(item, panels)

        assertEquals(
            GestureAction.QuickLauncher("first-panel"),
            QuickLauncherItemCodec.parseActionPayload(normalized.payload),
        )
        assertEquals("快速启动器", normalized.label)
    }

    @Test
    fun folderChildrenAreNormalized() {
        val folder = QuickLauncherItem.folder(
            "常用",
            listOf(
                QuickLauncherItem.action(GestureAction.QuickLauncher(""), "快速启动器"),
                QuickLauncherItem.app("com.example.app", "App"),
            ),
        )

        val normalized = QuickLauncherPanelMutator.normalizeQuickLauncherItem(folder, panels)
        val children = normalized.folderItems()

        assertEquals(
            GestureAction.QuickLauncher("first-panel"),
            QuickLauncherItemCodec.parseActionPayload(children[0].payload),
        )
        assertEquals(children[1], folder.folderItems()[1])
    }

    @Test
    fun unchangedItemIsReturnedAsIs() {
        val item = QuickLauncherItem.action(GestureAction.QuickLauncher("second-panel"), "快速启动器")

        assertEquals(item, QuickLauncherPanelMutator.normalizeQuickLauncherItem(item, panels))
    }
}
