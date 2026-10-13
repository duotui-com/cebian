package com.slideindex.app.stash

/** 收纳面板暂存夹页的分类筛选。 */
sealed interface StashCategoryFilter {

    /** 不过滤。 */
    data object All : StashCategoryFilter

    /** 只看没有分类的普通暂存条目。 */
    data object Uncategorized : StashCategoryFilter

    data class Category(val id: String) : StashCategoryFilter

    /**
     * 过滤条目；单个分类内按手动顺序排，其余保持仓库里的顺序（新的在前）。
     * 置顶（[StashEntry.starred]）的条目排在最前面，各自保持原有的相对顺序，所以在每个分类里都是各自置顶。
     *
     * 例外是 [Uncategorized]：它是闪念时间线（按日期分组、新的在前），顺序必须就是时间顺序，
     * 这里的星标只是个标记，不能把老条目顶到最前面把日期分组搅乱，所以不置顶；
     * 还要按创建时间重排一遍：从分类里释放回来的老条目会被放到列表最前面（防止立刻被 200 条上限挤掉），
     * 不重排的话时间线最上面会冒出一个「更早」的日期头。稳定排序，时间相同的保持原顺序。
     */
    fun apply(entries: List<StashEntry>): List<StashEntry> = when (this) {
        All -> entries.pinnedFirst()
        Uncategorized -> entries.filter { it.categoryId == null }.sortedByDescending { it.createdAtEpochMs }
        is Category -> entries.filter { it.categoryId == id }.sortedInCategory().pinnedFirst()
    }

    /** 存进 SavedStateHandle 的字符串形式。 */
    fun encode(): String = when (this) {
        All -> ENCODED_ALL
        Uncategorized -> ENCODED_UNCATEGORIZED
        is Category -> CATEGORY_PREFIX + id
    }

    companion object {
        private const val ENCODED_ALL = "all"
        private const val ENCODED_UNCATEGORIZED = "none"
        private const val CATEGORY_PREFIX = "category:"

        fun decode(raw: String?): StashCategoryFilter = when {
            raw == ENCODED_UNCATEGORIZED -> Uncategorized
            raw != null && raw.startsWith(CATEGORY_PREFIX) && raw.length > CATEGORY_PREFIX.length ->
                Category(raw.substring(CATEGORY_PREFIX.length))
            else -> All
        }
    }
}

/** 分类内的展示顺序：先按手动排序，相同时新的在前。 */
fun List<StashEntry>.sortedInCategory(): List<StashEntry> =
    sortedWith(compareBy<StashEntry> { it.sortOrder }.thenByDescending { it.createdAtEpochMs })

/** 置顶的条目排到前面；稳定排序，置顶和未置顶两组内部都保持原顺序。 */
fun List<StashEntry>.pinnedFirst(): List<StashEntry> =
    if (none { it.starred }) this else sortedByDescending { it.starred }

/** 底部筛选条里各项的顺序：全部、各分类、未分类。左右滑动切换分类就按这个顺序走。 */
fun stashFilterOrder(categories: List<StashCategory>): List<StashCategoryFilter> =
    listOf<StashCategoryFilter>(StashCategoryFilter.All) +
        categories.map { StashCategoryFilter.Category(it.id) } +
        StashCategoryFilter.Uncategorized

/** [current] 往后（[step] > 0）或往前（[step] < 0）走 |step| 项；走出两头返回 null，不循环。 */
fun List<StashCategoryFilter>.adjacentTo(current: StashCategoryFilter, step: Int): StashCategoryFilter? {
    val index = indexOf(current).takeIf { it >= 0 } ?: return null
    return getOrNull(index + step)
}
