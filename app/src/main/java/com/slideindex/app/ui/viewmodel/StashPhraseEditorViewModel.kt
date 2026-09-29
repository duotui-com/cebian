package com.slideindex.app.ui.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slideindex.app.R
import com.slideindex.app.clipboard.ClipboardBlockKind
import com.slideindex.app.stash.StashImageEdit
import com.slideindex.app.stash.StashImageImport
import com.slideindex.app.stash.StashRepository
import com.slideindex.app.stash.StashRichPart
import com.slideindex.app.stash.allImageFileNames
import com.slideindex.app.stash.isSimpleContent
import com.slideindex.app.stash.resolvedContentBlocks
import com.slideindex.app.ui.feedback.UserMessageBus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 编辑页里的图片：没有 / 条目原有的 / 刚从相册选的。 */
sealed interface StashEditorImage {
    data object None : StashEditorImage
    data class Existing(val fileName: String) : StashEditorImage
    class Picked(val bitmap: Bitmap) : StashEditorImage
}

data class StashPhraseEditorState(
    val loaded: Boolean = false,
    /** 含多段文字或多张图的复杂条目不支持在这里改写。 */
    val editable: Boolean = true,
    val text: String = "",
    val image: StashEditorImage = StashEditorImage.None,
    val saving: Boolean = false,
) {
    val canSave: Boolean
        get() = loaded && editable && !saving && (text.isNotBlank() || image !is StashEditorImage.None)
}

/** 新增 / 编辑一条话术：文字、图片，或图文。 */
@HiltViewModel
class StashPhraseEditorViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val stashRepository: StashRepository,
    private val userMessageBus: UserMessageBus,
) : ViewModel() {
    private val _state = MutableStateFlow(StashPhraseEditorState())
    val state: StateFlow<StashPhraseEditorState> = _state.asStateFlow()

    private var loadedEntryId: String? = null

    /** [entryId] 为空表示新增。同一个编辑页只加载一次，旋转屏幕等重组不会冲掉已输入的内容。 */
    fun load(entryId: String) {
        if (loadedEntryId == entryId) return
        loadedEntryId = entryId
        if (entryId.isEmpty()) {
            _state.value = StashPhraseEditorState(loaded = true)
            return
        }
        val entry = stashRepository.entries.value.firstOrNull { it.id == entryId }
        _state.value = if (entry == null) {
            StashPhraseEditorState(loaded = true, editable = false)
        } else {
            StashPhraseEditorState(
                loaded = true,
                editable = entry.isSimpleContent(),
                text = entry.resolvedContentBlocks()
                    .firstOrNull { it.kind == ClipboardBlockKind.TEXT }?.text.orEmpty(),
                image = entry.allImageFileNames().firstOrNull()
                    ?.let { StashEditorImage.Existing(it) } ?: StashEditorImage.None
            )
        }
    }

    fun setText(text: String) {
        _state.update { it.copy(text = text) }
    }

    fun pickImage(uri: Uri) {
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) { StashImageImport.decode(appContext, uri) }
            if (bitmap == null) {
                userMessageBus.showError(appContext.getString(R.string.stash_category_image_load_failed))
            } else {
                _state.update { it.copy(image = StashEditorImage.Picked(bitmap)) }
            }
        }
    }

    fun removeImage() {
        _state.update { it.copy(image = StashEditorImage.None) }
    }

    /** 条目原有图片的缩略图，供编辑页预览。 */
    suspend fun loadExistingImage(entryId: String, fileName: String): Bitmap? = withContext(Dispatchers.IO) {
        stashRepository.loadThumbnailByFileName(entryId, fileName, PREVIEW_MAX_SIDE_PX)
    }

    /** [entryId] 为空表示新增到 [categoryId]；保存成功后回调 [onSaved]。 */
    fun save(categoryId: String, entryId: String, onSaved: () -> Unit) {
        val current = _state.value
        if (!current.canSave) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val saved = if (entryId.isEmpty()) create(categoryId, current) else update(entryId, current)
            if (saved) {
                onSaved()
            } else {
                _state.update { it.copy(saving = false) }
                userMessageBus.showError(appContext.getString(R.string.stash_category_save_failed))
            }
        }
    }

    private suspend fun create(categoryId: String, state: StashPhraseEditorState): Boolean {
        val text = state.text.trim()
        val bitmap = (state.image as? StashEditorImage.Picked)?.bitmap
        val created = when {
            text.isNotEmpty() && bitmap != null -> stashRepository.addRich(
                parts = listOf(StashRichPart.Text(text), StashRichPart.Image(bitmap)),
                categoryId = categoryId
            )
            bitmap != null -> stashRepository.addImage(bitmap, categoryId = categoryId)
            else -> stashRepository.addText(text, categoryId = categoryId)
        }
        return created != null
    }

    private suspend fun update(entryId: String, state: StashPhraseEditorState): Boolean {
        val imageEdit = when (val image = state.image) {
            StashEditorImage.None -> StashImageEdit.Remove
            is StashEditorImage.Existing -> StashImageEdit.Keep
            is StashEditorImage.Picked -> StashImageEdit.Replace(image.bitmap)
        }
        return stashRepository.updateContent(entryId, state.text, imageEdit)
    }

    private companion object {
        const val PREVIEW_MAX_SIDE_PX = 720
    }
}
