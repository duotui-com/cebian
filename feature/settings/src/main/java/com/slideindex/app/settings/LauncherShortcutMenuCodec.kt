package com.slideindex.app.settings

/**
 * 桌面图标长按菜单顺序的落盘编码。
 *
 * 条目 id 只含 `[a-z_]`，用单元分隔符拼接即可保持顺序（stringSet 无序，不能直接用）。
 */
internal object LauncherShortcutMenuCodec {
    private const val SEP = "\u001F"

    fun encode(ids: List<String>): String = ids.joinToString(SEP)

    fun decode(raw: String?): List<String> =
        raw?.split(SEP)
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
}
