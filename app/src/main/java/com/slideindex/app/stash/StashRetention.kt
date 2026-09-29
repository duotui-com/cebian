package com.slideindex.app.stash

/**
 * 暂存夹条目的保留规则（纯函数，与存储解耦，便于单测）。
 *
 * 带分类（[StashEntry.categoryId] 非空）的条目是“话术”，长期保留：
 * 不占普通暂存的名额、不被自动淘汰，也不会被“清空暂存夹”清掉。
 */
internal object StashRetention {

    /** [kept] 是仍保留的条目（保持原顺序），[removed] 是被淘汰 / 被清掉的条目。 */
    class Split(val kept: List<StashEntry>, val removed: List<StashEntry>)

    /** 只对普通条目计数：保留列表里靠前（较新）的 [maxPlain] 条普通条目，其余淘汰；带分类的条目一律保留。 */
    fun trimPlain(entries: List<StashEntry>, maxPlain: Int): Split {
        if (entries.count { it.categoryId == null } <= maxPlain) return Split(entries, emptyList())
        val kept = ArrayList<StashEntry>(entries.size)
        val removed = ArrayList<StashEntry>()
        var plainKept = 0
        for (entry in entries) {
            when {
                entry.categoryId != null -> kept += entry
                plainKept < maxPlain -> {
                    plainKept++
                    kept += entry
                }
                else -> removed += entry
            }
        }
        return Split(kept, removed)
    }

    /** “清空暂存夹”：只清普通条目，分类内的话术保留。 */
    fun clearPlain(entries: List<StashEntry>): Split {
        val (phrases, plain) = entries.partition { it.categoryId != null }
        return Split(kept = phrases, removed = plain)
    }

    /** “清空某个分类”：删掉该分类下的全部条目。 */
    fun clearCategory(entries: List<StashEntry>, categoryId: String): Split {
        val (inCategory, others) = entries.partition { it.categoryId == categoryId }
        return Split(kept = others, removed = inCategory)
    }

    /**
     * 让命中 [shouldRelease] 的条目变回普通暂存条目，并挪到列表最前。
     *
     * 挪到最前是为了让它们视作“刚放回暂存夹”：紧随其后的 200 条淘汰只会挤掉更老的普通条目，
     * 而不是刚被放回来的这几条。
     */
    fun releaseToPlain(entries: List<StashEntry>, shouldRelease: (StashEntry) -> Boolean): List<StashEntry> {
        val (released, others) = entries.partition(shouldRelease)
        if (released.isEmpty()) return entries
        return released.map { it.copy(categoryId = null, sortOrder = 0) } + others
    }

    /** 新条目进入分类时的排序值：排在该分类现有条目之后。 */
    fun nextSortOrder(entries: List<StashEntry>, categoryId: String): Int =
        (entries.filter { it.categoryId == categoryId }.maxOfOrNull { it.sortOrder } ?: -1) + 1

    /** 按 [orderedIds] 的顺序重写该分类内条目的 [StashEntry.sortOrder]。 */
    fun reorder(entries: List<StashEntry>, categoryId: String, orderedIds: List<String>): List<StashEntry> {
        val position = HashMap<String, Int>(orderedIds.size)
        orderedIds.forEachIndexed { index, id -> position[id] = index }
        return entries.map { entry ->
            val index = position[entry.id]
            if (entry.categoryId == categoryId && index != null) entry.copy(sortOrder = index) else entry
        }
    }
}
