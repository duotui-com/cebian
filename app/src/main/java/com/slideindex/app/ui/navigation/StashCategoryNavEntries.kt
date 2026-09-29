package com.slideindex.app.ui.navigation

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slideindex.app.stash.StashEntry
import com.slideindex.app.stash.sortedInCategory
import com.slideindex.app.ui.StashCategoriesScreen
import com.slideindex.app.ui.StashCategoryEntriesScreen
import com.slideindex.app.ui.StashPhraseEditorScreen
import com.slideindex.app.ui.viewmodel.StashCategoryViewModel
import com.slideindex.app.ui.viewmodel.StashEditorImage
import com.slideindex.app.ui.viewmodel.StashPhraseEditorViewModel
import top.yukonga.miuix.kmp.nav.core.NavEntryBuilder

/** 暂存夹「分类与话术管理」：分类列表 → 分类内条目 → 新增 / 编辑一条。 */
fun NavEntryBuilder.stashCategoryNavEntries(ctx: MainNavContext) {
    hiltEntry<AppNavKey.StashCategories> {
        val viewModel: StashCategoryViewModel = hiltViewModel()
        val categories by viewModel.categories.collectAsStateWithLifecycle()
        val entries by viewModel.entries.collectAsStateWithLifecycle()
        val collapsePanelAfterSend by viewModel.collapsePanelAfterSend.collectAsStateWithLifecycle()
        val permissions = ctx.collectPermissions()
        val entryCounts = remember(entries) {
            entries.mapNotNull { it.categoryId }.groupingBy { it }.eachCount()
        }
        StashCategoriesScreen(
            categories = categories,
            entryCounts = entryCounts,
            collapsePanelAfterSend = collapsePanelAfterSend,
            accessibilityGranted = permissions.accessibilityGranted,
            onBack = { ctx.navigateBackTo(AppNavKey.StashClipboard) },
            onOpenCategory = { ctx.navigate(AppNavKey.StashCategoryEntries(it)) },
            onAddCategory = viewModel::addCategory,
            onRenameCategory = viewModel::renameCategory,
            onDeleteCategory = viewModel::deleteCategory,
            onReorderCategories = viewModel::reorderCategories,
            onCollapsePanelAfterSendChange = viewModel::setCollapsePanelAfterSend,
            onOpenAccessibilitySettings = { ctx.openAccessibilitySettings() },
        )
    }

    hiltEntry<AppNavKey.StashCategoryEntries> { key ->
        val viewModel: StashCategoryViewModel = hiltViewModel()
        val categories by viewModel.categories.collectAsStateWithLifecycle()
        val entries by viewModel.entries.collectAsStateWithLifecycle()
        val category = categories.firstOrNull { it.id == key.categoryId }
        val inCategory: List<StashEntry> = remember(entries, key.categoryId) {
            entries.filter { it.categoryId == key.categoryId }.sortedInCategory()
        }
        StashCategoryEntriesScreen(
            category = category,
            entries = inCategory,
            otherCategories = categories.filter { it.id != key.categoryId },
            loadThumbnail = viewModel::loadThumbnail,
            onBack = { ctx.navigateBackTo(AppNavKey.StashCategories) },
            onAddEntry = { ctx.navigate(AppNavKey.StashPhraseEditor(categoryId = key.categoryId)) },
            onEditEntry = { entryId ->
                ctx.navigate(AppNavKey.StashPhraseEditor(categoryId = key.categoryId, entryId = entryId))
            },
            onDeleteEntry = viewModel::deleteEntry,
            onMoveEntry = viewModel::moveEntry,
            onReorderEntries = { orderedIds -> viewModel.reorderEntries(key.categoryId, orderedIds) },
            onClearCategory = { viewModel.clearCategory(key.categoryId) },
        )
    }

    hiltEntry<AppNavKey.StashPhraseEditor> { key ->
        val viewModel: StashPhraseEditorViewModel = hiltViewModel()
        val state by viewModel.state.collectAsStateWithLifecycle()
        LaunchedEffect(key.entryId) { viewModel.load(key.entryId) }
        val existingFileName = (state.image as? StashEditorImage.Existing)?.fileName
        val existingImage by produceState<Bitmap?>(null, key.entryId, existingFileName) {
            value = existingFileName?.let { viewModel.loadExistingImage(key.entryId, it) }
        }
        // 用系统文件选择器（GetContent）而不是照片选择器：和应用里其他选图入口一致，能浏览所有文件夹，
        // 不像照片选择器那样只列出系统认为的“相册”，也不需要额外的媒体权限。
        val imagePicker = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent(),
        ) { uri -> uri?.let(viewModel::pickImage) }
        StashPhraseEditorScreen(
            isNew = key.entryId.isEmpty(),
            state = state,
            existingImage = existingImage,
            onBack = { ctx.navigateBackTo(AppNavKey.StashCategoryEntries(key.categoryId)) },
            onTextChange = viewModel::setText,
            onPickImage = {
                imagePicker.launch("image/*")
            },
            onRemoveImage = viewModel::removeImage,
            onSave = {
                viewModel.save(key.categoryId, key.entryId) {
                    ctx.navigateBackTo(AppNavKey.StashCategoryEntries(key.categoryId))
                }
            },
        )
    }
}
