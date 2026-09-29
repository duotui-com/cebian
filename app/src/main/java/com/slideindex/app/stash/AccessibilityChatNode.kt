package com.slideindex.app.stash

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.slideindex.app.clipboard.ClipboardWriter
import com.slideindex.app.clipboard.monitor.ClipboardMonitorController

/** [ChatNode] 的真实实现，包装一个 [AccessibilityNodeInfo]。 */
internal class AccessibilityChatNode(private val raw: AccessibilityNodeInfo) : ChatNode {
    override val className: String get() = raw.className?.toString().orEmpty()
    override val text: String get() = raw.text?.toString().orEmpty()
    override val contentDescription: String get() = raw.contentDescription?.toString().orEmpty()
    override val hintText: String get() = raw.hintText?.toString().orEmpty()
    override val isEditable: Boolean get() = raw.isEditable
    override val isEnabled: Boolean get() = raw.isEnabled
    override val isFocused: Boolean get() = raw.isFocused
    override val isFocusable: Boolean get() = raw.isFocusable
    override val isClickable: Boolean get() = raw.isClickable
    override val isVisibleToUser: Boolean get() = raw.isVisibleToUser

    override val top: Int get() = bounds().top
    override val bottom: Int get() = bounds().bottom

    override val childCount: Int get() = raw.childCount

    override fun childAt(index: Int): ChatNode? = raw.getChild(index)?.let(::AccessibilityChatNode)

    override fun parent(): ChatNode? = raw.parent?.let(::AccessibilityChatNode)

    override fun findInputFocus(): ChatNode? =
        raw.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let(::AccessibilityChatNode)

    override fun findAccessibilityFocus(): ChatNode? =
        raw.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.let(::AccessibilityChatNode)

    override fun focus(): Boolean = raw.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

    override fun setText(text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return raw.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    override fun paste(): Boolean = raw.performAction(AccessibilityNodeInfo.ACTION_PASTE)

    override fun click(): Boolean = raw.performAction(AccessibilityNodeInfo.ACTION_CLICK)

    private fun bounds(): Rect = Rect().also(raw::getBoundsInScreen)
}

/**
 * 目标 App 的窗口。
 *
 * 优先取 `rootInActiveWindow`；它不属于目标 App（比如输入法或我们自己的面板抢了活动窗口）时，
 * 遍历 `windows`（需要 flagRetrieveInteractiveWindows）找属于目标 App 的窗口。
 * 与速聊不同：本应用的无障碍服务没有包名限制，所以**绝不**退回到别的 App 的根节点，
 * 否则可能把话术填进别的应用的输入框。
 */
internal class AccessibilityChatWindow(
    private val service: AccessibilityService,
    private val targetPackage: String
) : ChatWindow {

    override fun targetRoots(): List<ChatNode> {
        // 无障碍服务随时可能在别的线程被系统断开，这里任何一次 Binder 调用都可能抛异常：
        // 取不到就当作“目标 App 不在屏幕上”，别让异常一路冒到发送流程外面。
        val active = runCatching { service.rootInActiveWindow }.getOrNull()
        val others = runCatching { service.windows }.getOrNull().orEmpty()
            .mapNotNull { window -> runCatching { window.root }.getOrNull() }
        return pickTargetRoots(active, others, { it.packageName?.toString() }, targetPackage)
            .map(::AccessibilityChatNode)
    }
}

/**
 * 从活动窗口和其余窗口里挑出属于 [targetPackage] 的根节点：活动窗口在前，其余按原顺序在后，重复的去掉。
 *
 * **只**返回包名恰好等于 [targetPackage] 的节点，没有任何“退回别的 App”的路径。
 */
internal fun <T : Any> pickTargetRoots(
    active: T?,
    others: List<T>,
    packageNameOf: (T) -> String?,
    targetPackage: String
): List<T> {
    val roots = ArrayList<T>(2)
    if (active != null && packageNameOf(active) == targetPackage) roots += active
    for (root in others) {
        if (packageNameOf(root) != targetPackage) continue
        if (root in roots) continue
        roots += root
    }
    return roots
}

/** 往系统剪贴板放文字 / 暂存夹里的图片。 */
internal class SystemChatClipboard(
    private val context: Context,
    private val repository: StashRepository?,
    private val targetPackage: String
) : ChatClipboard {

    override fun setText(text: String): Boolean {
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return false
        suppressOwnWriteEvent()
        return runCatching { manager.setPrimaryClip(ClipData.newPlainText("text", text)) }.isSuccess
    }

    override fun setImage(fileName: String): Boolean {
        val uri = repository?.uriForFile(fileName) ?: return false
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return false
        // 暂存夹的图片都以 PNG 存盘；直接声明 mime，不依赖 ContentResolver 反查。
        val clip = ClipData(ClipDescription("Image", arrayOf("image/png")), ClipData.Item(uri))
        // 必须授权目标 App 读取这个 content:// URI，否则微信粘贴时读不到图片。
        ClipboardWriter.grantClipReadToPackage(context, clip, targetPackage)
        suppressOwnWriteEvent()
        return runCatching { manager.setPrimaryClip(clip) }.isSuccess
    }

    /**
     * 告诉剪贴板监听：接下来这次剪贴板变化是我们自己写的，别当成新内容入历史。
     *
     * 不只是不想留下一条多余的历史：非标准监听模式下，监听服务看到剪贴板变化会临时弹一个可聚焦的探针窗口
     * 去读内容，而微信只有拿着窗口焦点才能读剪贴板——这个时间窗正好和发送流程里紧接着的“粘贴”撞上。
     */
    private fun suppressOwnWriteEvent() {
        runCatching { ClipboardMonitorController.peek()?.config?.ignoreOwnClipboardWrite() }
    }
}
