package com.slideindex.app.launcher

import com.slideindex.app.gesture.GestureAction
import com.slideindex.app.gesture.GestureRule
import com.slideindex.app.gesture.GestureRuleCodec
import com.slideindex.app.gesture.GestureTriggerType
import com.slideindex.app.overlay.PanelSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 删除启动面板时，散落在各处的「打开快速启动器」动作要改写到存活面板。
 *
 * 覆盖两种落库形态：动作负载（`2\u001C面板 id`）与手势规则相邻字段（`2\u001F面板 id`）。
 */
class QuickLauncherPanelReferenceRemapTest {
    private val removedPanelId = "panel-b"
    private val fallbackPanelId = "panel-a"

    @Test
    fun remapsQuickLauncherItemActionPayload() {
        val item = QuickLauncherItem.action(GestureAction.QuickLauncher(removedPanelId), "快速启动器")

        val remapped = QuickLauncherItemCodec.remapPanelReferences(
            raw = QuickLauncherItemCodec.encode(item),
            removedPanelId = removedPanelId,
            fallbackPanelId = fallbackPanelId,
        )

        assertEquals(
            GestureAction.QuickLauncher(fallbackPanelId),
            QuickLauncherItemCodec.decode(remapped)?.payload?.let(QuickLauncherItemCodec::parseActionPayload),
        )
    }

    @Test
    fun remapsGestureRuleActionField() {
        val rule = GestureRule(
            id = GestureRule.slotId(PanelSide.LEFT, GestureTriggerType.SHORT_SWIPE_IN, "default"),
            side = PanelSide.LEFT,
            trigger = GestureTriggerType.SHORT_SWIPE_IN,
            action = GestureAction.QuickLauncher(removedPanelId),
        )

        val remapped = QuickLauncherItemCodec.remapPanelReferences(
            raw = GestureRuleCodec.encode(rule),
            removedPanelId = removedPanelId,
            fallbackPanelId = fallbackPanelId,
        )

        assertEquals(GestureAction.QuickLauncher(fallbackPanelId), GestureRuleCodec.decode(remapped)?.action)
    }

    @Test
    fun keepsOtherPanelReferencesUntouched() {
        val raw = QuickLauncherItemCodec.encode(
            QuickLauncherItem.action(GestureAction.QuickLauncher("panel-c"), "快速启动器"),
        )

        assertEquals(
            raw,
            QuickLauncherItemCodec.remapPanelReferences(raw, removedPanelId, fallbackPanelId),
        )
    }

    @Test
    fun keepsNonQuickLauncherActionsUntouched() {
        val item = QuickLauncherItem.action(GestureAction.Back, "返回")
        val raw = QuickLauncherItemCodec.encode(item)

        assertEquals(
            raw,
            QuickLauncherItemCodec.remapPanelReferences(raw, removedPanelId, fallbackPanelId),
        )
    }

    @Test
    fun doesNotRewriteLongerPanelIdSharingPrefix() {
        val longerPanelId = "${removedPanelId}bb"
        val raw = QuickLauncherItemCodec.encode(
            QuickLauncherItem.action(GestureAction.QuickLauncher(longerPanelId), "快速启动器"),
        )

        val remapped = QuickLauncherItemCodec.remapPanelReferences(raw, removedPanelId, fallbackPanelId)

        assertEquals(raw, remapped)
        assertEquals(
            GestureAction.QuickLauncher(longerPanelId),
            QuickLauncherItemCodec.decode(remapped)?.payload?.let(QuickLauncherItemCodec::parseActionPayload),
        )
    }

    @Test
    fun remapsEveryAffectedEntryInStringSet() {
        val affected = QuickLauncherItemCodec.encode(
            QuickLauncherItem.action(GestureAction.QuickLauncher(removedPanelId), "快速启动器"),
        )
        val untouched = QuickLauncherItemCodec.encode(
            QuickLauncherItem.action(GestureAction.QuickLauncher("panel-c"), "快速启动器"),
        )

        val remapped = QuickLauncherItemCodec.remapPanelReferences(
            values = setOf(affected, untouched),
            removedPanelId = removedPanelId,
            fallbackPanelId = fallbackPanelId,
        )

        assertEquals(2, remapped.size)
        assertTrue(untouched in remapped)
        assertEquals(
            GestureAction.QuickLauncher(fallbackPanelId),
            remapped.first { it != untouched }
                .let(QuickLauncherItemCodec::decode)
                ?.payload
                ?.let(QuickLauncherItemCodec::parseActionPayload),
        )
    }

    @Test
    fun identicalFallbackIdIsNoOp() {
        val raw = QuickLauncherItemCodec.encode(
            QuickLauncherItem.action(GestureAction.QuickLauncher(removedPanelId), "快速启动器"),
        )

        assertEquals(raw, QuickLauncherItemCodec.remapPanelReferences(raw, removedPanelId, removedPanelId))
    }
}
