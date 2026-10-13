package com.slideindex.app.stash

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 需要真的位图编码的用例：图片入库、换图。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class StashRepositoryBitmapTest {
    private lateinit var context: Context
    private lateinit var imageDir: File
    private lateinit var repository: StashRepository

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        val stashDir = File(context.filesDir, "stash")
        stashDir.deleteRecursively()
        imageDir = File(stashDir, "images")
        repository = StashRepository(context, StashMetaRepository(context))
    }

    private fun bitmap() = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)

    @Test
    fun addImageToCategory_savesFileAndAppendsToCategory() = runBlocking {
        val first = repository.addImage(bitmap(), categoryId = "A")
        val second = repository.addImage(bitmap(), categoryId = "A")

        assertNotNull(first)
        assertEquals("A", first?.categoryId)
        assertEquals(0, first?.sortOrder)
        assertEquals(1, second?.sortOrder)
        assertTrue(File(imageDir, first?.imageFileName.orEmpty()).exists())
    }

    @Test
    fun addRichToCategory_keepsTextThenImageOrder() = runBlocking {
        val entry = repository.addRich(
            parts = listOf(StashRichPart.Text("先发文字"), StashRichPart.Image(bitmap())),
            categoryId = "A"
        )

        assertEquals(StashEntryType.RICH, entry?.type)
        assertEquals("A", entry?.categoryId)
        assertEquals(listOf("先发文字", ""), entry?.resolvedContentBlocks()?.map { it.text })
        assertEquals(1, entry?.allImageFileNames()?.size)
    }

    @Test
    fun updateContent_replaceImage_usesANewFileNameAndDeletesTheOldOne() = runBlocking {
        val added = repository.addImage(bitmap(), categoryId = "A")!!
        val oldFile = added.imageFileName!!

        assertTrue(repository.updateContent(added.id, "配文", StashImageEdit.Replace(bitmap())))

        val updated = repository.entries.value.single()
        assertEquals(StashEntryType.RICH, updated.type)
        assertNotEquals("换图必须换文件名，否则缩略图缓存会一直显示旧图", oldFile, updated.imageFileName)
        assertFalse(File(imageDir, oldFile).exists())
        assertTrue(File(imageDir, updated.imageFileName!!).exists())
        assertEquals("A", updated.categoryId)
    }

    @Test
    fun deleteImageEntry_keepsItsFileForUndo() = runBlocking {
        val added = repository.addImage(bitmap())!!
        val file = File(imageDir, added.imageFileName!!)
        assertTrue(file.exists())

        repository.delete(added.id)

        // 上游改成了“可撤销的删除”：文件留着，下次启动统一清孤儿。
        assertTrue(file.exists())
        assertTrue(repository.restore(added, 0))
    }

    @Test
    fun deleteImageEntry_purgeMedia_removesItsFileRightAway() = runBlocking {
        val added = repository.addImage(bitmap())!!
        val file = File(imageDir, added.imageFileName!!)
        assertTrue(file.exists())

        repository.delete(added.id, purgeMedia = true)

        assertFalse(file.exists())
    }
}
