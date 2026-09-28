package com.slideindex.app.clipboard.monitor

/**
 * Based on [ClipboardListener](https://github.com/aa2013/ClipboardListener) (MIT).
 */
import android.app.Application
import android.content.Context

internal object ClipboardMonitorProcess {
    /**
     * 单进程后剪贴板监听与 UI/无障碍在同一个进程，**本进程就是监听进程**。
     *
     * 这条判定不能留 false：`ClipboardMonitorController.start/restart` 在"非监听进程"时
     * 会直接 `return false`，判定恒 false 等于监听永远起不来（合并后必须为 true）。
     */
    fun isMonitorProcess(): Boolean = true
}
