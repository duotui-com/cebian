package com.slideindex.app.stash

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 已有用户的 `stash/index.json` 必须能原样读取，没有分类的老条目行为不变。 */
class StashEntrySerializationTest {

    // 与 StashRepository 里读写 index.json 用的配置一致。
    private val json = Json { ignoreUnknownKeys = true }

    /** 分类功能上线之前的 index.json：没有 categoryId / sortOrder 字段。 */
    private val legacyIndex = """
        [
          {"id":"t1","type":"TEXT","text":"你好","createdAtEpochMs":1700000000000},
          {"id":"i1","type":"IMAGE","imageFileName":"i1.png","createdAtEpochMs":1700000001000,
           "starred":true,"pinDisplayWidthPx":320,"pinDisplayHeightPx":240},
          {"id":"r1","type":"RICH","text":"图文","imageFileName":"r1.png",
           "contentBlocks":[{"kind":"text","text":"图文"},{"kind":"image","fileName":"r1.png"}],
           "htmlText":"<p>图文</p>","createdAtEpochMs":1700000002000}
        ]
    """.trimIndent()

    @Test
    fun legacyIndex_decodesWithUncategorizedDefaults() {
        val entries = json.decodeFromString<List<StashEntry>>(legacyIndex)

        assertEquals(listOf("t1", "i1", "r1"), entries.map { it.id })
        assertTrue(entries.all { it.categoryId == null && it.sortOrder == 0 })
        assertEquals(StashEntryType.RICH, entries[2].type)
        assertEquals(2, entries[2].contentBlocks.size)
        assertTrue(entries[1].starred)
        assertEquals(320, entries[1].pinDisplayWidthPx)
    }

    @Test
    fun uncategorizedEntry_encodesWithoutNewFields() {
        // 默认值不写盘，普通条目的 JSON 与升级前完全一致（回退到旧版本也能读）。
        val entry = StashEntry(id = "t1", type = StashEntryType.TEXT, text = "你好", createdAtEpochMs = 1L)

        val encoded = json.encodeToString(entry)

        assertFalse(encoded.contains("categoryId"))
        assertFalse(encoded.contains("sortOrder"))
    }

    @Test
    fun categorizedEntry_roundTrips() {
        val entry = StashEntry(
            id = "t2",
            type = StashEntryType.TEXT,
            text = "话术",
            createdAtEpochMs = 2L,
            categoryId = "cat-1",
            sortOrder = 3
        )

        val decoded = json.decodeFromString<StashEntry>(json.encodeToString(entry))

        assertEquals(entry, decoded)
    }

    @Test
    fun unknownFieldsFromNewerVersionsAreIgnored() {
        val entry = json.decodeFromString<StashEntry>(
            """{"id":"x","type":"TEXT","text":"a","createdAtEpochMs":1,"someFutureField":42}"""
        )

        assertEquals("x", entry.id)
        assertNull(entry.categoryId)
    }

    @Test
    fun category_roundTripsAndToleratesMissingSortOrder() {
        val decoded = json.decodeFromString<StashCategory>(
            """{"id":"c1","name":"售前","createdAtEpochMs":5}"""
        )

        assertEquals(StashCategory(id = "c1", name = "售前", sortOrder = 0, createdAtEpochMs = 5), decoded)
        assertEquals(decoded, json.decodeFromString<StashCategory>(json.encodeToString(decoded)))
    }
}
