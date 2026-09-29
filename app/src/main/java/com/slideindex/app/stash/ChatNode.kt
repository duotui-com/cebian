package com.slideindex.app.stash

/**
 * 聊天界面里一个无障碍节点的最小视图。
 *
 * 把 AccessibilityNodeInfo 藏在这层后面，查找输入框 / 发送按钮的算法就是纯 JVM 逻辑，
 * 可以用假的节点树做单元测试；真实实现见 [AccessibilityChatNode]。
 */
internal interface ChatNode {
    val className: String
    val text: String
    val contentDescription: String
    val hintText: String
    val isEditable: Boolean
    val isEnabled: Boolean
    val isFocused: Boolean
    val isFocusable: Boolean
    val isClickable: Boolean
    val isVisibleToUser: Boolean

    /** 节点在屏幕坐标系下的上 / 下边缘。 */
    val top: Int
    val bottom: Int

    val childCount: Int

    fun childAt(index: Int): ChatNode?
    fun parent(): ChatNode?

    /** 以本节点为根，找当前拥有输入焦点 / 无障碍焦点的节点。 */
    fun findInputFocus(): ChatNode?
    fun findAccessibilityFocus(): ChatNode?

    fun focus(): Boolean
    fun setText(text: String): Boolean
    fun paste(): Boolean
    fun click(): Boolean
}

/** 目标聊天 App 当前的窗口。 */
internal interface ChatWindow {
    /** 属于目标 App 的窗口根节点；当前活动窗口在前，其余窗口在后。目标 App 不在屏幕上时为空。 */
    fun targetRoots(): List<ChatNode>
}

/** 发送用到的系统剪贴板。 */
internal interface ChatClipboard {
    fun setText(text: String)

    /** 把暂存夹里的图片放上剪贴板，并授权目标 App 读取；失败返回 false。 */
    fun setImage(fileName: String): Boolean
}
