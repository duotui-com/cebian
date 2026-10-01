package com.slideindex.app.launcher

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import com.slideindex.app.MainActivity
import com.slideindex.app.R
import com.slideindex.app.overlay.StashPanelInitialTab
import com.slideindex.app.service.SearchPanelTrampolineActivity
import com.slideindex.app.service.ShellCommandPanelTrampolineActivity
import com.slideindex.app.service.StashClipboardTrampolineActivity
import com.slideindex.app.service.ToggleGestureTrampolineActivity

/**
 * 桌面图标长按菜单（App Shortcuts）里的可选项。
 *
 * [id] 同时是 ShortcutInfo 的 shortcutId，与系统排行/使用统计共用。
 */
enum class LauncherShortcutMenuEntry(
    val id: String,
    @StringRes val labelRes: Int,
) {
    TOGGLE_GESTURE("toggle_gesture", R.string.shortcut_toggle_gesture),
    NOTIFICATION_HUB("notification_hub", R.string.shortcut_notification_hub),
    SHELL_PANEL("shell_panel", R.string.shortcut_shell_panel),
    STASH_PANEL("stash_panel", R.string.shortcut_stash_panel),
    CLIPBOARD_PANEL("clipboard_panel", R.string.shortcut_clipboard_panel),
    SEARCH_PANEL("search_panel", R.string.shortcut_search_panel),
    ;

    companion object {
        fun fromId(id: String): LauncherShortcutMenuEntry? = entries.firstOrNull { it.id == id }
    }
}

object LauncherShortcutMenu {
    private const val ACTION_TOGGLE_GESTURE = "com.slideindex.app.action.TOGGLE_GESTURE"
    private const val ACTION_OPEN_SHELL_PANEL = "com.slideindex.app.action.OPEN_SHELL_PANEL"

    /** 默认顺序：搜索面板放最前，系统上限较小（如 5 条）的机型上也不会被挤掉。 */
    val defaultOrder: List<String> =
        listOf(LauncherShortcutMenuEntry.SEARCH_PANEL.id) +
            LauncherShortcutMenuEntry.entries
                .filterNot { it == LauncherShortcutMenuEntry.SEARCH_PANEL }
                .map { it.id }

    /**
     * 归一化已保存的顺序：丢弃未知 id、去掉重复，并把新版本新增的条目补到末尾。
     * 返回顺序即长按菜单的展示顺序。
     */
    fun normalizedOrder(saved: List<String>): List<String> {
        val known = LauncherShortcutMenuEntry.entries.map { it.id }
        val kept = saved.filter { it in known }.distinct()
        return kept + known.filterNot { it in kept }
    }

    fun buildIntent(context: Context, entry: LauncherShortcutMenuEntry): Intent = when (entry) {
        LauncherShortcutMenuEntry.TOGGLE_GESTURE ->
            Intent(context, ToggleGestureTrampolineActivity::class.java)
                .setAction(ACTION_TOGGLE_GESTURE)

        LauncherShortcutMenuEntry.NOTIFICATION_HUB ->
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_NOTIFICATION_HISTORY)

        LauncherShortcutMenuEntry.SHELL_PANEL ->
            ShellCommandPanelTrampolineActivity.createIntent(context)
                .setAction(ACTION_OPEN_SHELL_PANEL)

        LauncherShortcutMenuEntry.STASH_PANEL ->
            StashClipboardTrampolineActivity.createIntent(context, StashPanelInitialTab.Stash)

        LauncherShortcutMenuEntry.CLIPBOARD_PANEL ->
            StashClipboardTrampolineActivity.createIntent(context, StashPanelInitialTab.Clipboard)

        LauncherShortcutMenuEntry.SEARCH_PANEL ->
            SearchPanelTrampolineActivity.createIntent(context)
                .setAction(SearchPanelTrampolineActivity.ACTION_OPEN_SEARCH_PANEL)
    }
}
