package com.slideindex.app.stash

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 把需求里写明的延时数值钉住：这些是真机经验值，改动要有意为之，不能顺手改掉。
 * （其余测试用 [StashSendTiming] 自己的常量推期望值，常量变了它们会跟着变。）
 */
class StashSendTimingTest {

    @Test
    fun timingsMatchTheAgreedValues() {
        assertEquals(200L, StashSendTiming.SETTLE_BEFORE_FIND_INPUT_MS)
        assertEquals(100L, StashSendTiming.FIND_INPUT_POLL_MS)
        assertEquals(1_500L, StashSendTiming.FIND_INPUT_TIMEOUT_MS)
        assertEquals(50L, StashSendTiming.TEXT_AFTER_FOCUS_MS)
        assertEquals(80L, StashSendTiming.TEXT_AFTER_CLIPBOARD_MS)
        assertEquals(150L, StashSendTiming.TEXT_BEFORE_SEND_BUTTON_MS)
        assertEquals(80L, StashSendTiming.IMAGE_AFTER_FOCUS_MS)
        assertEquals(250L, StashSendTiming.IMAGE_BEFORE_SEND_BUTTON_MS)
        assertEquals(3, StashSendTiming.SEND_BUTTON_RETRIES)
        assertEquals(150L, StashSendTiming.SEND_BUTTON_RETRY_MS)
        assertEquals(1_500L, StashSendTiming.BETWEEN_BLOCKS_MS)
    }

    @Test
    fun defaultLabelsCoverTheSimplifiedChineseWording() {
        val labels = StashSendLabels.Default

        assertEquals(true, "发送" in labels.sendTexts)
        assertEquals(true, "确定" in labels.fallbackTexts)
        assertEquals(true, "发送(" in labels.sendPrefixes)
        assertEquals(true, "发送" in labels.sendDescriptions)
        assertEquals("com.tencent.mm", StashSendLabels.PKG_WECHAT)
    }
}
