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

    /**
     * 单个窗口里的输入框，优先级见 [findEditCandidates]；强、弱候选都没有时为 null。
     */
    fun findEditText(root: ChatNode?): ChatNode? = findEditCandidates(root).best

    /**
     * 多个窗口里的输入框：**先**在所有窗口里比强候选，都没有才退到弱候选。
     *
     * 逐窗口取第一个非空结果会出错：活动窗口是个弹窗时，它里面某个已聚焦的按钮（弱候选）
     * 会盖过后面主聊天窗口里真正的 EditText。
     */
    fun findEditTextInAny(roots: List<ChatNode>): ChatNode? {
        var firstWeak: ChatNode? = null
        for (root in roots) {
            val candidates = findEditCandidates(root)
            candidates.strong?.let { return it }
            if (firstWeak == null) firstWeak = candidates.weak
        }
        return firstWeak
    }

    /**
     * 找输入框的候选。窗口内的优先级：
     * 拥有输入焦点的可编辑节点（P1）> 拥有无障碍焦点的可编辑节点（P2）> 类名含 EditText > 可编辑 >
     * 有 hint 且可用 > 已聚焦且可聚焦。前四类算“强”候选，后两类是“弱”候选（可能只是个搜索框之类）。
     */
    fun findEditCandidates(root: ChatNode?): EditCandidates {
        if (root == null) return EditCandidates(null, null)

        // P1：拥有输入焦点的节点——跨 ROM 最可靠，不要求节点可见。
        val focused = root.findInputFocus()
        if (focused != null && (focused.isEditable || focused.className.contains("Edit"))) {
            return EditCandidates(strong = focused, weak = null)
        }

        // P2：拥有无障碍焦点且可编辑的节点。
        val accessibilityFocused = root.findAccessibilityFocus()
        if (accessibilityFocused != null && accessibilityFocused.isEditable) {
            return EditCandidates(strong = accessibilityFocused, weak = null)
        }

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
        return EditCandidates(strong = byClass ?: byEditable, weak = byHint ?: byFocus)
    }

    /**
     * 找“发送”按钮，返回**应该被点击的节点**（命中节点自己不可点击时是它最近的可点击祖先）。
     *
     * 相比速聊的原实现有两处改进：候选不止取遍历到的第一个，而是取最靠下的一个（输入区在屏幕下方，
     * 聊天气泡在它上面），避免误点气泡里恰好写着“发送”“确定”的文字；点击目标会上溯到可点击的祖先。
     *
     * [allowFallback] 为 false 时只认“发送”类文案，不考虑“确定”兜底。
     */
    fun findSendButton(
        root: ChatNode?,
        labels: StashSendLabels = StashSendLabels.Default,
        allowFallback: Boolean = true
    ): ChatNode? = findSendButtonInAny(listOfNotNull(root), labels, allowFallback)

    /** 多个窗口一起找：候选放在一起比（“发送”类优先于“确定”，再取最靠下的），而不是逐窗口各选各的。 */
    fun findSendButtonInAny(
        roots: List<ChatNode>,
        labels: StashSendLabels = StashSendLabels.Default,
        allowFallback: Boolean = true
    ): ChatNode? {
        val matched = ArrayList<ChatNode>()
        val candidates = ArrayList<SendButtonCandidate>()
        for (root in roots) {
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
        }
        val chosen = selectSendButton(candidates, allowFallback) ?: return null
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

/** 一个窗口里找到的输入框候选：[strong] 基本可以确定就是输入框，[weak] 只是可能（比如带 hint 的搜索框）。 */
internal class EditCandidates(val strong: ChatNode?, val weak: ChatNode?) {
    val best: ChatNode? get() = strong ?: weak
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
 * 从候选里挑出真正的发送按钮，返回其下标；没有可用候选返回 null。
 *
 * 规则：有“发送”类候选时忽略“确定”兜底；[allowFallback] 为 false 时兜底候选一律不用；
 * 在剩下的候选里取最靠下的一个（输入区在屏幕下半部分、聊天气泡在它上面，
 * 所以“最靠下”就是“优先下半部分里最靠下的”），并列时取先遍历到的。
 */
internal fun selectSendButton(candidates: List<SendButtonCandidate>, allowFallback: Boolean = true): Int? {
    val primary = candidates.indices.filter { candidates[it].primary }
    val pool = when {
        primary.isNotEmpty() -> primary
        allowFallback -> candidates.indices.toList()
        else -> return null
    }
    if (pool.isEmpty()) return null
    var best = pool.first()
    for (i in pool) {
        if (candidates[i].centerY > candidates[best].centerY) best = i
    }
    return best
}
