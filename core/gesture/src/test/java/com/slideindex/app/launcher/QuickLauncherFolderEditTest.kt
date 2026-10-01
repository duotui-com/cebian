package com.slideindex.app.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickLauncherFolderEditTest {

    private val appA = QuickLauncherItem.app("com.app.a", "App A")
    private val appB = QuickLauncherItem.app("com.app.b", "App B")
    private val appC = QuickLauncherItem.app("com.app.c", "App C")

    @Test
    fun testWithFolderChildrenReplacesChildrenInPlace() {
        val folder = QuickLauncherItem.folder(label = "Tools", items = listOf(appA))
        val items = listOf(appC, folder, appB)

        val updated = items.withFolderChildren(1, listOf(appA, appB))

        assertEquals(3, updated.size)
        assertEquals(appC, updated[0])
        assertEquals(appB, updated[2])
        assertTrue(updated[1].isFolder)
        assertEquals("Tools", updated[1].label)
        assertEquals(listOf(appA, appB), updated[1].folderItems())
    }

    @Test
    fun testWithFolderChildrenKeepsEmptyFolder() {
        val folder = QuickLauncherItem.folder(label = "Tools", items = listOf(appA))

        val updated = listOf(folder).withFolderChildren(0, emptyList())

        assertEquals(1, updated.size)
        assertTrue(updated[0].isFolder)
        assertEquals(emptyList<QuickLauncherItem>(), updated[0].folderItems())
    }

    @Test
    fun testWithFolderChildrenIgnoresNonFolderAndOutOfBounds() {
        val items = listOf(appA, appB)

        assertEquals(items, items.withFolderChildren(0, listOf(appC)))
        assertEquals(items, items.withFolderChildren(5, listOf(appC)))
        assertEquals(items, items.withFolderChildren(-1, listOf(appC)))
    }

    @Test
    fun testRenameFolderUpdatesLabelOnly() {
        val folder = QuickLauncherItem.folder(label = "", items = listOf(appA))
        val items = listOf(folder, appB)

        val renamed = items.renameFolder(0, "工具")

        assertEquals("工具", renamed[0].label)
        assertTrue(renamed[0].isFolder)
        assertEquals(listOf(appA), renamed[0].folderItems())
        assertEquals(appB, renamed[1])
    }

    @Test
    fun testRenameFolderIgnoresNonFolder() {
        val items = listOf(appA)
        assertEquals(items, items.renameFolder(0, "工具"))
        assertEquals(items, items.renameFolder(3, "工具"))
    }

    @Test
    fun testDissolveFolderFlattensChildrenInOrder() {
        val folder = QuickLauncherItem.folder(label = "Tools", items = listOf(appA, appB))
        val items = listOf(appC, folder, appB.copy(payload = "com.app.d"))

        val dissolved = items.dissolveFolder(1)

        assertEquals(4, dissolved.size)
        assertEquals(appC, dissolved[0])
        assertEquals(appA, dissolved[1])
        assertEquals(appB, dissolved[2])
        assertEquals("com.app.d", dissolved[3].payload)
    }

    @Test
    fun testDissolveEmptyFolderRemovesIt() {
        val folder = QuickLauncherItem.folder(label = "Tools", items = emptyList())
        val items = listOf(appA, folder)

        assertEquals(listOf(appA), items.dissolveFolder(1))
    }

    @Test
    fun testDissolveFolderIgnoresNonFolder() {
        val items = listOf(appA)
        assertEquals(items, items.dissolveFolder(0))
        assertEquals(items, items.dissolveFolder(9))
    }

    @Test
    fun testResolveFolderItemsFallsBackToSelf() {
        val folder = QuickLauncherItem.folder(label = "Tools", items = listOf(appA))
        val items = listOf(folder, appB)

        assertEquals(listOf(appA), items.resolveFolderItems(0))
        assertEquals(items, items.resolveFolderItems(1))
        assertEquals(items, items.resolveFolderItems(-1))
        assertEquals(items, items.resolveFolderItems(7))
    }

    @Test
    fun testWithItemsAtFolderWritesFolderOrRoot() {
        val folder = QuickLauncherItem.folder(label = "Tools", items = listOf(appA))
        val items = listOf(folder, appB)

        val intoFolder = items.withItemsAtFolder(0, listOf(appC))
        assertEquals(listOf(appC), intoFolder[0].folderItems())
        assertEquals(appB, intoFolder[1])

        assertEquals(listOf(appA), items.withItemsAtFolder(-1, listOf(appA)))

        // 索引失效（越界或指向非文件夹）时保持原样，避免把子项误写到根列表上。
        assertEquals(items, items.withItemsAtFolder(5, listOf(appC)))
        assertEquals(items, items.withItemsAtFolder(1, listOf(appC)))
    }
}
