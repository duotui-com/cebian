package com.slideindex.app.ui.viewmodel

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slideindex.app.R
import com.slideindex.app.stash.StashCategory
import com.slideindex.app.stash.StashCategoryRepository
import com.slideindex.app.stash.StashEntry
import com.slideindex.app.stash.StashRepository
import com.slideindex.app.stash.StashSendPreferences
import com.slideindex.app.ui.feedback.UserMessageBus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 「分类与话术管理」的两个列表页（分类列表、分类内条目列表）共用。 */
@HiltViewModel
class StashCategoryViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val stashRepository: StashRepository,
    private val categoryRepository: StashCategoryRepository,
    private val sendPreferences: StashSendPreferences,
    private val userMessageBus: UserMessageBus,
) : ViewModel() {
    val categories: StateFlow<List<StashCategory>> = categoryRepository.categories
    val entries: StateFlow<List<StashEntry>> = stashRepository.entries
    val collapsePanelAfterSend: StateFlow<Boolean> = sendPreferences.collapsePanelAfterSend

    fun addCategory(name: String) {
        viewModelScope.launch {
            if (categoryRepository.add(name) == null) showNameRejected()
        }
    }

    fun renameCategory(id: String, name: String) {
        viewModelScope.launch {
            if (!categoryRepository.rename(id, name)) showNameRejected()
        }
    }

    /** 分类下的条目不删除，变回普通暂存条目。 */
    fun deleteCategory(id: String) {
        viewModelScope.launch { categoryRepository.delete(id) }
    }

    fun reorderCategories(orderedIds: List<String>) {
        viewModelScope.launch { categoryRepository.reorder(orderedIds) }
    }

    fun clearCategory(id: String) {
        viewModelScope.launch { stashRepository.clearCategory(id) }
    }

    /** [categoryId] 为 null 表示移出分类。 */
    fun moveEntry(entryId: String, categoryId: String?) {
        viewModelScope.launch { stashRepository.moveToCategory(entryId, categoryId) }
    }

    fun deleteEntry(entryId: String) {
        viewModelScope.launch { stashRepository.delete(entryId) }
    }

    fun reorderEntries(categoryId: String, orderedIds: List<String>) {
        viewModelScope.launch { stashRepository.reorderInCategory(categoryId, orderedIds) }
    }

    fun setCollapsePanelAfterSend(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { sendPreferences.setCollapsePanelAfterSend(enabled) }
    }

    /** 列表行左侧的缩略图：条目里的第一张图；没有图片返回 null。 */
    suspend fun loadThumbnail(entry: StashEntry): Bitmap? = withContext(Dispatchers.IO) {
        stashRepository.loadEntryThumbnailsForPreview(entry, THUMBNAIL_MAX_SIDE_PX).firstOrNull()
    }

    private fun showNameRejected() {
        userMessageBus.showError(appContext.getString(R.string.stash_category_name_rejected))
    }

    private companion object {
        const val THUMBNAIL_MAX_SIDE_PX = 192
    }
}
