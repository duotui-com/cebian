package com.slideindex.app.stash

import android.content.Context
import com.slideindex.app.util.CrossProcessStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * 暂存夹分类。存放在 `filesDir/stash/categories.json`：与 `index.json` 同目录，随 `stash` 目录一起备份。
 *
 * 分类只记名字和顺序；条目通过 [StashEntry.categoryId] 归属分类，条目本身仍由 [StashRepository] 管理。
 */
@Singleton
class StashCategoryRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val stashRepository: StashRepository
) {
    private val appContext = context.applicationContext
    private val categoriesFile = File(File(appContext.filesDir, STASH_DIR_NAME).apply { mkdirs() }, FILE_NAME)
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    private val _categories = MutableStateFlow<List<StashCategory>>(emptyList())
    val categories: StateFlow<List<StashCategory>> = _categories.asStateFlow()

    /** 写操作统一入口：进程内 mutex + 跨进程文件锁，写完广播通知其它进程重载（同 [StashRepository]）。 */
    private suspend fun <T> withCrossProcessWrite(block: suspend () -> T): T =
        mutex.withLock {
            CrossProcessStore.withFileLock(categoriesFile) {
                val result = block()
                CrossProcessStore.notifyChanged(appContext, categoriesFile)
                result
            }
        }

    init {
        CrossProcessStore.registerListener(appContext, categoriesFile) {
            mutex.withLock { _categories.value = readFromDisk() }
        }
        _categories.value = readFromDisk()
        StashAccess.categoryRepository = this
    }

    /** 新建分类，排在最后；名称为空或与现有分类重名时返回 null。 */
    suspend fun add(name: String): StashCategory? {
        val normalized = normalizeName(name) ?: return null
        return withContext(Dispatchers.IO) {
            withCrossProcessWrite {
                val current = readFromDisk()
                if (isDuplicate(current, normalized, exceptId = null)) return@withCrossProcessWrite null
                val category = StashCategory(
                    id = UUID.randomUUID().toString(),
                    name = normalized,
                    sortOrder = (current.maxOfOrNull { it.sortOrder } ?: -1) + 1,
                    createdAtEpochMs = System.currentTimeMillis()
                )
                val next = current + category
                writeToDisk(next)
                _categories.value = next
                category
            }
        }
    }

    /** 重命名；分类不存在、名称为空或与其它分类重名时返回 false。 */
    suspend fun rename(id: String, name: String): Boolean {
        val normalized = normalizeName(name) ?: return false
        return withContext(Dispatchers.IO) {
            withCrossProcessWrite {
                val current = readFromDisk()
                if (current.none { it.id == id }) return@withCrossProcessWrite false
                if (isDuplicate(current, normalized, exceptId = id)) return@withCrossProcessWrite false
                val next = current.map { if (it.id == id) it.copy(name = normalized) else it }
                writeToDisk(next)
                _categories.value = next
                true
            }
        }
    }

    /**
     * 删除分类。分类下的条目**不删除**，变回普通暂存条目。
     *
     * 先放回条目、再删分类：中途失败最多留下一个空分类，不会留下指向已删分类的条目。
     */
    suspend fun delete(id: String) {
        stashRepository.releaseCategory(id)
        withContext(Dispatchers.IO) {
            withCrossProcessWrite {
                val next = readFromDisk().filterNot { it.id == id }
                writeToDisk(next)
                _categories.value = next
            }
        }
    }

    /** 按 [orderedIds] 重排分类；没出现在列表里的分类保持原相对顺序排在后面。 */
    suspend fun reorder(orderedIds: List<String>) {
        withContext(Dispatchers.IO) {
            withCrossProcessWrite {
                val current = readFromDisk()
                val byId = current.associateBy { it.id }
                val listed = orderedIds.distinct().mapNotNull { byId[it] }
                val listedIds = listed.mapTo(HashSet()) { it.id }
                val next = (listed + current.filter { it.id !in listedIds })
                    .mapIndexed { index, category -> category.copy(sortOrder = index) }
                writeToDisk(next)
                _categories.value = next
            }
        }
    }

    private fun normalizeName(name: String): String? =
        name.trim().take(MAX_NAME_LENGTH).trim().takeIf { it.isNotEmpty() }

    private fun isDuplicate(current: List<StashCategory>, name: String, exceptId: String?): Boolean =
        current.any { it.id != exceptId && it.name.equals(name, ignoreCase = true) }

    private fun readFromDisk(): List<StashCategory> {
        if (!categoriesFile.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<StashCategory>>(categoriesFile.readText())
        }.getOrDefault(emptyList()).sortedWith(compareBy<StashCategory>({ it.sortOrder }, { it.createdAtEpochMs }))
    }

    private fun writeToDisk(categories: List<StashCategory>) {
        categoriesFile.writeText(json.encodeToString(categories))
    }

    companion object {
        /** 分类名上限：筛选条上的标签放不下太长的名字。 */
        const val MAX_NAME_LENGTH = 20

        private const val STASH_DIR_NAME = "stash"
        private const val FILE_NAME = "categories.json"
    }
}
