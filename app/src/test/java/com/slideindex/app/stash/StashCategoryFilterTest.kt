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
}
