package com.slideindex.app.stash

import android.content.Context
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class StashCategoryRepositoryTest {
    private lateinit var context: Context
    private lateinit var stashDir: File
    private lateinit var stash: StashRepository
    private lateinit var categories: StashCategoryRepository

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        stashDir = File(context.filesDir, "stash")
        stashDir.deleteRecursively()
        stash = StashRepository(context)
        categories = StashCategoryRepository(context, stash)
    }

    private fun names(list: List<StashCategory>) = list.map { it.name }

    /** 用全新实例从磁盘重新读，确认分类确实落盘了。 */
    private fun reloaded() = StashCategoryRepository(context, StashRepository(context)).categories.value

    @Test
    fun noFile_meansNoCategories() {
        assertTrue(categories.categories.value.isEmpty())
    }

    @Test
    fun add_appendsInOrder_andPersistsInsideTheStashDirectory() = runBlocking {
        assertNotNull(categories.add("售前"))
        assertNotNull(categories.add("  售后  "))

        assertEquals(listOf("售前", "售后"), names(categories.categories.value))
        assertEquals(listOf("售前", "售后"), names(reloaded()))
        // 与 index.json 同目录，随 stash 目录一起进备份。
        val file = File(stashDir, "categories.json")
        assertTrue(file.exists())
        assertTrue(file.readText().contains("售前"))
    }

    @Test
    fun add_rejectsBlankAndDuplicateNames() = runBlocking {
        assertNotNull(categories.add("话术"))
        assertNotNull(categories.add("ABC"))

        assertNull(categories.add("   "))
        assertNull(categories.add("话术"))
        assertNull("英文名忽略大小写", categories.add("abc"))
        assertEquals(2, categories.categories.value.size)
    }

    @Test
    fun add_truncatesOverlongNames() = runBlocking {
        val created = categories.add("字".repeat(StashCategoryRepository.MAX_NAME_LENGTH + 10))

        assertEquals(StashCategoryRepository.MAX_NAME_LENGTH, created?.name?.length)
    }

    @Test
    fun rename_changesNameKeepsIdAndOrder() = runBlocking {
        val first = categories.add("A")!!
        categories.add("B")

        assertTrue(categories.rename(first.id, "新名字"))

        assertEquals(listOf("新名字", "B"), names(reloaded()))
        assertEquals(first.id, reloaded().first().id)
    }

    @Test
    fun rename_rejectsBlankDuplicateAndUnknown() = runBlocking {
        val a = categories.add("A")!!
        categories.add("B")

        assertFalse(categories.rename(a.id, " "))
        assertFalse(categories.rename(a.id, "b"))
        assertFalse(categories.rename("missing", "C"))
        assertTrue("改成自己原来的名字（仅大小写不同）是允许的", categories.rename(a.id, "a"))
        assertEquals(listOf("a", "B"), names(categories.categories.value))
    }

    @Test
    fun reorder_rewritesOrderAndKeepsUnlistedAtTheEnd() = runBlocking {
        val a = categories.add("A")!!
        val b = categories.add("B")!!
        val c = categories.add("C")!!

        categories.reorder(listOf(c.id, a.id))

        assertEquals(listOf("C", "A", "B"), names(categories.categories.value))
        assertEquals(listOf("C", "A", "B"), names(reloaded()))
        assertEquals(listOf(0, 1, 2), reloaded().map { it.sortOrder })
        assertEquals(b.id, reloaded().last().id)
    }

    @Test
    fun delete_keepsEntriesAndTurnsThemBackIntoPlainOnes() = runBlocking {
        val sales = categories.add("售前")!!
        val other = categories.add("售后")!!
        stash.addText("一段话术", categoryId = sales.id)
        stash.addText("另一段话术", categoryId = sales.id)
        stash.addText("别的分类", categoryId = other.id)
        stash.addText("普通暂存")

        categories.delete(sales.id)

        assertEquals(listOf("售后"), names(categories.categories.value))
        assertEquals(listOf("售后"), names(reloaded()))
        val entries = stash.entries.value
        assertEquals(4, entries.size)
        assertEquals(listOf(other.id), entries.mapNotNull { it.categoryId }.distinct())
        assertEquals(3, entries.count { it.categoryId == null })
        // 变回普通条目后排在最前面，不会被紧随其后的“最老优先”淘汰第一个挤掉。
        assertEquals(setOf("一段话术", "另一段话术"), entries.take(2).map { it.text }.toSet())
    }

    @Test
    fun delete_unknownCategory_isNoop() = runBlocking {
        categories.add("A")
        stash.addText("x")

        categories.delete("missing")

        assertEquals(1, categories.categories.value.size)
        assertEquals(1, stash.entries.value.size)
    }

    @Test
    fun sendPreferences_defaultOn_andPersist() {
        val prefs = StashSendPreferences(context)
        assertTrue(prefs.collapsePanelAfterSend.value)

        prefs.setCollapsePanelAfterSend(false)

        assertFalse(prefs.collapsePanelAfterSend.value)
        assertFalse(StashSendPreferences(context).collapsePanelAfterSend.value)
        assertTrue(File(stashDir, "send_prefs.json").exists())

        prefs.setCollapsePanelAfterSend(true)
        assertTrue(StashSendPreferences(context).collapsePanelAfterSend.value)
    }
}
