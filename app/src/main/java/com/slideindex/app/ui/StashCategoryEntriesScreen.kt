@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.slideindex.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.slideindex.app.R
import com.slideindex.app.stash.StashCategory
import com.slideindex.app.stash.StashEntry
import com.slideindex.app.stash.StashEntryType
import com.slideindex.app.stash.allImageFileNames
import com.slideindex.app.stash.combinedText
import com.slideindex.app.ui.miuix.MiuixConfirmDialog
import com.slideindex.app.ui.miuix.MiuixRadioSelectDialog
import com.slideindex.app.ui.miuix.groupedCardItems
import com.slideindex.app.ui.miuix.miuixGroupedCardItem
import com.slideindex.app.ui.settings.components.LazySettingsItem
import com.slideindex.app.ui.settings.components.SettingLinkRow
import com.slideindex.app.ui.settings.components.SettingNavigationRow
import com.slideindex.app.ui.settings.components.SettingsScreenScaffold
import com.slideindex.app.ui.settings.components.SettingsVerticalReorderList
import com.slideindex.app.ui.settings.components.settingsCardScopeItem
import com.slideindex.app.ui.settings.components.settingsLazyHint
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Folder

/** 「移到其它分类」里的一个去处；[categoryId] 为 null 表示移出分类。 */
private data class MoveTarget(val categoryId: String?, val label: String)

/** 不与任何去处相等，用来让单选框一个都不选中。 */
private val NoMoveTargetSelected = MoveTarget(categoryId = "", label = "")

private const val ENTRY_TITLE_MAX_CHARS = 60

