package com.slideindex.app.stash

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.slideindex.app.R
import com.slideindex.app.clipboard.ClipboardContentBlock
import com.slideindex.app.service.SlideIndexAccessibilityService
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
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
    private const val TAG = "StashSend"

    // 与无障碍服务同在主进程：这里冒出未捕获异常会连无障碍服务一起带崩，所以带一个兜底的 handler。
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, error -> Log.e(TAG, "unhandled error while sending", error) }
    )
    private val sending = AtomicBoolean(false)

    /**
     * 发送 [entry]。前置条件不满足（无障碍未连接 / 微信不在前台 / 无障碍树为空 / 找不到输入框）时，
     * 提示原因并降级为仅复制到剪贴板。
     *
     * [onStart] 在“确定要开始发送”（内容非空、没有别的发送在进行）之后、真正动手之前调用，
     * 用来收起面板这类必须先于发送完成的动作；提示“请稍候”之类时不会调用。
     */
    fun send(context: Context, entry: StashEntry, onStart: () -> Unit = {}) {
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
        runCatching(onStart).onFailure { Log.w(TAG, "onStart failed", it) }
        scope.launch {
            try {
                val result = try {
                    withContext(Dispatchers.Default) { execute(appContext, blocks) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.e(TAG, "send failed unexpectedly", error)
                    StashSendResult.Failed(StashSendFailure.UNEXPECTED, blocksSent = 0)
                }
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
        val hasTargetWindow = window.targetRoots().isNotEmpty()
        Log.d(TAG, "execute: blocks=${blocks.size} tracked=$trackedPackage hasTargetWindow=$hasTargetWindow")
        if (!isTargetForeground(trackedPackage, hasTargetWindow)) {
            return StashSendResult.Failed(StashSendFailure.TARGET_NOT_FOREGROUND, blocksSent = 0)
        }
        val sender = WechatChatSender(
            window = window,
            clipboard = SystemChatClipboard(context, StashAccess.repository, StashSendLabels.PKG_WECHAT),
            log = { Log.d(TAG, it) }
        )
        return StashBlockSender.sendAll(blocks, sender)
    }

    /** 失败提示。这条路径恰好在功能已经出问题时运行，自己不能再抛异常，否则用户连原因都看不到。 */
    private fun report(context: Context, entry: StashEntry, result: StashSendResult) {
        if (result !is StashSendResult.Failed) return
        Log.w(TAG, "send failed: ${result.failure} after ${result.blocksSent} block(s)")
        val message = if (result.blocksSent > 0) {
            // 前面的内容已经发出去了，再复制整条只会重复。
            context.getString(R.string.stash_send_fail_partial)
        } else {
            val copied = runCatching { StashCoordinator.copyStashEntry(context, entry) }.getOrDefault(false)
            context.getString(result.failure.messageRes) +
                if (copied) context.getString(R.string.stash_send_copied_suffix) else ""
        }
        toast(context, message)
    }

    private fun toast(context: Context, message: String) {
        runCatching { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
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
