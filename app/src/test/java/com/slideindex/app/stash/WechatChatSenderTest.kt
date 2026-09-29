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
        assertEquals(StashSendTiming.SEND_BUTTON_ATTEMPTS, chat.sendButton.actions.size)
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

    @Test
    fun onlySendButtonFailureSkipsTheCopyFallback() {
        val noCopy = StashSendFailure.entries.filterNot { it.fallsBackToCopy }

        assertEquals(listOf(StashSendFailure.SEND_BUTTON_NOT_FOUND), noCopy)
    }
}
