package com.slideindex.app.stash

/**
 * 在微信的无障碍树里找输入框和“发送”按钮。
 *
 * 移植自速聊（Quickchat）在真机上验证过的算法。刻意**不依赖微信控件的 resource-id**
 * （微信升级会变），只看类名、可编辑 / 焦点状态、文案和位置。
 */
internal object WechatNodeFinder {

    /** 命中的文字节点自己不可点击时，向上最多爬几层去找可点击的祖先。 */
    private const val MAX_CLICK_PARENT_HOPS = 4

    fun findEditText(root: ChatNode?): ChatNode? {
        if (root == null) return null

        // P1：拥有输入焦点的节点——跨 ROM 最可靠，不要求节点可见。
        val focused = root.findInputFocus()
        if (focused != null && (focused.isEditable || focused.className.contains("Edit"))) return focused

        // P2：拥有无障碍焦点且可编辑的节点。
        val accessibilityFocused = root.findAccessibilityFocus()
        if (accessibilityFocused != null && accessibilityFocused.isEditable) return accessibilityFocused

        // 整棵树 BFS（不按可见性过滤），按优先级取：类名含 EditText > 可编辑 > 有 hint 且可用 > 已聚焦且可聚焦。
        var byClass: ChatNode? = null
        var byEditable: ChatNode? = null
        var byHint: ChatNode? = null
        var byFocus: ChatNode? = null
        val queue = ArrayList<ChatNode>()
        queue += root
        var index = 0
        while (index < queue.size) {
            val node = queue[index++]
            val className = node.className
            if (byClass == null && className.contains("EditText")) {
                byClass = node
            } else if (byEditable == null && node.isEditable) {
                byEditable = node
            } else if (byHint == null && node.hintText.isNotEmpty() && node.isEnabled) {
                byHint = node
            }
            if (byFocus == null && node.isFocused && node.isFocusable) {
                byFocus = node
                // 当前焦点本身就是输入框：不会再有更好的了，不必继续遍历。
                if (node.isEditable || className.contains("Edit")) break
            }
            for (i in 0 until node.childCount) node.childAt(i)?.let { queue += it }
        }
        return byClass ?: byEditable ?: byHint ?: byFocus
    }

    /**
     * 找“发送”按钮，返回**应该被点击的节点**（命中节点自己不可点击时是它最近的可点击祖先）。
     *
     * 相比速聊的原实现有两处改进：候选不止取遍历到的第一个，而是优先选窗口下半部分（输入区）里
     * 最靠下的一个，避免误点聊天气泡里恰好写着“发送”“确定”的文字；点击目标会上溯到可点击的祖先。
     */
    fun findSendButton(root: ChatNode?, labels: StashSendLabels = StashSendLabels.Default): ChatNode? {
        if (root == null) return null
        val matched = ArrayList<ChatNode>()
        val candidates = ArrayList<SendButtonCandidate>()
        val queue = ArrayList<ChatNode>()
        queue += root
        var index = 0
        while (index < queue.size) {
            val node = queue[index++]
            if (node.isVisibleToUser) {
                val kind = labels.classify(node.text, node.contentDescription)
                if (kind != SendLabelKind.NONE) {
                    matched += node
                    candidates += SendButtonCandidate(
                        primary = kind == SendLabelKind.PRIMARY,
                        centerY = (node.top + node.bottom) / 2
                    )
                }
            }
            for (i in 0 until node.childCount) node.childAt(i)?.let { queue += it }
        }
        val chosen = selectSendButton(candidates, root.top, root.bottom) ?: return null
        return clickTargetOf(matched[chosen])
    }

    /** 命中节点自己或最近的可点击祖先；都不可点击时退回命中节点本身（照样尝试点它）。 */
    fun clickTargetOf(node: ChatNode): ChatNode {
        var current: ChatNode? = node
        var hops = 0
        while (current != null && hops <= MAX_CLICK_PARENT_HOPS) {
            if (current.isClickable) return current
            current = current.parent()
            hops++
        }
        return node
    }

    fun countNodes(root: ChatNode?): Int {
        if (root == null) return 0
        val queue = ArrayList<ChatNode>()
        queue += root
        var index = 0
        while (index < queue.size) {
            val node = queue[index++]
            for (i in 0 until node.childCount) node.childAt(i)?.let { queue += it }
        }
        return queue.size
    }
}

internal enum class SendLabelKind { NONE, PRIMARY, FALLBACK }

internal fun StashSendLabels.classify(text: String, description: String): SendLabelKind = when {
    text in sendTexts || sendPrefixes.any { text.startsWith(it) } || description in sendDescriptions ->
        SendLabelKind.PRIMARY
    text in fallbackTexts -> SendLabelKind.FALLBACK
    else -> SendLabelKind.NONE
}

/** 一个文案命中“发送”的可见节点。[primary] 为 false 表示只命中了“确定”这类兜底文案。 */
internal data class SendButtonCandidate(val primary: Boolean, val centerY: Int)

/**
 * 从候选里挑出真正的发送按钮，返回其下标；没有候选返回 null。
 *
 * 规则：有“发送”类候选时忽略“确定”兜底；候选里优先取窗口下半部分（输入区）的，
 * 再取其中最靠下的一个，并列时取先遍历到的。窗口边界未知（[windowBottom] <= [windowTop]）时不做下半部分筛选。
 */
internal fun selectSendButton(candidates: List<SendButtonCandidate>, windowTop: Int, windowBottom: Int): Int? {
    if (candidates.isEmpty()) return null
    val pool = candidates.indices.filter { candidates[it].primary }
        .ifEmpty { candidates.indices.toList() }
    val midY = (windowTop + windowBottom) / 2
    val lowerHalf = if (windowBottom > windowTop) pool.filter { candidates[it].centerY >= midY } else emptyList()
    val ranked = lowerHalf.ifEmpty { pool }
    var best = ranked.first()
    for (i in ranked) {
        if (candidates[i].centerY > candidates[best].centerY) best = i
    }
    return best
}
