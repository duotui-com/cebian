package com.slideindex.app.stash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class StashCategoryFilterTest {

    private fun entry(
        id: String,
        categoryId: String? = null,
        sortOrder: Int = 0,
        createdAt: Long = 0L
    ) = StashEntry(
        id = id,
        type = StashEntryType.TEXT,
        text = id,
        createdAtEpochMs = createdAt,
        categoryId = categoryId,
        sortOrder = sortOrder
    )

    private val entries = listOf(
        entry("p1"),
        entry("a-late", categoryId = "A", sortOrder = 2, createdAt = 30),
        entry("b1", categoryId = "B"),
        entry("a-first", categoryId = "A", sortOrder = 0, createdAt = 10),
        entry("p2"),
        entry("a-tie-new", categoryId = "A", sortOrder = 1, createdAt = 50),
        entry("a-tie-old", categoryId = "A", sortOrder = 1, createdAt = 20),
    )

    @Test
    fun all_returnsEntriesUntouched() {
        assertSame(entries, StashCategoryFilter.All.apply(entries))
    }

    @Test
    fun uncategorized_onlyPlainEntriesInRepositoryOrder() {
        val ids = StashCategoryFilter.Uncategorized.apply(entries).map { it.id }

        assertEquals(listOf("p1", "p2"), ids)
    }

    @Test
    fun category_onlyThatCategoryOrderedBySortOrderThenNewestFirst() {
        val ids = StashCategoryFilter.Category("A").apply(entries).map { it.id }

        assertEquals(listOf("a-first", "a-tie-new", "a-tie-old", "a-late"), ids)
    }

    @Test
    fun category_unknownId_isEmpty() {
        assertEquals(emptyList<StashEntry>(), StashCategoryFilter.Category("missing").apply(entries))
    }

    @Test
    fun encodeDecode_roundTrips() {
        listOf(
            StashCategoryFilter.All,
            StashCategoryFilter.Uncategorized,
            StashCategoryFilter.Category("3f2a-uuid"),
        ).forEach { filter ->
            assertEquals(filter, StashCategoryFilter.decode(filter.encode()))
        }
    }

    @Test
    fun decode_missingOrUnknownFallsBackToAll() {
        assertEquals(StashCategoryFilter.All, StashCategoryFilter.decode(null))
        assertEquals(StashCategoryFilter.All, StashCategoryFilter.decode(""))
        assertEquals(StashCategoryFilter.All, StashCategoryFilter.decode("garbage"))
        assertEquals(StashCategoryFilter.All, StashCategoryFilter.decode("category:"))
    }

    // ───────────────────────── 置顶 ─────────────────────────

    private fun pinned(e: StashEntry) = e.copy(starred = true)

    @Test
    fun pinnedEntries_comeFirstWithinEachCategoryAndKeepTheirOwnOrder() {
        val list = listOf(
            entry("a1", categoryId = "A", sortOrder = 0),
            pinned(entry("a2", categoryId = "A", sortOrder = 1)),
            entry("a3", categoryId = "A", sortOrder = 2),
            pinned(entry("a4", categoryId = "A", sortOrder = 3)),
            pinned(entry("b1", categoryId = "B")),
        )

        assertEquals(listOf("a2", "a4", "a1", "a3"), StashCategoryFilter.Category("A").apply(list).map { it.id })
        assertEquals(listOf("b1"), StashCategoryFilter.Category("B").apply(list).map { it.id })
    }

    @Test
    fun pinnedEntries_comeFirstInAllAndUncategorized() {
        val list = listOf(entry("p1"), pinned(entry("p2")), entry("p3"), pinned(entry("c1", categoryId = "A")))

        assertEquals(listOf("p2", "c1", "p1", "p3"), StashCategoryFilter.All.apply(list).map { it.id })
        assertEquals(listOf("p2", "p1", "p3"), StashCategoryFilter.Uncategorized.apply(list).map { it.id })
    }

    // ───────────────────────── 滑动切换分类的顺序 ─────────────────────────

    private fun category(id: String) = StashCategory(id = id, name = id, createdAtEpochMs = 0L)

    @Test
    fun filterOrder_isAllThenCategoriesThenUncategorized() {
        val order = stashFilterOrder(listOf(category("A"), category("B")))

        assertEquals(
            listOf(
                StashCategoryFilter.All,
                StashCategoryFilter.Category("A"),
                StashCategoryFilter.Category("B"),
                StashCategoryFilter.Uncategorized,
            ),
            order
        )
        assertEquals(listOf(StashCategoryFilter.All, StashCategoryFilter.Uncategorized), stashFilterOrder(emptyList()))
    }

    @Test
    fun adjacentTo_movesOneStepAndStopsAtBothEnds() {
        val order = stashFilterOrder(listOf(category("A")))

        assertEquals(StashCategoryFilter.Category("A"), order.adjacentTo(StashCategoryFilter.All, 1))
        assertEquals(StashCategoryFilter.Uncategorized, order.adjacentTo(StashCategoryFilter.Category("A"), 1))
        assertEquals(null, order.adjacentTo(StashCategoryFilter.Uncategorized, 1))
        assertEquals(null, order.adjacentTo(StashCategoryFilter.All, -1))
        assertEquals(StashCategoryFilter.All, order.adjacentTo(StashCategoryFilter.Category("A"), -1))
    }

    @Test
    fun adjacentTo_unknownCurrent_isNull() {
        assertEquals(null, stashFilterOrder(emptyList()).adjacentTo(StashCategoryFilter.Category("gone"), 1))
    }
}
