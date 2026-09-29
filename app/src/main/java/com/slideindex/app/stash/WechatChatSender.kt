package com.slideindex.app.stash

import com.slideindex.app.clipboard.ClipboardBlockKind
import com.slideindex.app.clipboard.ClipboardContentBlock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** 一键发送失败的原因。 */
enum class StashSendFailure {
    /** 无障碍服务没连着。 */
    ACCESSIBILITY_UNAVAILABLE,

    /** 微信不在前台，或读不到微信的窗口。 */
    TARGET_NOT_FOREGROUND,

    /** 微信在前台，但无障碍树是空的（部分机型 / 微信版本的已知问题）。 */
    TREE_EMPTY,

    /** 找不到输入框。 */
    INPUT_NOT_FOUND,

    /** 找不到或点不了“发送”按钮（内容多半已经填进输入框，但不保证）。 */
    SEND_BUTTON_NOT_FOUND,

    /** 图片文件丢失，或放不上剪贴板。 */
    IMAGE_UNAVAILABLE,

    UNEXPECTED,
}

sealed interface StashSendResult {
    data object Sent : StashSendResult

    /** [blocksSent] 是失败之前已经成功发出去的内容块数。 */
    data class Failed(val failure: StashSendFailure, val blocksSent: Int) : StashSendResult
}

/** 逐块发送的内容：文字块与图片块按条目内的顺序，丢掉空白块。 */
internal fun StashEntry.sendableBlocks(): List<ClipboardContentBlock> =
    resolvedContentBlocks().filter { block ->
        when (block.kind) {
            ClipboardBlockKind.TEXT -> block.text.isNotBlank()
            ClipboardBlockKind.IMAGE -> block.fileName.isNotBlank()
        }
    }

/**
 * 目标聊天 App 是否在前台：前台跟踪器记录的最近一个非本应用的包名就是它，
 * 或者它的窗口此刻确实在屏幕上（多窗口 / 跟踪器滞后时兜底）。
 */
internal fun isTargetForeground(
    trackedPackage: String?,
    hasTargetWindow: Boolean,
    targetPackage: String = StashSendLabels.PKG_WECHAT
): Boolean = trackedPackage == targetPackage || hasTargetWindow

/** 往聊天窗口发一条文字 / 一张图；成功返回 null，失败返回原因。 */
internal interface ChatSendDriver {
    suspend fun sendText(text: String): StashSendFailure?
    suspend fun sendImage(fileName: String): StashSendFailure?
}

