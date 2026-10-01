@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.slideindex.app.ui

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.slideindex.app.R
import com.slideindex.app.launcher.LauncherShortcutMenu
import com.slideindex.app.launcher.LauncherShortcutMenuEntry
import com.slideindex.app.ui.miuix.miuixGroupedCardItem
import com.slideindex.app.ui.settings.components.LazySettingsItem
import com.slideindex.app.ui.settings.components.SettingsLazyScreenScaffold
import com.slideindex.app.ui.settings.components.SettingsVerticalReorderList
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * 桌面图标长按菜单（App Shortcuts）管理：开关控制是否显示，长按拖动调整顺序。
 *
 * 页面说明用 [SettingsLazyScreenScaffold] 的白卡片（pageHint），不做成蓝色 tip。
 */
@Composable
fun LauncherShortcutMenuScreen(
    launcherShortcutOrder: List<String>,
    launcherShortcutDisabled: Set<String>,
    onLauncherShortcutOrderChange: (List<String>) -> Unit,
    onLauncherShortcutDisabledChange: (Set<String>) -> Unit,
    onBack: () -> Unit,
) {
    val entries = remember(launcherShortcutOrder) {
        LauncherShortcutMenu.normalizedOrder(launcherShortcutOrder)
            .mapNotNull(LauncherShortcutMenuEntry::fromId)
    }

    SettingsLazyScreenScaffold(
        title = stringResource(R.string.launcher_shortcut_menu_entry_title),
        pageHint = stringResource(R.string.launcher_shortcut_menu_hint),
        onBack = onBack,
    ) {
        LazySettingsItem(key = "launcher_shortcut_menu_list") {
            SettingsVerticalReorderList(
                items = entries,
                key = { it.id },
                onReorder = { reordered ->
                    onLauncherShortcutOrderChange(reordered.map { it.id })
                },
            ) { entry, _, segmentIndex, segmentCount, dragModifier ->
                SwitchPreference(
                    modifier = dragModifier.miuixGroupedCardItem(segmentIndex, segmentCount),
                    title = stringResource(entry.labelRes),
                    checked = entry.id !in launcherShortcutDisabled,
                    onCheckedChange = { enabled ->
                        onLauncherShortcutDisabledChange(
                            if (enabled) {
                                launcherShortcutDisabled - entry.id
                            } else {
                                launcherShortcutDisabled + entry.id
                            }
                        )
                    },
                )
            }
        }
    }
}