/** 某个分类里的条目：新增 / 编辑 / 移动 / 删除 / 长按拖动排序 / 清空。 */
@Composable
fun StashCategoryEntriesScreen(
    category: StashCategory?,
    entries: List<StashEntry>,
    otherCategories: List<StashCategory>,
    loadThumbnail: suspend (StashEntry) -> Bitmap?,
    onBack: () -> Unit,
    onAddEntry: () -> Unit,
    onEditEntry: (String) -> Unit,
    onDeleteEntry: (String) -> Unit,
    onMoveEntry: (entryId: String, categoryId: String?) -> Unit,
    onReorderEntries: (List<String>) -> Unit,
    onClearCategory: () -> Unit,
) {
    // 分类在别处被删掉了：这一页已经没有意义。
    if (category == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    var moving by remember { mutableStateOf<StashEntry?>(null) }
    var deleting by remember { mutableStateOf<StashEntry?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    // LazyListScope 顶层不能调用 @Composable，文案先在外面取好。
    val entriesEmptyHint = stringResource(R.string.stash_category_entries_empty)
    val reorderHint = stringResource(R.string.stash_category_entries_reorder_hint)
    val uncategorizedLabel = stringResource(R.string.stash_category_move_uncategorized)
    val moveTargets = remember(otherCategories, uncategorizedLabel) {
        listOf(MoveTarget(categoryId = null, label = uncategorizedLabel)) +
            otherCategories.map { MoveTarget(categoryId = it.id, label = it.name) }
    }

    SettingsScreenScaffold(
        title = category.name,
        onBack = onBack,
    ) {
        groupedCardItems(
            keyPrefix = "stash-entries-add",
            items = listOf(
                settingsCardScopeItem("add-entry") {
                    SettingNavigationRow(
                        icon = { label ->
                            Icon(
                                MiuixIcons.Add,
                                contentDescription = label,
                                modifier = Modifier.size(24.dp),
                            )
                        },
                        title = stringResource(R.string.stash_category_entries_add),
                        subtitle = stringResource(R.string.stash_category_entries_add_desc),
                        onClick = onAddEntry,
                    )
                },
            ),
        )
        if (entries.isEmpty()) {
            settingsLazyHint(
                key = "stash-entries-empty",
                text = entriesEmptyHint,
            )
        } else {
            settingsLazyHint(
                key = "stash-entries-reorder-hint",
                text = reorderHint,
            )
            LazySettingsItem(key = "stash-entry-list") {
                SettingsVerticalReorderList(
                    items = entries,
                    key = { it.id },
                    onReorder = { ordered -> onReorderEntries(ordered.map { it.id }) },
                ) { entry, _, segmentIndex, segmentCount, dragModifier ->
                    StashEntryRow(
                        entry = entry,
                        loadThumbnail = loadThumbnail,
                        segmentIndex = segmentIndex,
                        segmentCount = segmentCount,
                        onClick = { onEditEntry(entry.id) },
                        onMove = { moving = entry },
                        onDelete = { deleting = entry },
                        modifier = dragModifier,
                    )
                }
            }
        }
        groupedCardItems(
            keyPrefix = "stash-entries-clear",
            items = listOf(
                settingsCardScopeItem("clear-category") {
                    SettingLinkRow(
                        title = stringResource(R.string.stash_category_clear),
                        subtitle = stringResource(R.string.stash_category_clear_desc),
                        enabled = entries.isNotEmpty(),
                        onClick = { confirmClear = true },
                    )
                },
            ),
        )
    }

    MiuixRadioSelectDialog(
        show = moving != null,
        onDismissRequest = { moving = null },
        title = stringResource(R.string.stash_category_move_title),
        options = moveTargets,
        selected = NoMoveTargetSelected,
        optionLabel = { it.label },
        onSelect = { target -> moving?.let { onMoveEntry(it.id, target.categoryId) } },
    )

    MiuixConfirmDialog(
        show = deleting != null,
        onDismissRequest = { deleting = null },
        title = stringResource(R.string.stash_category_entry_delete_title),
        message = stringResource(R.string.stash_category_entry_delete_message),
        onConfirm = { deleting?.let { onDeleteEntry(it.id) } },
    )

    MiuixConfirmDialog(
        show = confirmClear,
        onDismissRequest = { confirmClear = false },
        title = stringResource(R.string.stash_category_clear_confirm_title),
        message = stringResource(R.string.stash_category_clear_confirm_message, category.name, entries.size),
        onConfirm = onClearCategory,
    )
}

@Composable
private fun StashEntryRow(
    entry: StashEntry,
    loadThumbnail: suspend (StashEntry) -> Bitmap?,
    segmentIndex: Int,
    segmentCount: Int,
    onClick: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasImage = entry.allImageFileNames().isNotEmpty()
    val bodyText = entry.combinedText().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
    val title = bodyText.take(ENTRY_TITLE_MAX_CHARS).ifEmpty { stringResource(R.string.stash_category_entry_image_only) }
    val thumbnail: (@Composable () -> Unit)? = if (hasImage) {
        { StashEntryThumbnail(entry, loadThumbnail) }
    } else {
        null
    }
    BasicComponent(
        modifier = modifier.miuixGroupedCardItem(segmentIndex, segmentCount),
        title = title,
        summary = stringResource(
            when (entry.type) {
                StashEntryType.TEXT -> R.string.stash_category_entry_type_text
                StashEntryType.IMAGE -> R.string.stash_category_entry_type_image
                StashEntryType.RICH -> R.string.stash_category_entry_type_rich
            },
        ),
        onClick = onClick,
        startAction = thumbnail,
        endActions = {
            IconButton(onClick = onMove) {
                MiuixIcon(
                    imageVector = MiuixIcons.Folder,
                    contentDescription = stringResource(R.string.stash_category_move),
                )
            }
            IconButton(onClick = onDelete) {
                MiuixIcon(
                    imageVector = MiuixIcons.Delete,
                    contentDescription = stringResource(R.string.stash_action_delete),
                )
            }
        },
    )
}

@Composable
private fun StashEntryThumbnail(
    entry: StashEntry,
    loadThumbnail: suspend (StashEntry) -> Bitmap?,
) {
    val bitmap by produceState<Bitmap?>(null, entry.id, entry.imageFileName, entry.contentBlocks) {
        value = loadThumbnail(entry)
    }
    val image = bitmap?.let { remember(it) { it.asImageBitmap() } } ?: return
    Image(
        bitmap = image,
        contentDescription = null,
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(8.dp)),
        contentScale = ContentScale.Crop,
    )
}
