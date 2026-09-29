package com.slideindex.app.launcher

import android.content.Context
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.slideindex.app.R

/**
 * 按设置推送桌面图标长按菜单的动态快捷方式。
 *
 * 设置页改完立即调用一次，避免"改完要重开应用才生效"。
 */
object LauncherShortcutsApplier {
    private const val TAG = "LauncherShortcuts"

    fun sync(context: Context, order: List<String>, disabled: Set<String>) {
        val appContext = context.applicationContext
        val maxCount = runCatching {
            ShortcutManagerCompat.getMaxShortcutCountPerActivity(appContext)
        }.getOrDefault(0).coerceAtLeast(1)

        val shortcuts = LauncherShortcutMenu.normalizedOrder(order)
            .asSequence()
            .filterNot { it in disabled }
            .mapNotNull(LauncherShortcutMenuEntry::fromId)
            .take(maxCount)
            .map { entry ->
                ShortcutInfoCompat.Builder(appContext, entry.id)
                    .setShortLabel(appContext.getString(entry.labelRes))
                    .setIcon(IconCompat.createWithResource(appContext, R.mipmap.ic_launcher))
                    .setIntent(LauncherShortcutMenu.buildIntent(appContext, entry))
                    .build()
            }
            .toList()

        runCatching {
            ShortcutManagerCompat.setDynamicShortcuts(appContext, shortcuts)
        }.onFailure {
            Log.w(TAG, "setDynamicShortcuts failed", it)
        }

        // 用户把快捷方式拖到桌面后，系统保存的是当时那个 Intent 的副本，
        // setDynamicShortcuts 只替换动态列表，不会改写已固定的实例。用同一批 id 再调一次
        // updateShortcuts，把固定实例里的旧 deeplink 一起迁移掉；否则旧的「剪贴板」快捷方式
        // 会因为 resolveInitialTab 的兜底分支静默变成「收纳夹」。
        runCatching {
            ShortcutManagerCompat.updateShortcuts(appContext, shortcuts)
        }.onFailure {
            Log.w(TAG, "updateShortcuts failed", it)
        }
    }
}
