package com.slideindex.app.stash

/**
 * 微信一键发送的经验延时（毫秒）。这些都是在真机上试出来的经验值，集中放在这里便于调参。
 */
internal object StashSendTiming {
    /** 点「发送」后先等一会儿，让面板收起、微信重新拿回窗口焦点。 */
    const val SETTLE_BEFORE_FIND_INPUT_MS = 200L

    /** 轮询输入框的间隔与总时长。 */
    const val FIND_INPUT_POLL_MS = 100L
    const val FIND_INPUT_TIMEOUT_MS = 1_500L

    /** 文字：ACTION_FOCUS 之后等一下再 SET_TEXT。 */
    const val TEXT_AFTER_FOCUS_MS = 50L

    /** 文字：SET_TEXT 失败改走剪贴板时，写入剪贴板后等一下再 PASTE。 */
    const val TEXT_AFTER_CLIPBOARD_MS = 80L

    /** 文字：填入后等输入区把“+”换成“发送”按钮。 */
    const val TEXT_BEFORE_SEND_BUTTON_MS = 150L

    /** 图片：ACTION_FOCUS 之后等一下再 PASTE。 */
    const val IMAGE_AFTER_FOCUS_MS = 80L

    /** 图片：粘贴后等微信弹出“发送”确认条。 */
    const val IMAGE_BEFORE_SEND_BUTTON_MS = 250L

    /** 找不到“发送”按钮时的重试次数与间隔。 */
    const val SEND_BUTTON_ATTEMPTS = 3
    const val SEND_BUTTON_RETRY_MS = 150L

    /** 相邻两块内容之间的间隔，等上一条消息发出去。 */
    const val BETWEEN_BLOCKS_MS = 1_500L
}

/**
 * 微信界面上的文案与包名。全是中文文案，微信改文案或系统语言不同时只需要改这里，
 * 也可以给 [StashSendLabels] 传别的取值来扩展。
 */
internal data class StashSendLabels(
    /** 输入区“发送”按钮的文字。 */
    val sendTexts: List<String> = listOf("发送"),
    /** 兜底文案：只有找不到任何“发送”时才会考虑。 */
    val fallbackTexts: List<String> = listOf("确定"),
    /** 带数量的“发送(3)”这类按钮。 */
    val sendPrefixes: List<String> = listOf("发送(", "发送（"),
    /** 图标按钮没有文字时，用 contentDescription 匹配。 */
    val sendDescriptions: List<String> = listOf("发送"),
) {
    companion object {
        const val PKG_WECHAT = "com.tencent.mm"

        val Default = StashSendLabels()
    }
}
