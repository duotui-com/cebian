package com.slideindex.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 小窗尺寸位置编辑层的开关。
 *
 * 放在进程级单例里而不是某个页面的组合状态里：编辑层挂在 App 根部（导航宿主之上），
 * 打开/关闭由设置页触发，同时外壳布局要据此冻结，避免转屏时底栏↔侧栏切换把编辑层重建。
 */
object FreeWindowLayoutEditorSession {
    var isOpen by mutableStateOf(false)
        private set

    fun open() {
        isOpen = true
    }

    fun close() {
        isOpen = false
    }
}
