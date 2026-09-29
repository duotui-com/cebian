package com.slideindex.app.stash

/** 单测用的假节点：可搭出一棵树，并记录对它执行过的动作。 */
internal class FakeChatNode(
    override val className: String = "android.view.View",
    override var text: String = "",
    override val contentDescription: String = "",
    override val hintText: String = "",
    override val isEditable: Boolean = false,
    override val isEnabled: Boolean = true,
    override val isFocused: Boolean = false,
    override val isFocusable: Boolean = false,
    override val isClickable: Boolean = false,
    override var isVisibleToUser: Boolean = true,
    override val top: Int = 0,
    override val bottom: Int = 0,
) : ChatNode {
    val children = mutableListOf<FakeChatNode>()
    private var parentNode: FakeChatNode? = null

    /** 作为根节点时，`findFocus` 返回的节点。 */
    var inputFocus: FakeChatNode? = null
    var accessibilityFocus: FakeChatNode? = null

    var setTextResult = true
    var pasteResult = true
    var clickResult = true

    /** 动作流水，如 `focus`、`setText:你好`、`paste`、`click`。 */
    val actions = mutableListOf<String>()

    /** 动作执行后的回调，用来模拟微信界面的联动（比如填入文字后出现“发送”按钮）。 */
    var onSetText: ((String) -> Unit)? = null
    var onPaste: (() -> Unit)? = null

    fun add(child: FakeChatNode): FakeChatNode {
        child.parentNode = this
        children += child
        return child
    }

    override val childCount: Int get() = children.size
    override fun childAt(index: Int): ChatNode? = children.getOrNull(index)
    override fun parent(): ChatNode? = parentNode
    override fun findInputFocus(): ChatNode? = inputFocus
    override fun findAccessibilityFocus(): ChatNode? = accessibilityFocus

    override fun focus(): Boolean {
        actions += "focus"
        return true
    }

    override fun setText(text: String): Boolean {
        actions += "setText:$text"
        if (setTextResult) {
            onSetText?.invoke(text)
        }
        return setTextResult
    }

    override fun paste(): Boolean {
        actions += "paste"
        onPaste?.invoke()
        return pasteResult
    }

    override fun click(): Boolean {
        actions += "click"
        return clickResult
    }
}

/** 单测用的窗口：每次取根节点都调用 [roots]，便于模拟界面随时间变化。 */
internal class FakeChatWindow(private val roots: () -> List<ChatNode>) : ChatWindow {
    constructor(root: ChatNode) : this({ listOf(root) })

    var calls = 0
        private set

    override fun targetRoots(): List<ChatNode> {
        calls++
        return roots()
    }
}

internal class FakeChatClipboard : ChatClipboard {
    val events = mutableListOf<String>()
    var textResult = true
    var imageResult = true

    override fun setText(text: String): Boolean {
        events += "text:$text"
        return textResult
    }

    override fun setImage(fileName: String): Boolean {
        events += "image:$fileName"
        return imageResult
    }
}
