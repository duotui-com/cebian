package com.slideindex.app.stash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class WechatNodeFinderTest {

    // ───────────────────────── selectSendButton（纯函数）─────────────────────────

    private fun candidate(centerY: Int, primary: Boolean = true) = SendButtonCandidate(primary, centerY)

    @Test
    fun selectSendButton_noCandidates_isNull() {
        assertNull(selectSendButton(emptyList()))
    }

    @Test
    fun selectSendButton_prefersPrimaryOverFallback() {
        // “确定”只是兜底：哪怕它更靠下，只要有“发送”就选“发送”。
        val candidates = listOf(candidate(1000, primary = true), candidate(1900, primary = false))

        assertEquals(0, selectSendButton(candidates))
    }

    @Test
    fun selectSendButton_usesFallbackWhenNothingElse() {
        val candidates = listOf(candidate(500, primary = false), candidate(1800, primary = false))

        assertEquals(1, selectSendButton(candidates))
    }

    @Test
    fun selectSendButton_fallbackNotAllowed_ignoresFallbackCandidates() {
        val onlyFallback = listOf(candidate(500, primary = false), candidate(1800, primary = false))
        val mixed = listOf(candidate(1000, primary = true), candidate(1900, primary = false))

        assertNull(selectSendButton(onlyFallback, allowFallback = false))
        assertEquals(0, selectSendButton(mixed, allowFallback = false))
    }

    @Test
    fun selectSendButton_picksTheLowest_soChatBubbleAboveTheInputAreaLoses() {
        // 聊天气泡里的“发送”在上面，输入区的按钮在最下面。
        val candidates = listOf(candidate(400), candidate(1850))

        assertEquals(1, selectSendButton(candidates))
    }

    @Test
    fun selectSendButton_amongSeveralPicksTheLowest() {
        val candidates = listOf(candidate(1200), candidate(1850), candidate(1500))

        assertEquals(1, selectSendButton(candidates))
    }

    @Test
    fun selectSendButton_ties_pickFirstTraversed() {
        val candidates = listOf(candidate(1500), candidate(1500))

        assertEquals(0, selectSendButton(candidates))
    }

    // ───────────────────────── findSendButton（假节点树）─────────────────────────

    /** 一个 1000 高的微信窗口：上面是聊天列表，下面是输入区。 */
    private fun chatRoot() = FakeChatNode(className = "android.widget.FrameLayout", top = 0, bottom = 1000)

    @Test
    fun findSendButton_ignoresBubbleTextAndPicksInputAreaButton() {
        val root = chatRoot()
        val list = root.add(FakeChatNode(className = "androidx.recyclerview.widget.RecyclerView", top = 0, bottom = 850))
        // 对方发来的一条消息，内容恰好就是“发送”。
        val bubble = list.add(FakeChatNode(className = "android.widget.TextView", text = "发送", top = 700, bottom = 760))
        val input = root.add(FakeChatNode(className = "android.widget.LinearLayout", top = 850, bottom = 1000))
        val button = input.add(
            FakeChatNode(className = "android.widget.Button", text = "发送", isClickable = true, top = 900, bottom = 980)
        )

        val target = WechatNodeFinder.findSendButton(root)

        assertSame(button, target)
        assertEquals(emptyList<String>(), bubble.actions)
    }

    @Test
    fun findSendButton_climbsToClickableAncestor() {
        val root = chatRoot()
        val container = root.add(FakeChatNode(className = "android.widget.FrameLayout", isClickable = true, top = 900, bottom = 980))
        val label = container.add(FakeChatNode(className = "android.widget.TextView", text = "发送", top = 900, bottom = 980))

        val target = WechatNodeFinder.findSendButton(root)

        assertSame(container, target)
        assertEquals(false, target === label)
    }

    @Test
    fun findSendButton_noClickableAncestor_fallsBackToTheNodeItself() {
        val root = chatRoot()
        val label = root.add(FakeChatNode(className = "android.widget.TextView", text = "发送", top = 900, bottom = 980))

        assertSame(label, WechatNodeFinder.findSendButton(root))
    }

    @Test
    fun findSendButton_matchesPrefixAndContentDescription() {
        val withCount = chatRoot().also {
            it.add(FakeChatNode(text = "发送(3)", isClickable = true, top = 900, bottom = 980))
        }
        val iconOnly = chatRoot().also {
            it.add(FakeChatNode(contentDescription = "发送", isClickable = true, top = 900, bottom = 980))
        }

        assertEquals("发送(3)", (WechatNodeFinder.findSendButton(withCount) as FakeChatNode).text)
        assertEquals("发送", (WechatNodeFinder.findSendButton(iconOnly) as FakeChatNode).contentDescription)
    }

    @Test
    fun findSendButton_ignoresInvisibleNodes() {
        val root = chatRoot()
        root.add(FakeChatNode(text = "发送", isClickable = true, isVisibleToUser = false, top = 900, bottom = 980))

        assertNull(WechatNodeFinder.findSendButton(root))
    }

    @Test
    fun findSendButton_fallbackTextOnlyWhenNoSendLabel() {
        val onlyConfirm = chatRoot().also {
            it.add(FakeChatNode(text = "确定", isClickable = true, top = 900, bottom = 980))
        }
        val both = chatRoot()
        both.add(FakeChatNode(text = "确定", isClickable = true, top = 950, bottom = 990))
        val send = both.add(FakeChatNode(text = "发送", isClickable = true, top = 900, bottom = 940))

        assertEquals("确定", (WechatNodeFinder.findSendButton(onlyConfirm) as FakeChatNode).text)
        assertSame(send, WechatNodeFinder.findSendButton(both))
    }

    @Test
    fun findSendButton_nullOrNoMatch_isNull() {
        assertNull(WechatNodeFinder.findSendButton(null))
        assertNull(WechatNodeFinder.findSendButton(chatRoot().also { it.add(FakeChatNode(text = "语音")) }))
    }

    @Test
    fun findSendButton_customLabelsCanExtendTheMatching() {
        val root = chatRoot()
        val button = root.add(FakeChatNode(text = "Enviar", isClickable = true, top = 900, bottom = 980))
        val spanish = StashSendLabels(sendTexts = listOf("发送", "Enviar"))

        assertNull(WechatNodeFinder.findSendButton(root))
        assertSame(button, WechatNodeFinder.findSendButton(root, spanish))
    }

    // ───────────────────────── 多窗口 / 兜底开关 ─────────────────────────

    @Test
    fun findSendButton_fallbackNotAllowed_skipsConfirmText() {
        val root = chatRoot()
        root.add(FakeChatNode(text = "确定", isClickable = true, top = 900, bottom = 980))

        assertNull(WechatNodeFinder.findSendButton(root, allowFallback = false))
        assertEquals("确定", (WechatNodeFinder.findSendButton(root, allowFallback = true) as FakeChatNode).text)
    }

    @Test
    fun findSendButtonInAny_primaryInLaterWindowBeatsFallbackInEarlierWindow() {
        val dialog = chatRoot()
        val ok = dialog.add(FakeChatNode(text = "确定", isClickable = true, top = 1100, bottom = 1180))
        val main = chatRoot()
        val send = main.add(FakeChatNode(text = "发送", isClickable = true, top = 900, bottom = 980))

        val target = WechatNodeFinder.findSendButtonInAny(listOf(dialog, main))

        assertSame(send, target)
        assertEquals(false, target === ok)
    }

    @Test
    fun findSendButtonInAny_comparesCandidatesAcrossWindowsByHeight() {
        val upper = chatRoot()
        upper.add(FakeChatNode(text = "发送", isClickable = true, top = 300, bottom = 380))
        val lower = chatRoot()
        val lowest = lower.add(FakeChatNode(text = "发送", isClickable = true, top = 900, bottom = 980))

        assertSame(lowest, WechatNodeFinder.findSendButtonInAny(listOf(upper, lower)))
    }

    @Test
    fun findSendButtonInAny_noRoots_isNull() {
        assertNull(WechatNodeFinder.findSendButtonInAny(emptyList()))
    }

    @Test
    fun findEditTextInAny_strongCandidateInLaterWindowBeatsWeakOneInEarlierWindow() {
        // 活动窗口是个弹窗，里面有个已聚焦的按钮（弱候选）；主窗口里才是真正的 EditText。
        val popup = chatRoot()
        val weak = popup.add(FakeChatNode(className = "android.widget.Button", isFocused = true, isFocusable = true))
        val main = chatRoot()
        val real = main.add(FakeChatNode(className = "android.widget.EditText", isEditable = true))

        val found = WechatNodeFinder.findEditTextInAny(listOf(popup, main))

        assertSame(real, found)
        assertEquals(false, found === weak)
    }

    @Test
    fun findEditTextInAny_onlyWeakCandidates_takesTheFirstWindowsOne() {
        val first = chatRoot()
        val weakA = first.add(FakeChatNode(hintText = "搜索", isEnabled = true))
        val second = chatRoot()
        second.add(FakeChatNode(hintText = "输入消息", isEnabled = true))

        assertSame(weakA, WechatNodeFinder.findEditTextInAny(listOf(first, second)))
    }

    @Test
    fun findEditTextInAny_inputFocusInAnyWindowIsStrong() {
        val popup = chatRoot()
        popup.add(FakeChatNode(hintText = "搜索", isEnabled = true))
        val main = chatRoot()
        val focused = main.add(FakeChatNode(className = "android.widget.EditText", isEditable = true, isFocused = true))
        main.inputFocus = focused

        assertSame(focused, WechatNodeFinder.findEditTextInAny(listOf(popup, main)))
    }

    @Test
    fun findEditTextInAny_noRoots_isNull() {
        assertNull(WechatNodeFinder.findEditTextInAny(emptyList()))
    }

    @Test
    fun findEditCandidates_separatesStrongFromWeak() {
        val hintOnly = chatRoot().also { it.add(FakeChatNode(hintText = "搜索", isEnabled = true)) }
        val editable = chatRoot().also { it.add(FakeChatNode(isEditable = true)) }

        assertNull(WechatNodeFinder.findEditCandidates(hintOnly).strong)
        assertEquals(true, WechatNodeFinder.findEditCandidates(hintOnly).weak != null)
        assertEquals(true, WechatNodeFinder.findEditCandidates(editable).strong != null)
        assertNull(WechatNodeFinder.findEditCandidates(null).best)
    }

    // ───────────────────────── findEditText（假节点树）─────────────────────────

    @Test
    fun findEditText_prefersInputFocusedEditableNode() {
        val root = chatRoot()
        val other = root.add(FakeChatNode(className = "android.widget.EditText"))
        val focused = root.add(FakeChatNode(className = "android.widget.EditText", isEditable = true, isFocused = true))
        root.inputFocus = focused

        assertSame(focused, WechatNodeFinder.findEditText(root))
        assertEquals(false, WechatNodeFinder.findEditText(root) === other)
    }

    @Test
    fun findEditText_inputFocusOnNonEditableNode_isIgnored() {
        val root = chatRoot()
        val editText = root.add(FakeChatNode(className = "android.widget.EditText"))
        root.inputFocus = root.add(FakeChatNode(className = "android.widget.TextView", isFocused = true))

        assertSame(editText, WechatNodeFinder.findEditText(root))
    }

    @Test
    fun findEditText_accessibilityFocusMustBeEditable() {
        val root = chatRoot()
        val editable = root.add(FakeChatNode(className = "android.view.View", isEditable = true))
        root.accessibilityFocus = editable

        assertSame(editable, WechatNodeFinder.findEditText(root))
    }

    @Test
    fun findEditText_priority_className_then_editable_then_hint_then_focus() {
        val byFocus = FakeChatNode(className = "android.view.View", isFocused = true, isFocusable = true)
        val byHint = FakeChatNode(className = "android.view.View", hintText = "输入消息", isEnabled = true)
        val byEditable = FakeChatNode(className = "android.view.View", isEditable = true)
        val byClass = FakeChatNode(className = "android.widget.EditText")

        fun rootWith(vararg nodes: FakeChatNode) = chatRoot().also { root -> nodes.forEach { root.add(it) } }

        assertSame(byClass, WechatNodeFinder.findEditText(rootWith(byFocus, byHint, byEditable, byClass)))
        assertSame(byEditable, WechatNodeFinder.findEditText(rootWith(byFocus, byHint, byEditable)))
        assertSame(byHint, WechatNodeFinder.findEditText(rootWith(byFocus, byHint)))
        assertSame(byFocus, WechatNodeFinder.findEditText(rootWith(byFocus)))
    }

    @Test
    fun findEditText_doesNotRequireVisibility() {
        val root = chatRoot()
        val hidden = root.add(FakeChatNode(className = "android.widget.EditText", isVisibleToUser = false))

        assertSame(hidden, WechatNodeFinder.findEditText(root))
    }

    @Test
    fun findEditText_hintOnDisabledNodeDoesNotCount() {
        val root = chatRoot()
        root.add(FakeChatNode(hintText = "输入消息", isEnabled = false))

        assertNull(WechatNodeFinder.findEditText(root))
    }

    @Test
    fun findEditText_findsNestedNodeBreadthFirst() {
        val root = chatRoot()
        val nestedDeep = root.add(FakeChatNode()).add(FakeChatNode()).add(FakeChatNode(className = "android.widget.EditText"))

        assertSame(nestedDeep, WechatNodeFinder.findEditText(root))
    }

    @Test
    fun findEditText_nullOrEmptyTree_isNull() {
        assertNull(WechatNodeFinder.findEditText(null))
        assertNull(WechatNodeFinder.findEditText(FakeChatNode()))
    }

    @Test
    fun countNodes_countsWholeTree() {
        val root = chatRoot()
        root.add(FakeChatNode()).add(FakeChatNode())
        root.add(FakeChatNode())

        assertEquals(4, WechatNodeFinder.countNodes(root))
        assertEquals(1, WechatNodeFinder.countNodes(FakeChatNode()))
        assertEquals(0, WechatNodeFinder.countNodes(null))
    }

    // ───────────────────────── 文案分类 ─────────────────────────

    @Test
    fun classify_distinguishesPrimaryFallbackAndNone() {
        val labels = StashSendLabels.Default

        assertEquals(SendLabelKind.PRIMARY, labels.classify("发送", ""))
        assertEquals(SendLabelKind.PRIMARY, labels.classify("发送(2)", ""))
        assertEquals(SendLabelKind.PRIMARY, labels.classify("", "发送"))
        assertEquals(SendLabelKind.FALLBACK, labels.classify("确定", ""))
        assertEquals(SendLabelKind.NONE, labels.classify("已发送", ""))
        assertEquals(SendLabelKind.NONE, labels.classify("", ""))
    }

    @Test
    fun classify_knowsTheCommonSendLabelsOfOtherLanguages() {
        val labels = StashSendLabels.Default

        assertEquals(SendLabelKind.PRIMARY, labels.classify("Send", ""))
        assertEquals(SendLabelKind.PRIMARY, labels.classify("送信", ""))
        assertEquals(SendLabelKind.PRIMARY, labels.classify("傳送", ""))
        assertEquals(SendLabelKind.PRIMARY, labels.classify("發送(2)", ""))
        assertEquals(SendLabelKind.FALLBACK, labels.classify("確定", ""))
        // 只认整个文案，不认包含关系。
        assertEquals(SendLabelKind.NONE, labels.classify("Sender", ""))
    }
}
