package com.slideindex.app.stash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StashRetentionTest {

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

    private fun ids(entries: List<StashEntry>) = entries.map { it.id }

    @Test
    fun trimPlain_withoutCategories_keepsNewestLikeTake() {
        // 老行为：列表最前是最新的，超出上限就丢掉最老的。
        val entries = (1..7).map { entry("e$it") }

        val split = StashRetention.trimPlain(entries, maxPlain = 5)

        assertEquals(listOf("e1", "e2", "e3", "e4", "e5"), ids(split.kept))
        assertEquals(listOf("e6", "e7"), ids(split.removed))
    }

    @Test
    fun trimPlain_atOrBelowLimit_returnsSameList() {
        val entries = (1..5).map { entry("e$it") }

        val split = StashRetention.trimPlain(entries, maxPlain = 5)

        assertSame(entries, split.kept)
        assertTrue(split.removed.isEmpty())
    }

    @Test
    fun trimPlain_neverDropsCategorizedEntries() {
        val entries = listOf(
            entry("p1"),
            entry("c1", categoryId = "A"),
            entry("p2"),
            entry("c2", categoryId = "A"),
            entry("p3"),
            entry("c3", categoryId = "B"),
            entry("p4"),
        )

        val split = StashRetention.trimPlain(entries, maxPlain = 2)

        // 话术全部保留、位置不变；普通条目只留最前面的 2 条。
        assertEquals(listOf("p1", "c1", "p2", "c2", "c3"), ids(split.kept))
        assertEquals(listOf("p3", "p4"), ids(split.removed))
    }

    @Test
    fun trimPlain_categorizedEntriesDoNotUseUpPlainQuota() {
        val entries = (1..10).map { entry("c$it", categoryId = "A") } +
            (1..3).map { entry("p$it") }

        val split = StashRetention.trimPlain(entries, maxPlain = 3)

        assertEquals(13, split.kept.size)
        assertTrue(split.removed.isEmpty())
    }

    @Test
    fun clearPlain_keepsCategorizedEntries() {
        val entries = listOf(
            entry("p1"),
            entry("c1", categoryId = "A"),
            entry("p2"),
            entry("c2", categoryId = "B"),
        )

        val split = StashRetention.clearPlain(entries)

        assertEquals(listOf("c1", "c2"), ids(split.kept))
        assertEquals(listOf("p1", "p2"), ids(split.removed))
    }

    @Test
    fun clearCategory_removesOnlyThatCategory() {
        val entries = listOf(
            entry("p1"),
            entry("a1", categoryId = "A"),
            entry("b1", categoryId = "B"),
            entry("a2", categoryId = "A"),
        )

        val split = StashRetention.clearCategory(entries, "A")

        assertEquals(listOf("p1", "b1"), ids(split.kept))
        assertEquals(listOf("a1", "a2"), ids(split.removed))
    }

    @Test
    fun releaseToPlain_movesReleasedToFrontAndResetsCategory() {
        val entries = listOf(
            entry("p1"),
            entry("a1", categoryId = "A", sortOrder = 3),
            entry("p2"),
            entry("a2", categoryId = "A", sortOrder = 1),
            entry("b1", categoryId = "B"),
        )

        val next = StashRetention.releaseToPlain(entries) { it.categoryId == "A" }

        assertEquals(listOf("a1", "a2", "p1", "p2", "b1"), ids(next))
        assertTrue(next.take(2).all { it.categoryId == null && it.sortOrder == 0 })
        assertEquals("B", next.last().categoryId)
    }

    @Test
    fun releaseToPlain_nothingMatched_returnsSameList() {
        val entries = listOf(entry("p1"), entry("a1", categoryId = "A"))

        val next = StashRetention.releaseToPlain(entries) { it.categoryId == "missing" }

        assertSame(entries, next)
    }

    @Test
    fun releasedEntriesSurviveTheNextTrim() {
        // 分类删除后变回普通条目：即使它们很老，也不能被紧随其后的“最老优先”淘汰第一个挤掉。
        val plain = (1..4).map { entry("p$it") }
        val old = entry("old-phrase", categoryId = "A", createdAt = 1L)

        val released = StashRetention.releaseToPlain(plain + old) { it.categoryId == "A" }
        val split = StashRetention.trimPlain(released, maxPlain = 4)

        assertEquals(listOf("old-phrase", "p1", "p2", "p3"), ids(split.kept))
        assertEquals(listOf("p4"), ids(split.removed))
    }

    @Test
    fun nextSortOrder_appendsAfterExistingEntriesOfThatCategory() {
        val entries = listOf(
            entry("a1", categoryId = "A", sortOrder = 0),
            entry("a2", categoryId = "A", sortOrder = 4),
            entry("b1", categoryId = "B", sortOrder = 9),
        )

        assertEquals(5, StashRetention.nextSortOrder(entries, "A"))
        assertEquals(10, StashRetention.nextSortOrder(entries, "B"))
        assertEquals(0, StashRetention.nextSortOrder(entries, "empty"))
    }

    @Test
    fun reorder_rewritesSortOrderOnlyInThatCategory() {
        val entries = listOf(
            entry("a1", categoryId = "A", sortOrder = 0),
            entry("a2", categoryId = "A", sortOrder = 1),
            entry("a3", categoryId = "A", sortOrder = 2),
            entry("b1", categoryId = "B", sortOrder = 0),
            entry("p1"),
        )

        val next = StashRetention.reorder(entries, "A", listOf("a3", "a1", "a2"))

        val byId = next.associateBy { it.id }
        assertEquals(0, byId.getValue("a3").sortOrder)
        assertEquals(1, byId.getValue("a1").sortOrder)
        assertEquals(2, byId.getValue("a2").sortOrder)
        assertEquals(0, byId.getValue("b1").sortOrder)
        assertEquals(ids(entries), ids(next))
    }
}
