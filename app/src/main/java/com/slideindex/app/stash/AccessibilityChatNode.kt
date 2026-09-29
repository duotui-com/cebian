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
        val roots = ArrayList<ChatNode>(2)
        val active = service.rootInActiveWindow
        if (active != null && active.packageName?.toString() == targetPackage) {
            roots += AccessibilityChatNode(active)
        }
        runCatching {
            for (window in service.windows) {
                val root = window.root ?: continue
                if (root.packageName?.toString() != targetPackage) continue
                if (active != null && root == active) continue
                roots += AccessibilityChatNode(root)
            }
        }
        return roots
    }
}

/** 往系统剪贴板放文字 / 暂存夹里的图片。 */
internal class SystemChatClipboard(
    private val context: Context,
    private val repository: StashRepository?,
    private val targetPackage: String
) : ChatClipboard {

    override fun setText(text: String) {
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return
        runCatching { manager.setPrimaryClip(ClipData.newPlainText("text", text)) }
    }

    override fun setImage(fileName: String): Boolean {
        val uri = repository?.uriForFile(fileName) ?: return false
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return false
        // 暂存夹的图片都以 PNG 存盘；直接声明 mime，不依赖 ContentResolver 反查。
        val clip = ClipData(ClipDescription("Image", arrayOf("image/png")), ClipData.Item(uri))
        // 必须授权目标 App 读取这个 content:// URI，否则微信粘贴时读不到图片。
        ClipboardWriter.grantClipReadToPackage(context, clip, targetPackage)
        return runCatching { manager.setPrimaryClip(clip) }.isSuccess
    }
}