/** 按顺序把内容块逐块发出去；某一块失败就中止。 */
internal object StashBlockSender {
    suspend fun sendAll(
        blocks: List<ClipboardContentBlock>,
        driver: ChatSendDriver,
        betweenBlocksMs: Long = StashSendTiming.BETWEEN_BLOCKS_MS
    ): StashSendResult {
        var sent = 0
        for ((index, block) in blocks.withIndex()) {
            if (index > 0) delay(betweenBlocksMs)
            val failure = try {
                when (block.kind) {
                    ClipboardBlockKind.TEXT -> driver.sendText(block.text.trim())
                    ClipboardBlockKind.IMAGE -> driver.sendImage(block.fileName)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                StashSendFailure.UNEXPECTED
            }
            if (failure != null) return StashSendResult.Failed(failure, sent)
            sent++
        }
        return StashSendResult.Sent
    }
}

/**
 * 微信发送流程：移植自速聊 `sendText` / `sendImage`。
 *
 * 已知坑：部分机型 / 微信版本下 `rootInActiveWindow` 只有 1 个节点（空无障碍树），此时找不到输入框，
 * 本实现只做检测并返回 [StashSendFailure.TREE_EMPTY]，不做坐标手势兜底。
 *
 * [log] 是真机调参用的调试日志（命中了哪个输入框、SET_TEXT / 粘贴 / 点击的结果……），纯 JVM 单测里默认丢弃。
 */
internal class WechatChatSender(
    private val window: ChatWindow,
    private val clipboard: ChatClipboard,
    private val labels: StashSendLabels = StashSendLabels.Default,
    private val log: (String) -> Unit = {}
) : ChatSendDriver {

    override suspend fun sendText(text: String): StashSendFailure? {
        delay(StashSendTiming.SETTLE_BEFORE_FIND_INPUT_MS)
        val input = awaitInputNode() ?: return diagnoseMissingInput()
        val focused = input.focus()
        delay(StashSendTiming.TEXT_AFTER_FOCUS_MS)
        // 首选 ACTION_SET_TEXT；被拒绝时退化为写剪贴板 + 粘贴。
        val setDirectly = input.setText(text)
        log("sendText: focus=$focused setText=$setDirectly")
        if (!setDirectly) {
            // 剪贴板没写进去就不能粘贴：那会把用户之前复制的别的内容（验证码、口令……）贴进聊天框并发出去。
            if (!clipboard.setText(text)) return StashSendFailure.UNEXPECTED
            delay(StashSendTiming.TEXT_AFTER_CLIPBOARD_MS)
            log("sendText: paste=${input.paste()}")
        }
        delay(StashSendTiming.TEXT_BEFORE_SEND_BUTTON_MS)
        return if (clickSendButton()) null else StashSendFailure.SEND_BUTTON_NOT_FOUND
    }

    override suspend fun sendImage(fileName: String): StashSendFailure? {
        // 每次发图片都重新写剪贴板并授权：前面的文字回退路径可能已经把剪贴板覆盖了。
        if (!clipboard.setImage(fileName)) return StashSendFailure.IMAGE_UNAVAILABLE
        delay(StashSendTiming.SETTLE_BEFORE_FIND_INPUT_MS)
        val input = awaitInputNode() ?: return diagnoseMissingInput()
        val focused = input.focus()
        delay(StashSendTiming.IMAGE_AFTER_FOCUS_MS)
        log("sendImage: focus=$focused paste=${input.paste()}")
        // 粘贴图片后微信会弹出带“发送”的确认条。
        delay(StashSendTiming.IMAGE_BEFORE_SEND_BUTTON_MS)
        return if (clickSendButton()) null else StashSendFailure.SEND_BUTTON_NOT_FOUND
    }

    /**
     * 每 100ms 找一次输入框，最多等 1.5s。
     *
     * 限时用协程的挂起计时而不是“试 15 次”：微信的节点树很大时，单次遍历本身就可能要几百毫秒，
     * 按次数算会把总等待拖到好几秒。
     */
    private suspend fun awaitInputNode(): ChatNode? {
        val found = withTimeoutOrNull(StashSendTiming.FIND_INPUT_TIMEOUT_MS) {
            var node: ChatNode? = null
            while (node == null) {
                node = WechatNodeFinder.findEditTextInAny(window.targetRoots())
                if (node == null) delay(StashSendTiming.FIND_INPUT_POLL_MS)
            }
            node
        }
        log("awaitInputNode: ${found?.let { "found ${it.className} editable=${it.isEditable}" } ?: "not found"}")
        return found
    }

    private fun diagnoseMissingInput(): StashSendFailure {
        val roots = window.targetRoots()
        if (roots.isEmpty()) return StashSendFailure.TARGET_NOT_FOREGROUND
        // 每个窗口的树都只有根节点：微信没把界面暴露给无障碍。
        return if (roots.all { WechatNodeFinder.countNodes(it) <= 1 }) {
            StashSendFailure.TREE_EMPTY
        } else {
            StashSendFailure.INPUT_NOT_FOUND
        }
    }

    /**
     * 找不到（或点不动）就隔一会儿重试，而不是只试一次。
     *
     * “确定”只是兜底文案：前面几次只认“发送”，最后一次才考虑它。否则“发送”按钮只是还没来得及出现
     * （“+”换成“发送”有个动画）时，屏幕上任何一个“确定”（比如客户上一条回复）都会被误点，
     * 而且点得动就会被当成发送成功。
     */
    private suspend fun clickSendButton(): Boolean {
        val lastAttempt = StashSendTiming.SEND_BUTTON_RETRIES
        for (attempt in 0..lastAttempt) {
            val button = WechatNodeFinder.findSendButtonInAny(
                roots = window.targetRoots(),
                labels = labels,
                allowFallback = attempt == lastAttempt
            )
            val clicked = button?.click() == true
            log("clickSendButton: attempt=$attempt found=${button != null} clicked=$clicked")
            if (clicked) return true
            if (attempt < lastAttempt) delay(StashSendTiming.SEND_BUTTON_RETRY_MS)
        }
        return false
    }
}
