package com.slideindex.app.stash

import android.content.Context
import android.widget.Toast
import com.slideindex.app.R
import com.slideindex.app.clipboard.ClipboardContentBlock
import com.slideindex.app.service.SlideIndexAccessibilityService
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 暂存条目「一键发送」：在微信聊天界面里自动填入输入框并点发送，图片走剪贴板粘贴再发送。
 *
 * 单进程：无障碍服务与面板同在主进程，直接用 [SlideIndexAccessibilityService.accessibilityInstance]。
 * 发送流程跑在应用级 scope 里，**不随面板销毁而取消**——点「发送」后面板会收起，
 * 若在面板的 Compose 作用域里发起，收起面板就会把发送取消掉。
 */
object StashSendHelper {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sending = AtomicBoolean(false)

    /**
     * 发送 [entry]。前置条件不满足（无障碍未连接 / 微信不在前台 / 无障碍树为空 / 找不到输入框）时，
     * 提示原因并降级为仅复制到剪贴板。
     */
    fun send(context: Context, entry: StashEntry) {
        val appContext = context.applicationContext
        val blocks = entry.sendableBlocks()
        if (blocks.isEmpty()) {
            toast(appContext, appContext.getString(R.string.stash_send_empty))
            return
        }
        if (!sending.compareAndSet(false, true)) {
            toast(appContext, appContext.getString(R.string.stash_send_busy))
            return
        }
        scope.launch {
            try {
                val result = withContext(Dispatchers.Default) { execute(appContext, blocks) }
                report(appContext, entry, result)
            } finally {
                sending.set(false)
            }
        }
    }

    private suspend fun execute(context: Context, blocks: List<ClipboardContentBlock>): StashSendResult {
        val service = SlideIndexAccessibilityService.accessibilityInstance()
            ?: return StashSendResult.Failed(StashSendFailure.ACCESSIBILITY_UNAVAILABLE, blocksSent = 0)
        val window = AccessibilityChatWindow(service, StashSendLabels.PKG_WECHAT)
        val trackedPackage = runCatching { SlideIndexAccessibilityService.currentForegroundPackageName() }.getOrNull()
        if (!isTargetForeground(trackedPackage, hasTargetWindow = window.targetRoots().isNotEmpty())) {
            return StashSendResult.Failed(StashSendFailure.TARGET_NOT_FOREGROUND, blocksSent = 0)
        }
        val sender = WechatChatSender(
            window = window,
            clipboard = SystemChatClipboard(context, StashAccess.repository, StashSendLabels.PKG_WECHAT)
        )
        return StashBlockSender.sendAll(blocks, sender)
    }

    private fun report(context: Context, entry: StashEntry, result: StashSendResult) {
        if (result !is StashSendResult.Failed) return
        val message = if (result.blocksSent > 0) {
            // 前面的内容已经发出去了，再复制整条只会重复。
            context.getString(R.string.stash_send_fail_partial)
        } else {
            val copied = result.failure.fallsBackToCopy && StashCoordinator.copyStashEntry(context, entry)
            context.getString(result.failure.messageRes) +
                if (copied) context.getString(R.string.stash_send_copied_suffix) else ""
        }
        toast(context, message)
    }

    private fun toast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
}

internal val StashSendFailure.messageRes: Int
    get() = when (this) {
        StashSendFailure.ACCESSIBILITY_UNAVAILABLE -> R.string.stash_send_fail_accessibility
        StashSendFailure.TARGET_NOT_FOREGROUND -> R.string.stash_send_fail_not_in_wechat
        StashSendFailure.TREE_EMPTY -> R.string.stash_send_fail_tree_empty
        StashSendFailure.INPUT_NOT_FOUND -> R.string.stash_send_fail_input_not_found
        StashSendFailure.SEND_BUTTON_NOT_FOUND -> R.string.stash_send_fail_send_button
        StashSendFailure.IMAGE_UNAVAILABLE -> R.string.stash_send_fail_image
        StashSendFailure.UNEXPECTED -> R.string.stash_send_fail_unexpected
    }
