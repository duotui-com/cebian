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
     */
    fun apply(entries: List<StashEntry>): List<StashEntry> = when (this) {
        All -> entries
        Uncategorized -> entries.filter { it.categoryId == null }
        is Category -> entries.filter { it.categoryId == id }.sortedInCategory()
    }.pinnedFirst()

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
