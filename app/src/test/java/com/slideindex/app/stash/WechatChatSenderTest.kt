package com.slideindex.app.stash

import com.slideindex.app.clipboard.ClipboardContentBlock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WechatChatSenderTest {

    /** 一个微信聊天窗口：输入框 +（默认已经显示的）发送按钮。 */
    private class Chat(sendButtonVisible: Boolean = true) {
        val root = FakeChatNode(className = "android.widget.FrameLayout", top = 0, bottom = 1000)
        val input = root.add(
            FakeChatNode(className = "android.widget.EditText", isEditable = true, top = 900, bottom = 980)
        )
        val sendButton = root.add(
            FakeChatNode(
                className = "android.widget.Button",
                text = "发送",
                isClickable = true,
                isVisibleToUser = sendButtonVisible,
                top = 900,
                bottom = 980
            )
        )
        val window = FakeChatWindow(root)
        val clipboard = FakeChatClipboard()
        val sender = WechatChatSender(window, clipboard)
    }

    // ───────────────────────── 文字 ─────────────────────────

    @Test
    fun sendText_setsTextThenClicksSend() = runTest {
        val chat = Chat()

        val failure = chat.sender.sendText("您好，欢迎咨询")

        assertNull(failure)
        assertEquals(listOf("focus", "setText:您好，欢迎咨询"), chat.input.actions)
        assertEquals(listOf("click"), chat.sendButton.actions)
        assertTrue("SET_TEXT 成功时不应动剪贴板", chat.clipboard.events.isEmpty())
    }

    @Test
    fun sendText_setTextRejected_fallsBackToClipboardPaste() = runTest {
        val chat = Chat()
        chat.input.setTextResult = false

        val failure = chat.sender.sendText("hello")

        assertNull(failure)
        assertEquals(listOf("focus", "setText:hello", "paste"), chat.input.actions)
        assertEquals(listOf("text:hello"), chat.clipboard.events)
        assertEquals(listOf("click"), chat.sendButton.actions)
    }

    @Test
    fun sendText_waitsForSendButtonToAppearAfterFilling() = runTest {
        // 微信在输入框有内容后才把“+”换成“发送”。
        val chat = Chat(sendButtonVisible = false)
        chat.input.onSetText = { chat.sendButton.isVisibleToUser = true }

        assertNull(chat.sender.sendText("hi"))
        assertEquals(listOf("click"), chat.sendButton.actions)
    }

    @Test
    fun sendText_retriesUntilSendButtonShowsUp() = runTest {
        val chat = Chat(sendButtonVisible = false)
        var lookups = 0
        val sender = WechatChatSender(
            FakeChatWindow {
                lookups++
                // 第 1 次取窗口是找输入框；第 2、3 次找按钮时它还没出现，第 4 次（第 3 次重试）才出现。
                if (lookups >= 4) chat.sendButton.isVisibleToUser = true
                listOf(chat.root)
            },
            chat.clipboard
        )

        assertNull(sender.sendText("hi"))
        assertEquals(listOf("click"), chat.sendButton.actions)
        assertEquals(4, lookups)
    }

    @Test
    fun sendText_sendButtonNeverAppears_failsAfterRetries() = runTest {
        val chat = Chat(sendButtonVisible = false)

        val failure = chat.sender.sendText("hi")

        assertEquals(StashSendFailure.SEND_BUTTON_NOT_FOUND, failure)
        assertTrue(chat.sendButton.actions.isEmpty())
        // 内容已经填进了输入框。
        assertEquals(listOf("focus", "setText:hi"), chat.input.actions)
    }

    @Test
    fun sendText_clickRejected_isTreatedAsNotSent() = runTest {
        val chat = Chat()
        chat.sendButton.clickResult = false

        assertEquals(StashSendFailure.SEND_BUTTON_NOT_FOUND, chat.sender.sendText("hi"))
        assertEquals(StashSendTiming.SEND_BUTTON_RETRIES + 1, chat.sendButton.actions.size)
    }

    // ───────────────────────── 找不到输入框时的诊断 ─────────────────────────

    @Test
    fun sendText_noRoots_meansTargetNotForeground() = runTest {
        val sender = WechatChatSender(FakeChatWindow { emptyList() }, FakeChatClipboard())

        assertEquals(StashSendFailure.TARGET_NOT_FOREGROUND, sender.sendText("hi"))
    }

    @Test
    fun sendText_singleNodeTree_meansEmptyAccessibilityTree() = runTest {
        val sender = WechatChatSender(FakeChatWindow(FakeChatNode()), FakeChatClipboard())

        assertEquals(StashSendFailure.TREE_EMPTY, sender.sendText("hi"))
    }

    @Test
    fun sendText_treeWithoutInput_meansInputNotFound() = runTest {
        val root = FakeChatNode()
        root.add(FakeChatNode(text = "朋友圈"))
        val sender = WechatChatSender(FakeChatWindow(root), FakeChatClipboard())

        assertEquals(StashSendFailure.INPUT_NOT_FOUND, sender.sendText("hi"))
    }

    @Test
    fun sendText_pollsForInputForAboutOneAndAHalfSeconds() = runTest {
        val sender = WechatChatSender(FakeChatWindow { emptyList() }, FakeChatClipboard())

        sender.sendText("hi")

        val expected = StashSendTiming.SETTLE_BEFORE_FIND_INPUT_MS + StashSendTiming.FIND_INPUT_TIMEOUT_MS
        assertEquals(expected, currentTime)
    }

    @Test
    fun sendText_inputAppearsLate_isStillFound() = runTest {
        val root = FakeChatNode(className = "android.widget.FrameLayout", top = 0, bottom = 1000)
        val input = FakeChatNode(className = "android.widget.EditText", isEditable = true)
        val button = root.add(FakeChatNode(text = "发送", isClickable = true, top = 900, bottom = 980))
        var polls = 0
        val sender = WechatChatSender(
            FakeChatWindow {
                polls++
                if (polls == 4 && input !in root.children) root.add(input)
                listOf(root)
            },
            FakeChatClipboard()
        )

        assertNull(sender.sendText("hi"))
        assertEquals(listOf("click"), button.actions)
    }

    @Test
    fun sendText_usesWeChatWindowsOtherThanTheActiveOne() = runTest {
        val popup = FakeChatNode(className = "android.widget.FrameLayout", top = 0, bottom = 1000)
        val main = FakeChatNode(className = "android.widget.FrameLayout", top = 0, bottom = 1000)
        val input = main.add(FakeChatNode(className = "android.widget.EditText", isEditable = true))
        val button = popup.add(FakeChatNode(text = "发送", isClickable = true, top = 900, bottom = 980))
        val sender = WechatChatSender(FakeChatWindow { listOf(popup, main) }, FakeChatClipboard())

        assertNull(sender.sendText("hi"))
        assertEquals(listOf("focus", "setText:hi"), input.actions)
        assertEquals(listOf("click"), button.actions)
    }

    // ───────────────────────── 评审补的回归用例 ─────────────────────────

    @Test
    fun sendText_setTextRejectedAndClipboardWriteFails_neverPastesStaleClipboard() = runTest {
        // 剪贴板没写进去就粘贴，会把用户之前复制的别的内容（验证码、口令……）贴进聊天框并发出去。
        val chat = Chat()
        chat.input.setTextResult = false
        chat.clipboard.textResult = false

        val failure = chat.sender.sendText("您好")

        assertEquals(StashSendFailure.UNEXPECTED, failure)
        assertEquals(listOf("focus", "setText:您好"), chat.input.actions)
        assertTrue("不该点发送: ${chat.sendButton.actions}", chat.sendButton.actions.isEmpty())
    }

    @Test
    fun sendText_primarySendInLaterWindow_beatsConfirmInEarlierWindow() = runTest {
        val dialog = FakeChatNode(className = "android.widget.FrameLayout", top = 800, bottom = 1200)
        val ok = dialog.add(FakeChatNode(text = "确定", isClickable = true, top = 1100, bottom = 1180))
        val main = FakeChatNode(className = "android.widget.FrameLayout", top = 0, bottom = 2000)
        main.add(FakeChatNode(className = "android.widget.EditText", isEditable = true, top = 1800, bottom = 1950))
        val send = main.add(FakeChatNode(text = "发送", isClickable = true, top = 1800, bottom = 1950))

        val failure = WechatChatSender(FakeChatWindow { listOf(dialog, main) }, FakeChatClipboard()).sendText("您好")

        assertNull(failure)
        assertEquals(listOf("click"), send.actions)
        assertTrue("不该点弹窗里的“确定”: ${ok.actions}", ok.actions.isEmpty())
    }

    @Test
    fun sendText_realEditTextInLaterWindow_beatsWeakCandidateInEarlierWindow() = runTest {
        val popup = FakeChatNode(className = "android.widget.FrameLayout", top = 800, bottom = 1200)
        val weak = popup.add(
            FakeChatNode(className = "android.widget.Button", text = "取消", isFocused = true, isFocusable = true, isClickable = true)
        )
        val main = FakeChatNode(className = "android.widget.FrameLayout", top = 0, bottom = 2000)
        val input = main.add(FakeChatNode(className = "android.widget.EditText", isEditable = true, top = 1800, bottom = 1950))
        main.add(FakeChatNode(text = "发送", isClickable = true, top = 1800, bottom = 1950))

        val failure = WechatChatSender(FakeChatWindow { listOf(popup, main) }, FakeChatClipboard()).sendText("您好")

        assertNull(failure)
        assertTrue("应写进真正的输入框: ${input.actions}", input.actions.contains("setText:您好"))
        assertTrue("不该动弱候选: ${weak.actions}", weak.actions.isEmpty())
    }

    @Test
    fun sendText_confirmBubbleIsIgnoredWhileTheRealSendButtonMayStillAppear() = runTest {
        // 客户上一条回复恰好是“确定”；真正的“发送”按钮要慢半拍才出现。前几次尝试只认“发送”，不能误点气泡。
        val chat = Chat(sendButtonVisible = false)
        val bubbleBox = chat.root.add(FakeChatNode(isClickable = true, top = 500, bottom = 560))
        bubbleBox.add(FakeChatNode(className = "android.widget.TextView", text = "确定", top = 500, bottom = 560))
        var lookups = 0
        val sender = WechatChatSender(
            FakeChatWindow {
                lookups++
                // 第 1 次是找输入框；第 2、3 次找按钮时还没出现，第 4 次（第 3 次尝试）才出现。
                if (lookups >= 4) chat.sendButton.isVisibleToUser = true
                listOf(chat.root)
            },
            chat.clipboard
        )

        assertNull(sender.sendText("hi"))
        assertEquals(listOf("click"), chat.sendButton.actions)
        assertTrue("不该点气泡: ${bubbleBox.actions}", bubbleBox.actions.isEmpty())
    }

    @Test
    fun sendText_confirmFallbackIsOnlyUsedOnTheLastAttempt() = runTest {
        val root = FakeChatNode(className = "android.widget.FrameLayout", top = 0, bottom = 1000)
        root.add(FakeChatNode(className = "android.widget.EditText", isEditable = true, top = 900, bottom = 980))
        val confirm = root.add(FakeChatNode(text = "确定", isClickable = true, top = 900, bottom = 980))
        var lookups = 0
        val sender = WechatChatSender(FakeChatWindow { lookups++; listOf(root) }, FakeChatClipboard())

        assertNull(sender.sendText("hi"))

        // 1 次找输入框 + (第一次尝试 + 重试) 共 4 次找按钮；“确定”只在最后一次才被点。
        assertEquals(1 + StashSendTiming.SEND_BUTTON_RETRIES + 1, lookups)
        assertEquals(listOf("click"), confirm.actions)
        val expectedTime = StashSendTiming.SETTLE_BEFORE_FIND_INPUT_MS +
            StashSendTiming.TEXT_AFTER_FOCUS_MS +
            StashSendTiming.TEXT_BEFORE_SEND_BUTTON_MS +
            StashSendTiming.SEND_BUTTON_RETRIES * StashSendTiming.SEND_BUTTON_RETRY_MS
        assertEquals(expectedTime, currentTime)
    }

    @Test
    fun sendText_allWindowsHaveOnlyARootNode_meansEmptyTree() = runTest {
        val sender = WechatChatSender(FakeChatWindow { listOf(FakeChatNode(), FakeChatNode()) }, FakeChatClipboard())

        assertEquals(StashSendFailure.TREE_EMPTY, sender.sendText("hi"))
    }

    @Test
    fun sendText_anyWindowWithContent_meansInputNotFoundNotEmptyTree() = runTest {
        val busy = FakeChatNode().also { it.add(FakeChatNode(text = "朋友圈")) }
        val sender = WechatChatSender(FakeChatWindow { listOf(FakeChatNode(), busy) }, FakeChatClipboard())

        assertEquals(StashSendFailure.INPUT_NOT_FOUND, sender.sendText("hi"))
    }

    @Test
    fun sendText_logsWhatItDoes() = runTest {
        val lines = mutableListOf<String>()
        val chat = Chat()
        val sender = WechatChatSender(chat.window, chat.clipboard, log = { lines += it })

        sender.sendText("hi")

        assertTrue("应该有调试日志: $lines", lines.any { it.startsWith("awaitInputNode") })
        assertTrue(lines.any { it.startsWith("clickSendButton") && it.contains("clicked=true") })
    }

    // ───────────────────────── 挑出微信窗口（纯函数）─────────────────────────

    private data class Win(val pkg: String?, val name: String)

    private fun pick(active: Win?, others: List<Win>) =
        pickTargetRoots(active, others, { it.pkg }, StashSendLabels.PKG_WECHAT).map { it.name }

    @Test
    fun pickTargetRoots_activeFirstThenOtherTargetWindows() {
        val active = Win("com.tencent.mm", "active")
        val others = listOf(Win("com.other", "x"), Win("com.tencent.mm", "popup"), Win("com.tencent.mm", "main"))

        assertEquals(listOf("active", "popup", "main"), pick(active, others))
    }

    @Test
    fun pickTargetRoots_activeWindowFromAnotherApp_isNeverReturned() {
        // 活动窗口是我们自己的面板 / 输入法：只取其余窗口里属于微信的。
        val active = Win("com.slideindex.app", "panel")
        val others = listOf(Win("com.tencent.mm", "main"), Win("com.android.inputmethod", "ime"))

        assertEquals(listOf("main"), pick(active, others))
    }

    @Test
    fun pickTargetRoots_targetNotOnScreen_neverFallsBackToOtherApps() {
        val active = Win("com.other.app", "other")
        val others = listOf(Win("com.slideindex.app", "panel"), Win(null, "unknown"))

        assertTrue(pick(active, others).isEmpty())
        assertTrue(pick(null, emptyList()).isEmpty())
    }

    @Test
    fun pickTargetRoots_dropsTheActiveWindowListedAgain() {
        val active = Win("com.tencent.mm", "active")

        assertEquals(listOf("active"), pick(active, listOf(active, Win("com.tencent.mm", "active"))))
    }

    @Test
    fun pickTargetRoots_packageNameMustMatchExactly() {
        val others = listOf(Win("com.tencent.mm.extra", "lookalike"), Win("com.tencent.mmm", "lookalike2"))

        assertTrue(pick(null, others).isEmpty())
    }

    // ───────────────────────── 图片 ─────────────────────────

    @Test
    fun sendImage_writesClipboardThenPastesThenClicksSend() = runTest {
        val chat = Chat(sendButtonVisible = false)
        chat.input.onPaste = { chat.sendButton.isVisibleToUser = true }

        val failure = chat.sender.sendImage("a.png")

        assertNull(failure)
        assertEquals(listOf("image:a.png"), chat.clipboard.events)
        assertEquals(listOf("focus", "paste"), chat.input.actions)
        assertEquals(listOf("click"), chat.sendButton.actions)
    }

    @Test
    fun sendImage_clipboardUnavailable_failsBeforeTouchingTheChat() = runTest {
        val chat = Chat()
        chat.clipboard.imageResult = false

        assertEquals(StashSendFailure.IMAGE_UNAVAILABLE, chat.sender.sendImage("gone.png"))
        assertTrue(chat.input.actions.isEmpty())
    }

    // ───────────────────────── 多块内容 ─────────────────────────

    private class RecordingDriver(
        private val failAt: Int? = null,
        private val failure: StashSendFailure = StashSendFailure.INPUT_NOT_FOUND,
        private val throwAt: Int? = null,
        private val onCall: (String) -> Unit = {}
    ) : ChatSendDriver {
        val calls = mutableListOf<String>()
        val callTimes = mutableListOf<Long>()
        var clock: () -> Long = { 0L }

        private fun record(call: String): StashSendFailure? {
            val index = calls.size
            calls += call
            callTimes += clock()
            onCall(call)
            if (throwAt == index) error("boom")
            return if (failAt == index) failure else null
        }

        override suspend fun sendText(text: String) = record("text:$text")
        override suspend fun sendImage(fileName: String) = record("image:$fileName")
    }

    private val richBlocks = listOf(
        ClipboardContentBlock.text("先发文字"),
        ClipboardContentBlock.image("pic.png"),
        ClipboardContentBlock.text("再发一段"),
    )

    @Test
    fun sendAll_sendsBlocksInOrderWithGapBetweenThem() = runTest {
        val driver = RecordingDriver().also { it.clock = { currentTime } }

        val result = StashBlockSender.sendAll(richBlocks, driver)

        assertEquals(StashSendResult.Sent, result)
        assertEquals(listOf("text:先发文字", "image:pic.png", "text:再发一段"), driver.calls)
        val gap = StashSendTiming.BETWEEN_BLOCKS_MS
        assertEquals(listOf(0L, gap, 2 * gap), driver.callTimes)
    }

    @Test
    fun sendAll_trimsTextBeforeSending() = runTest {
        val driver = RecordingDriver()

        StashBlockSender.sendAll(listOf(ClipboardContentBlock.text("  您好\n")), driver)

        assertEquals(listOf("text:您好"), driver.calls)
    }

    @Test
    fun sendAll_singleBlock_hasNoGap() = runTest {
        val driver = RecordingDriver().also { it.clock = { currentTime } }

        StashBlockSender.sendAll(listOf(ClipboardContentBlock.text("x")), driver)

        assertEquals(listOf(0L), driver.callTimes)
    }

    @Test
    fun sendAll_abortsAtFirstFailureAndReportsHowManyWereSent() = runTest {
        val driver = RecordingDriver(failAt = 1, failure = StashSendFailure.SEND_BUTTON_NOT_FOUND)

        val result = StashBlockSender.sendAll(richBlocks, driver)

        assertEquals(StashSendResult.Failed(StashSendFailure.SEND_BUTTON_NOT_FOUND, blocksSent = 1), result)
        assertEquals(listOf("text:先发文字", "image:pic.png"), driver.calls)
    }

    @Test
    fun sendAll_firstBlockFailing_reportsNothingSent() = runTest {
        val driver = RecordingDriver(failAt = 0, failure = StashSendFailure.TARGET_NOT_FOREGROUND)

        val result = StashBlockSender.sendAll(richBlocks, driver)

        assertEquals(StashSendResult.Failed(StashSendFailure.TARGET_NOT_FOREGROUND, blocksSent = 0), result)
    }

    @Test
    fun sendAll_unexpectedExceptionBecomesFailureInsteadOfCrashing() = runTest {
        val driver = RecordingDriver(throwAt = 1)

        val result = StashBlockSender.sendAll(richBlocks, driver)

        assertEquals(StashSendResult.Failed(StashSendFailure.UNEXPECTED, blocksSent = 1), result)
    }

    @Test
    fun sendAll_cancellationIsNotSwallowed() = runTest {
        val driver = object : ChatSendDriver {
            override suspend fun sendText(text: String): StashSendFailure? = throw CancellationException("cancelled")
            override suspend fun sendImage(fileName: String): StashSendFailure? = null
        }

        var propagated = false
        try {
            StashBlockSender.sendAll(richBlocks, driver)
        } catch (_: CancellationException) {
            propagated = true
        }

        assertTrue(propagated)
    }

    // ───────────────────────── 条目 → 内容块 / 前置检查 ─────────────────────────

    @Test
    fun sendableBlocks_followsEntryOrderAndDropsBlankBlocks() {
        val entry = StashEntry(
            id = "r",
            type = StashEntryType.RICH,
            contentBlocks = listOf(
                ClipboardContentBlock.text("a"),
                ClipboardContentBlock.text("   "),
                ClipboardContentBlock.image("1.png"),
                ClipboardContentBlock.image(""),
                ClipboardContentBlock.text("b"),
            ),
            createdAtEpochMs = 1L
        )

        val blocks = entry.sendableBlocks()

        assertEquals(listOf("a", "1.png", "b"), blocks.map { it.text.ifEmpty { it.fileName } })
    }

    @Test
    fun sendableBlocks_textAndImageEntries() {
        val text = StashEntry(id = "t", type = StashEntryType.TEXT, text = "  hello ", createdAtEpochMs = 1L)
        val image = StashEntry(id = "i", type = StashEntryType.IMAGE, imageFileName = "i.png", createdAtEpochMs = 1L)
        val empty = StashEntry(id = "e", type = StashEntryType.TEXT, text = "  ", createdAtEpochMs = 1L)

        assertEquals(listOf("hello"), text.sendableBlocks().map { it.text })
        assertEquals(listOf("i.png"), image.sendableBlocks().map { it.fileName })
        assertTrue(empty.sendableBlocks().isEmpty())
    }

    @Test
    fun isTargetForeground_trackerOrLiveWindow() {
        assertTrue(isTargetForeground("com.tencent.mm", hasTargetWindow = false))
        assertTrue(isTargetForeground(null, hasTargetWindow = true))
        assertTrue(isTargetForeground("com.other.app", hasTargetWindow = true))
        assertFalse(isTargetForeground("com.other.app", hasTargetWindow = false))
        assertFalse(isTargetForeground(null, hasTargetWindow = false))
    }

}
