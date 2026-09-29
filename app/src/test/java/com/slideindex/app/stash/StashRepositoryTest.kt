package com.slideindex.app.stash

import android.content.Context
import com.slideindex.app.clipboard.ClipboardContentBlock
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class StashRepositoryTest {
    private lateinit var context: Context
    private lateinit var stashDir: File
    private lateinit var imageDir: File

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        stashDir = File(context.filesDir, "stash")
        stashDir.deleteRecursively()
        imageDir = File(stashDir, "images").apply { mkdirs() }
    }

    // ───────────────────────── 测试数据 ─────────────────────────

    private fun textEntry(id: String, categoryId: String? = null, sortOrder: Int = 0) = StashEntry(
        id = id,
        type = StashEntryType.TEXT,
        text = id,
        createdAtEpochMs = 1L,
        categoryId = categoryId,
        sortOrder = sortOrder
    )

    private fun imageFile(name: String): String {
        File(imageDir, name).writeBytes(byteArrayOf(1, 2, 3))
        return name
    }

    private fun imageEntry(id: String, categoryId: String? = null) = StashEntry(
        id = id,
        type = StashEntryType.IMAGE,
        imageFileName = imageFile("$id.png"),
        createdAtEpochMs = 1L,
        categoryId = categoryId
    )

    /** 图文混合：一段文字 + 若干张图，图片文件真实落盘。 */
    private fun richEntry(id: String, vararg files: String, categoryId: String? = null): StashEntry {
        val saved = files.map { imageFile(it) }
        return StashEntry(
            id = id,
            type = StashEntryType.RICH,
            text = "caption",
            imageFileName = saved.first(),
            contentBlocks = listOf(ClipboardContentBlock.text("caption")) +
                saved.map { ClipboardContentBlock.image(it) },
            createdAtEpochMs = 1L,
            categoryId = categoryId
        )
    }

    private fun writeIndex(entries: List<StashEntry>) {
        File(stashDir, "index.json").writeText(Json.encodeToString(entries))
    }

    private fun repository() = StashRepository(context)

    /** 用一个全新的仓库实例重新从磁盘读，确认改动确实落盘了。 */
    private fun reloaded() = repository().entries.value

    private fun ids(entries: List<StashEntry>) = entries.map { it.id }

    private fun imageExists(name: String) = File(imageDir, name).exists()

    // ───────────────────────── 兼容老数据 ─────────────────────────

    @Test
    fun legacyIndexJson_loadsUnchanged() {
        File(stashDir, "index.json").writeText(
            """
            [
              {"id":"t1","type":"TEXT","text":"你好","createdAtEpochMs":3},
              {"id":"i1","type":"IMAGE","imageFileName":"i1.png","createdAtEpochMs":2,"starred":true},
              {"id":"r1","type":"RICH","text":"x","imageFileName":"r1.png",
               "contentBlocks":[{"kind":"text","text":"x"},{"kind":"image","fileName":"r1.png"}],
               "createdAtEpochMs":1}
            ]
            """.trimIndent()
        )

        val entries = repository().entries.value

        assertEquals(listOf("t1", "i1", "r1"), ids(entries))
        assertTrue(entries.all { it.categoryId == null })
        assertTrue(entries[1].starred)
    }

    @Test
    fun missingOrCorruptIndex_isEmpty() {
        assertTrue(repository().entries.value.isEmpty())

        File(stashDir, "index.json").writeText("not json")
        assertTrue(repository().entries.value.isEmpty())
    }

    // ───────────────────────── 200 条上限只管普通条目 ─────────────────────────

    @Test
    fun trimOnLoad_onlyCountsPlainEntries_andKeepsCategorizedImages() {
        val plain = (0 until 205).map { index ->
            if (index >= 200) imageEntry("p$index") else textEntry("p$index")
        }
        val phrases = listOf(
            imageEntry("c1", categoryId = "A"),
            textEntry("c2", categoryId = "A"),
            imageEntry("c3", categoryId = "B"),
        )
        // 话术穿插在普通条目中间，也不能被挤掉。
        writeIndex(plain.take(100) + phrases + plain.drop(100))

        val entries = repository().entries.value

        assertEquals(200, entries.count { it.categoryId == null })
        assertEquals(listOf("c1", "c2", "c3"), ids(entries.filter { it.categoryId != null }))
        assertEquals((0 until 200).map { "p$it" }, ids(entries.filter { it.categoryId == null }))
        // 被淘汰的普通条目的图片被删掉，话术的图片还在。
        (200 until 205).forEach { assertFalse(imageExists("p$it.png")) }
        assertTrue(imageExists("c1.png"))
        assertTrue(imageExists("c3.png"))
        // 淘汰结果已经写回磁盘。
        assertEquals(203, reloaded().size)
    }

    @Test
    fun addingAtTheCap_evictsOldestPlainEntryButNeverCategorizedOnes() = runBlocking {
        val plain = (0 until 200).map { textEntry("p$it") }
        writeIndex(plain + textEntry("c1", categoryId = "A") + imageEntry("c2", categoryId = "A"))
        val repo = repository()

        val added = repo.addText("brand new")

        val entries = repo.entries.value
        assertEquals(added?.id, entries.first().id)
        assertEquals(200, entries.count { it.categoryId == null })
        assertFalse("最老的普通条目被淘汰", entries.any { it.id == "p199" })
        assertTrue(entries.any { it.id == "c1" })
        assertTrue(entries.any { it.id == "c2" })
        assertTrue(imageExists("c2.png"))
    }

    @Test
    fun addingToCategory_doesNotUsePlainQuota_andAppendsSortOrder() = runBlocking {
        writeIndex((0 until 200).map { textEntry("p$it") })
        val repo = repository()

        val first = repo.addText("话术一", categoryId = "A")
        val second = repo.addText("话术二", categoryId = "A")
        val other = repo.addText("话术三", categoryId = "B")

        val entries = repo.entries.value
        assertEquals(200, entries.count { it.categoryId == null })
        assertEquals(0, first?.sortOrder)
        assertEquals(1, second?.sortOrder)
        assertEquals(0, other?.sortOrder)
        assertEquals("A", first?.categoryId)
    }

    // ───────────────────────── 清空 ─────────────────────────

    @Test
    fun clearAll_keepsCategorizedEntriesAndTheirImages() = runBlocking {
        writeIndex(
            listOf(
                textEntry("p1"),
                imageEntry("p2"),
                imageEntry("c1", categoryId = "A"),
                richEntry("c2", "c2a.png", "c2b.png", categoryId = "A"),
            )
        )
        val repo = repository()

        repo.clearAll()

        assertEquals(listOf("c1", "c2"), ids(repo.entries.value))
        assertEquals(listOf("c1", "c2"), ids(reloaded()))
        assertFalse(imageExists("p2.png"))
        assertTrue(imageExists("c1.png"))
        assertTrue(imageExists("c2a.png"))
        assertTrue(imageExists("c2b.png"))
    }

    @Test
    fun clearCategory_removesOnlyThatCategoryWithItsImages() = runBlocking {
        writeIndex(
            listOf(
                imageEntry("p1"),
                imageEntry("a1", categoryId = "A"),
                richEntry("a2", "a2a.png", "a2b.png", categoryId = "A"),
                imageEntry("b1", categoryId = "B"),
            )
        )
        val repo = repository()

        repo.clearCategory("A")

        assertEquals(listOf("p1", "b1"), ids(repo.entries.value))
        assertFalse(imageExists("a1.png"))
        assertFalse(imageExists("a2a.png"))
        assertFalse(imageExists("a2b.png"))
        assertTrue(imageExists("p1.png"))
        assertTrue(imageExists("b1.png"))
    }

    @Test
    fun delete_removesEveryImageOfARichEntry() = runBlocking {
        writeIndex(listOf(richEntry("r1", "r1a.png", "r1b.png", "r1c.png"), imageEntry("i1")))
        val repo = repository()

        repo.delete("r1")

        assertEquals(listOf("i1"), ids(repo.entries.value))
        listOf("r1a.png", "r1b.png", "r1c.png").forEach { assertFalse(imageExists(it)) }
        assertTrue(imageExists("i1.png"))
    }

    // ───────────────────────── 移动 / 释放 ─────────────────────────

    @Test
    fun moveToCategory_appendsToTheEndOfTheCategory() = runBlocking {
        writeIndex(listOf(textEntry("p1"), textEntry("p2"), textEntry("a1", categoryId = "A", sortOrder = 4)))
        val repo = repository()

        assertTrue(repo.moveToCategory("p1", "A"))
        assertTrue(repo.moveToCategory("p2", "A"))

        val byId = reloaded().associateBy { it.id }
        assertEquals("A", byId.getValue("p1").categoryId)
        assertEquals(5, byId.getValue("p1").sortOrder)
        assertEquals(6, byId.getValue("p2").sortOrder)
    }

    @Test
    fun moveToCategory_unknownEntry_returnsFalse() = runBlocking {
        val repo = repository()

        assertFalse(repo.moveToCategory("missing", "A"))
    }

    @Test
    fun moveToCategory_null_makesItPlainAndPutsItOnTop() = runBlocking {
        writeIndex(listOf(textEntry("p1"), textEntry("p2"), textEntry("a1", categoryId = "A", sortOrder = 3)))
        val repo = repository()

        assertTrue(repo.moveToCategory("a1", null))

        val entries = reloaded()
        assertEquals(listOf("a1", "p1", "p2"), ids(entries))
        assertNull(entries.first().categoryId)
        assertEquals(0, entries.first().sortOrder)
    }

    @Test
    fun movingOutOfCategoryAtTheCap_doesNotEvictTheEntryJustMoved() = runBlocking {
        // 200 条普通条目已满；一条很老的话术被移出分类后，被挤掉的应当是最老的普通条目，而不是它自己。
        val plain = (0 until 200).map { textEntry("p$it") }
        writeIndex(plain + imageEntry("old-phrase", categoryId = "A"))
        val repo = repository()

        repo.moveToCategory("old-phrase", null)

        val entries = repo.entries.value
        assertEquals("old-phrase", entries.first().id)
        assertEquals(200, entries.size)
        assertFalse(entries.any { it.id == "p199" })
        assertTrue(imageExists("old-phrase.png"))
    }

    @Test
    fun releaseCategory_turnsItsEntriesBackIntoPlainOnes() = runBlocking {
        writeIndex(
            listOf(
                textEntry("p1"),
                imageEntry("a1", categoryId = "A"),
                textEntry("b1", categoryId = "B"),
                textEntry("a2", categoryId = "A", sortOrder = 1),
            )
        )
        val repo = repository()

        repo.releaseCategory("A")

        val byId = reloaded().associateBy { it.id }
        assertNull(byId.getValue("a1").categoryId)
        assertNull(byId.getValue("a2").categoryId)
        assertEquals("B", byId.getValue("b1").categoryId)
        assertEquals(4, byId.size)
        assertTrue("内容不删除，图片还在", imageExists("a1.png"))
    }

    @Test
    fun reorderInCategory_persistsTheNewOrder() = runBlocking {
        writeIndex(
            listOf(
                textEntry("a1", categoryId = "A", sortOrder = 0),
                textEntry("a2", categoryId = "A", sortOrder = 1),
                textEntry("a3", categoryId = "A", sortOrder = 2),
            )
        )
        val repo = repository()

        repo.reorderInCategory("A", listOf("a3", "a1", "a2"))

        val inCategory = StashCategoryFilter.Category("A").apply(reloaded())
        assertEquals(listOf("a3", "a1", "a2"), ids(inCategory))
    }

    // ───────────────────────── 编辑 ─────────────────────────

    @Test
    fun updateText_onlyForTextEntries() = runBlocking {
        writeIndex(listOf(textEntry("t1"), imageEntry("i1"), richEntry("r1", "r1.png")))
        val repo = repository()

        assertTrue(repo.updateText("t1", "  新内容  "))
        assertFalse("图片条目没有文字可改", repo.updateText("i1", "x"))
        assertFalse("图文条目走 updateContent", repo.updateText("r1", "x"))
        assertFalse("空文字不受理", repo.updateText("t1", "   "))
        assertFalse(repo.updateText("missing", "x"))

        val byId = reloaded().associateBy { it.id }
        assertEquals("新内容", byId.getValue("t1").text)
        assertNull(byId.getValue("i1").text)
    }

    @Test
    fun updateText_keepsCategoryAndOrder() = runBlocking {
        writeIndex(listOf(textEntry("t1", categoryId = "A", sortOrder = 7)))
        val repo = repository()

        repo.updateText("t1", "改过了")

        val entry = reloaded().single()
        assertEquals("A", entry.categoryId)
        assertEquals(7, entry.sortOrder)
        assertEquals("改过了", entry.text)
    }

    @Test
    fun updateContent_addTextToImage_becomesRichAndKeepsTheImage() = runBlocking {
        writeIndex(listOf(imageEntry("i1", categoryId = "A")))
        val repo = repository()

        assertTrue(repo.updateContent("i1", "配文", StashImageEdit.Keep))

        val entry = reloaded().single()
        assertEquals(StashEntryType.RICH, entry.type)
        assertEquals("配文", entry.text)
        assertEquals("A", entry.categoryId)
        assertEquals(listOf("配文", ""), entry.resolvedContentBlocks().map { it.text })
        assertEquals(listOf("i1.png"), entry.allImageFileNames())
        assertTrue(imageExists("i1.png"))
    }

    @Test
    fun updateContent_removeImage_deletesTheFileAndBecomesText() = runBlocking {
        writeIndex(listOf(richEntry("r1", "r1.png")))
        val repo = repository()

        assertTrue(repo.updateContent("r1", "只留文字", StashImageEdit.Remove))

        val entry = reloaded().single()
        assertEquals(StashEntryType.TEXT, entry.type)
        assertEquals("只留文字", entry.text)
        assertNull(entry.imageFileName)
        assertTrue(entry.contentBlocks.isEmpty())
        assertFalse(imageExists("r1.png"))
    }

    @Test
    fun updateContent_dropTextKeepImage_becomesImageEntry() = runBlocking {
        writeIndex(listOf(richEntry("r1", "r1.png")))
        val repo = repository()

        assertTrue(repo.updateContent("r1", "  ", StashImageEdit.Keep))

        val entry = reloaded().single()
        assertEquals(StashEntryType.IMAGE, entry.type)
        assertNull(entry.text)
        assertEquals("r1.png", entry.imageFileName)
    }

    @Test
    fun updateContent_removingEverything_isRejectedAndLeavesTheEntryAlone() = runBlocking {
        writeIndex(listOf(richEntry("r1", "r1.png")))
        val repo = repository()

        assertFalse(repo.updateContent("r1", null, StashImageEdit.Remove))

        assertEquals(StashEntryType.RICH, reloaded().single().type)
        assertTrue(imageExists("r1.png"))
    }

    @Test
    fun updateContent_complexRichEntry_isNotEditable() = runBlocking {
        writeIndex(listOf(richEntry("r1", "r1a.png", "r1b.png")))
        val repo = repository()

        assertFalse(repo.updateContent("r1", "x", StashImageEdit.Keep))
        assertTrue(imageExists("r1a.png"))
        assertTrue(imageExists("r1b.png"))
    }

}
