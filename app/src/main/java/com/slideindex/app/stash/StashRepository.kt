package com.slideindex.app.stash

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.LruCache
import androidx.core.content.FileProvider
import androidx.core.graphics.scale
import com.slideindex.app.clipboard.ClipboardBlockKind
import com.slideindex.app.clipboard.ClipboardContentBlock
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

@Singleton
class StashRepository @Inject constructor(
    @ApplicationContext context: Context
) {
    private val appContext = context.applicationContext
    private val stashDir = File(appContext.filesDir, STASH_DIR_NAME).apply { mkdirs() }
    private val imageDir = File(stashDir, IMAGE_DIR_NAME).apply { mkdirs() }
    private val indexFile = File(stashDir, INDEX_FILE_NAME)
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val thumbnailCache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val _entries = MutableStateFlow<List<StashEntry>>(emptyList())
    val entries: StateFlow<List<StashEntry>> = _entries.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 写操作统一入口：进程内 mutex + **跨进程文件锁**；写完广播通知其它进程重载。
     * 各写方法内部本来就是"锁内重新读盘再计算"，套上这层即可跨进程安全。
     */
    private suspend fun <T> withCrossProcessWrite(block: suspend () -> T): T =
        mutex.withLock {
            com.slideindex.app.util.CrossProcessStore.withFileLock(indexFile) {
                val result = block()
                com.slideindex.app.util.CrossProcessStore.notifyChanged(appContext, indexFile)
                result
            }
        }

    private suspend fun reloadFromDiskForExternalChange() {
        mutex.withLock { _entries.value = trimToMax(readFromDiskSync()) }
    }

    init {
        com.slideindex.app.util.CrossProcessStore.registerListener(appContext) { changed ->
            if (changed.absolutePath == indexFile.absolutePath) {
                scope.launch { reloadFromDiskForExternalChange() }
            }
        }
        val loaded = readFromDiskSync()
        val trimmed = trimToMax(loaded)
        if (trimmed.size != loaded.size) {
            writeToDisk(trimmed)
        }
        _entries.value = trimmed
        StashAccess.repository = this
    }

    suspend fun addText(text: String, categoryId: String? = null): StashEntry? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return withContext(Dispatchers.IO) {
            withCrossProcessWrite {
                val current = readFromDisk()
                val entry = StashEntry(
                    id = UUID.randomUUID().toString(),
                    type = StashEntryType.TEXT,
                    text = trimmed,
                    createdAtEpochMs = System.currentTimeMillis(),
                    categoryId = categoryId,
                    sortOrder = sortOrderFor(categoryId, current)
                )
                val next = trimToMax(listOf(entry) + current)
                writeToDisk(next)
                _entries.value = next
                entry
            }
        }
    }

    suspend fun addImage(
        bitmap: Bitmap,
        pinDisplayWidthPx: Int? = null,
        pinDisplayHeightPx: Int? = null,
        categoryId: String? = null
    ): StashEntry? {
        return withContext(Dispatchers.IO) {
            withCrossProcessWrite {
                val id = UUID.randomUUID().toString()
                val fileName = "$id.png"
                val saved = saveImage(fileName, bitmap) ?: return@withCrossProcessWrite null
                val current = readFromDisk()
                val entry = StashEntry(
                    id = id,
                    type = StashEntryType.IMAGE,
                    imageFileName = saved,
                    createdAtEpochMs = System.currentTimeMillis(),
                    pinDisplayWidthPx = pinDisplayWidthPx?.takeIf { it > 0 },
                    pinDisplayHeightPx = pinDisplayHeightPx?.takeIf { it > 0 },
                    categoryId = categoryId,
                    sortOrder = sortOrderFor(categoryId, current)
                )
                val next = trimToMax(listOf(entry) + current)
                writeToDisk(next)
                _entries.value = next
                entry
            }
        }
    }

    suspend fun addRich(
        parts: List<StashRichPart>,
        htmlText: String? = null,
        categoryId: String? = null
    ): StashEntry? {
        if (parts.isEmpty()) return null
        return withContext(Dispatchers.IO) {
            withCrossProcessWrite {
                val id = UUID.randomUUID().toString()
                val imageTotal = parts.count { it is StashRichPart.Image }
                var imageIndex = 0
                val contentBlocks = mutableListOf<ClipboardContentBlock>()
                val textParts = mutableListOf<String>()
                val savedFiles = mutableListOf<String>()

                for (part in parts) {
                    when (part) {
                        is StashRichPart.Text -> {
                            val trimmed = part.text.trim()
                            if (trimmed.isEmpty()) continue
                            contentBlocks += ClipboardContentBlock.text(trimmed)
                            textParts += trimmed
                        }
                        is StashRichPart.Image -> {
                            val fileName = if (imageTotal <= 1) {
                                "$id.png"
                            } else {
                                "${id}_${imageIndex++}.png"
                            }
                            val saved = saveImage(fileName, part.bitmap)
                            if (saved == null) continue
                            savedFiles += saved
                            contentBlocks += ClipboardContentBlock.image(saved)
                        }
                    }
                }

                if (contentBlocks.isEmpty()) {
                    savedFiles.forEach { File(imageDir, it).delete() }
                    return@withCrossProcessWrite null
                }

                val current = readFromDisk()
                val entry = StashEntry(
                    id = id,
                    type = StashEntryType.RICH,
                    text = textParts.joinToString("\n\n").ifBlank { null },
                    imageFileName = contentBlocks
                        .firstOrNull { it.kind == ClipboardBlockKind.IMAGE }
                        ?.fileName,
                    contentBlocks = contentBlocks,
                    htmlText = htmlText?.trim()?.takeIf { it.isNotEmpty() },
                    createdAtEpochMs = System.currentTimeMillis(),
                    categoryId = categoryId,
                    sortOrder = sortOrderFor(categoryId, current)
                )
                val next = trimToMax(listOf(entry) + current)
                writeToDisk(next)
                _entries.value = next
                entry
            }
        }
    }

    suspend fun delete(id: String) {
        withContext(Dispatchers.IO) {
            withCrossProcessWrite {
                val current = readFromDisk()
                val removed = current.firstOrNull { it.id == id } ?: return@withCrossProcessWrite
                deleteEntryImages(removed)
                val next = current.filterNot { it.id == id }
                writeToDisk(next)
                _entries.value = next
            }
        }
    }

    /** 清空普通暂存条目；带分类的话术保留（要清请用 [clearCategory]）。 */
    suspend fun clearAll() {
        withContext(Dispatchers.IO) {
            withCrossProcessWrite {
                val split = StashRetention.clearPlain(readFromDisk())
                split.removed.forEach { deleteEntryImages(it) }
                writeToDisk(split.kept)
                _entries.value = split.kept
            }
        }
    }

    /** 清空某个分类下的全部条目（含图片文件）。 */
    suspend fun clearCategory(categoryId: String) {
        withContext(Dispatchers.IO) {
            withCrossProcessWrite {
                val split = StashRetention.clearCategory(readFromDisk(), categoryId)
                split.removed.forEach { deleteEntryImages(it) }
                writeToDisk(split.kept)
                _entries.value = split.kept
            }
        }
    }

    /** 分类被删除：其下条目不删，变回普通暂存条目。 */
    suspend fun releaseCategory(categoryId: String) {
        withContext(Dispatchers.IO) {
            withCrossProcessWrite {
                val next = trimToMax(
                    StashRetention.releaseToPlain(readFromDisk()) { it.categoryId == categoryId }
                )
                writeToDisk(next)
                _entries.value = next
            }
        }
    }

    /** 把条目移入分类；[categoryId] 为 null 表示移出分类（变回普通暂存条目）。条目不存在返回 false。 */
    suspend fun moveToCategory(id: String, categoryId: String?): Boolean {
        return withContext(Dispatchers.IO) {
            withCrossProcessWrite {
                val current = readFromDisk()
                val target = current.firstOrNull { it.id == id } ?: return@withCrossProcessWrite false
                if (target.categoryId == categoryId) return@withCrossProcessWrite true
                val next = if (categoryId == null) {
                    trimToMax(StashRetention.releaseToPlain(current) { it.id == id })
                } else {
                    val order = StashRetention.nextSortOrder(current, categoryId)
                    current.map { entry ->
                        if (entry.id == id) entry.copy(categoryId = categoryId, sortOrder = order) else entry
                    }
                }
                writeToDisk(next)
                _entries.value = next
                true
            }
        }
    }

    /** 按 [orderedIds] 重排某个分类内的条目。 */
    suspend fun reorderInCategory(categoryId: String, orderedIds: List<String>) {
        withContext(Dispatchers.IO) {
            withCrossProcessWrite {
                val next = StashRetention.reorder(readFromDisk(), categoryId, orderedIds)
                writeToDisk(next)
                _entries.value = next
            }
        }
    }

    /** 只改文字条目（[StashEntryType.TEXT]）的内容；其它类型或空文字返回 false。 */
    suspend fun updateText(id: String, newText: String): Boolean {
        val body = newText.trim()
        if (body.isEmpty()) return false
        return mutateEntry(id) { old ->
            if (old.type == StashEntryType.TEXT) old.copy(text = body) else null
        }
    }

    /**
     * 改写一条“至多一段文字 + 至多一张图”的条目，并按结果重新判定 TEXT / IMAGE / RICH。
     * 含多段文字或多张图的复杂条目、以及文字和图片都被去掉的改写都不受理，返回 false，原条目不动。
     */
    suspend fun updateContent(id: String, text: String?, image: StashImageEdit): Boolean =
        mutateEntry(id) { old ->
            if (!old.isSimpleContent()) return@mutateEntry null
            val oldFile = old.allImageFileNames().firstOrNull()
            val body = text?.trim()?.takeIf { it.isNotEmpty() }
            val newFile = when (image) {
                StashImageEdit.Keep -> oldFile
                StashImageEdit.Remove -> null
                // 换图必须换文件名：缩略图缓存按文件名做 key，沿用旧名会一直显示旧图。
                is StashImageEdit.Replace ->
                    saveImage("${old.id}_${System.currentTimeMillis()}.png", image.bitmap)
                        ?: return@mutateEntry null
            }
            if (body == null && newFile == null) return@mutateEntry null
            if (oldFile != null && oldFile != newFile) File(imageDir, oldFile).delete()
            old.rebuilt(body, newFile, imageReplaced = image is StashImageEdit.Replace)
        }

    /** 锁内重新读盘，对指定条目做一次改写；[transform] 返回 null 表示不改。 */
    private suspend fun mutateEntry(id: String, transform: (StashEntry) -> StashEntry?): Boolean {
        return withContext(Dispatchers.IO) {
            withCrossProcessWrite {
                val current = readFromDisk()
                val old = current.firstOrNull { it.id == id } ?: return@withCrossProcessWrite false
                val updated = transform(old) ?: return@withCrossProcessWrite false
                val next = current.map { if (it.id == id) updated else it }
                writeToDisk(next)
                _entries.value = next
                true
            }
        }
    }

    private fun StashEntry.rebuilt(body: String?, imageFile: String?, imageReplaced: Boolean): StashEntry {
        // 钉图尺寸对应旧图，换了图就不再适用。
        val pinWidth = pinDisplayWidthPx.takeUnless { imageReplaced }
        val pinHeight = pinDisplayHeightPx.takeUnless { imageReplaced }
        return when {
            body != null && imageFile != null -> copy(
                type = StashEntryType.RICH,
                text = body,
                imageFileName = imageFile,
                contentBlocks = listOf(
                    ClipboardContentBlock.text(body),
                    ClipboardContentBlock.image(imageFile)
                ),
                htmlText = null,
                pinDisplayWidthPx = pinWidth,
                pinDisplayHeightPx = pinHeight
            )
            body != null -> copy(
                type = StashEntryType.TEXT,
                text = body,
                imageFileName = null,
                contentBlocks = emptyList(),
                htmlText = null,
                pinDisplayWidthPx = null,
                pinDisplayHeightPx = null
            )
            else -> copy(
                type = StashEntryType.IMAGE,
                text = null,
                imageFileName = imageFile,
                contentBlocks = emptyList(),
                htmlText = null,
                pinDisplayWidthPx = pinWidth,
                pinDisplayHeightPx = pinHeight
            )
        }
    }

    private fun sortOrderFor(categoryId: String?, current: List<StashEntry>): Int =
        if (categoryId == null) 0 else StashRetention.nextSortOrder(current, categoryId)

    suspend fun toggleStar(id: String) {
        withContext(Dispatchers.IO) {
            withCrossProcessWrite {
                val next = readFromDisk().map { entry ->
                    if (entry.id == id) entry.copy(starred = !entry.starred) else entry
                }
                writeToDisk(next)
                _entries.value = next
            }
        }
    }

    fun loadImage(entry: StashEntry): Bitmap? {
        val fileName = entry.imageFileName ?: return null
        return loadBitmapByFileName(fileName)
    }

    fun loadBitmapByFileName(fileName: String?): Bitmap? {
        if (fileName.isNullOrBlank()) return null
        val file = File(imageDir, fileName)
        if (!file.exists()) return null
        return BitmapFactory.decodeFile(file.absolutePath)
    }

    fun loadImageThumbnail(entry: StashEntry, maxSidePx: Int = STASH_PREVIEW_MAX_SIDE_PX): Bitmap? {
        val fileName = entry.imageFileName ?: return null
        return loadThumbnailByFileName(entry.id, fileName, maxSidePx)
    }

    fun loadEntryThumbnailsForPreview(
        entry: StashEntry,
        maxSidePx: Int = STASH_PREVIEW_MAX_SIDE_PX
    ): List<Bitmap> =
        entry.allImageFileNames().mapNotNull { fileName ->
            loadThumbnailByFileName(entry.id, fileName, maxSidePx)
        }

    fun loadThumbnailByFileName(
        entryId: String,
        fileName: String?,
        maxSidePx: Int = STASH_PREVIEW_MAX_SIDE_PX
    ): Bitmap? {
        if (fileName.isNullOrBlank()) return null
        val cacheKey = "$entryId:$fileName:side$maxSidePx"
        thumbnailCache.get(cacheKey)?.let { return it }
        val file = File(imageDir, fileName)
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxSidePx)
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
        thumbnailCache.put(cacheKey, bitmap)
        return bitmap
    }

    fun uriForFile(fileName: String?): Uri? {
        if (fileName.isNullOrBlank()) return null
        val file = File(imageDir, fileName)
        if (!file.exists()) return null
        return runCatching {
            FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                file
            )
        }.getOrNull()
    }

    fun dataUriForFile(fileName: String?): String? {
        if (fileName.isNullOrBlank()) return null
        val file = File(imageDir, fileName)
        if (!file.exists()) return null
        return runCatching {
            val bytes = file.readBytes()
            if (bytes.isEmpty()) return null
            val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
            "data:image/png;base64,$encoded"
        }.getOrNull()
    }

    fun imageDimensions(fileName: String?): Pair<Int, Int>? {
        if (fileName.isNullOrBlank()) return null
        val file = File(imageDir, fileName)
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        return if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            bounds.outWidth to bounds.outHeight
        } else {
            null
        }
    }

    private fun deleteEntryImages(entry: StashEntry) {
        entry.allImageFileNames().forEach { File(imageDir, it).delete() }
    }

    fun loadImageThumbnailForCard(
        entry: StashEntry,
        targetWidthPx: Int,
        maxVisibleHeightPx: Int
    ): Bitmap? {
        val fileName = entry.imageFileName ?: return null
        return loadThumbnailByFileNameForCard(entry.id, fileName, targetWidthPx, maxVisibleHeightPx)
    }

    fun loadEntryThumbnailsForCard(
        entry: StashEntry,
        targetWidthPx: Int,
        maxVisibleHeightPx: Int
    ): List<Bitmap> =
        entry.allImageFileNames().mapNotNull { fileName ->
            loadThumbnailByFileNameForCard(entry.id, fileName, targetWidthPx, maxVisibleHeightPx)
        }

    fun loadThumbnailByFileNameForCard(
        entryId: String,
        fileName: String?,
        targetWidthPx: Int,
        maxVisibleHeightPx: Int
    ): Bitmap? {
        if (fileName.isNullOrBlank() || targetWidthPx <= 0 || maxVisibleHeightPx <= 0) return null
        val cacheKey = "$entryId:$fileName:w$targetWidthPx:h$maxVisibleHeightPx"
        thumbnailCache.get(cacheKey)?.let { return it }
        val file = File(imageDir, fileName)
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (bounds.outWidth / sampleSize > targetWidthPx * 2) {
            sampleSize *= 2
        }
        val maxPixels = targetWidthPx.toLong() * maxVisibleHeightPx * 2L
        while (
            (bounds.outWidth.toLong() / sampleSize) * (bounds.outHeight / sampleSize) > maxPixels
        ) {
            sampleSize *= 2
        }

        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        var bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null

        if (bitmap.width != targetWidthPx) {
            val scaledHeight = (
                bitmap.height.toFloat() * targetWidthPx / bitmap.width.coerceAtLeast(1)
                ).toInt().coerceAtLeast(1)
            val scaled = bitmap.scale(targetWidthPx, scaledHeight)
            if (scaled !== bitmap) {
                bitmap.recycle()
                bitmap = scaled
            }
        }

        if (bitmap.height > maxVisibleHeightPx) {
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, maxVisibleHeightPx)
            if (cropped !== bitmap) {
                bitmap.recycle()
                bitmap = cropped
            }
        }

        thumbnailCache.put(cacheKey, bitmap)
        return bitmap
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxSidePx: Int): Int {
        var sampleSize = 1
        var longest = maxOf(width, height)
        while (longest / sampleSize > maxSidePx) {
            sampleSize *= 2
        }
        return sampleSize.coerceAtLeast(1)
    }

    private fun saveImage(fileName: String, bitmap: Bitmap): String? {
        val file = File(imageDir, fileName)
        return runCatching {
            FileOutputStream(file).use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
            fileName
        }.getOrNull()
    }

    private fun readFromDiskSync(): List<StashEntry> = readFromDisk()

    private fun readFromDisk(): List<StashEntry> {
        if (!indexFile.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<StashEntry>>(indexFile.readText())
        }.getOrDefault(emptyList())
    }

    private fun writeToDisk(entries: List<StashEntry>) {
        indexFile.writeText(json.encodeToString(entries))
    }

    /** 只淘汰超出 [MAX_ENTRIES] 的普通条目；带分类的话术不占名额、不被淘汰。 */
    private fun trimToMax(entries: List<StashEntry>): List<StashEntry> {
        val split = StashRetention.trimPlain(entries, MAX_ENTRIES)
        split.removed.forEach { deleteEntryImages(it) }
        return split.kept
    }

    private companion object {
        const val STASH_DIR_NAME = "stash"
        const val IMAGE_DIR_NAME = "images"
        const val INDEX_FILE_NAME = "index.json"
        const val STASH_PREVIEW_MAX_SIDE_PX = 720
        const val MAX_ENTRIES = 200
    }
}
