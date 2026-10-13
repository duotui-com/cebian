package com.slideindex.app.overlay.history

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.slideindex.app.R
import com.slideindex.app.clipboard.ClipboardDragShareFallback
import com.slideindex.app.overlay.FloatBallStashPanel
import com.slideindex.app.overlay.FloatBallTextPick
import com.slideindex.app.overlay.PickResultFromHistoryCoordinator
import com.slideindex.app.stash.StashAccess
import com.slideindex.app.stash.StashCategory
import com.slideindex.app.stash.StashCoordinator
import com.slideindex.app.stash.StashEntry
import com.slideindex.app.stash.StashEntryType
import com.slideindex.app.stash.StashRepository
import com.slideindex.app.stash.allImageFileNames
import com.slideindex.app.stash.combinedText
import com.slideindex.app.stash.exportText
import com.slideindex.app.stash.resolvedContentBlocks
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 话术条目里操作按钮的边长。 */
private val PhraseActionButtonSize = 28.dp

/** 左侧缩略图的边长。 */
private val PhraseThumbSize = 64.dp

/**
 * 暂存夹里一条**话术**（分类视图 / 全部视图里的条目）：左边缩略图（有图才有），
 * 右边两行文案 + 一排按钮（复制 / 置顶 / 更多）。
 *
 * 点按整条就发送；点缩略图先弹出大图预览（[onPreviewImage]），在预览里确认后再发送。
 * 文案固定最多两行、超出截断，不再展开；没有卡片底色，条与条之间是分隔线。
 * 按钮靠抽屉内侧（贴着屏幕边那一侧）排：[buttonsAtEnd] 为 true 时右对齐。
 *
 * 与闪念时间线里的 [HistoryStashEntryCard]（上游的卡片，未分类视图用）是两套互不依赖的组件：
 * 话术要的是“一屏多条、点一下就发”，闪念要的是“能展开、能编辑、有标签 / 提醒”。
 * 这里刻意不改 [HistoryStashEntryCard] 和 `HistoryEntryCardParts.kt`，同步上游时才不会再撞车。
 *
 * [onEdit] 非空时，⋮ 菜单里多一项「编辑」，打开面板的就地编辑条。
 */
@Composable
internal fun HistoryPhraseEntryCard(
    entry: StashEntry,
    buttonsAtEnd: Boolean,
    onPreviewImage: () -> Unit,
    onShowMessage: (Int) -> Unit,
    onPin: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onSend: () -> Unit,
    onTogglePinTop: () -> Unit,
    onDelete: () -> Unit,
    categoryUi: StashCardCategoryUi,
    onEdit: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val repo = StashAccess.repository
    val theme = historyTheme()
    var showCategoryMenu by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val thumbPx = remember(density) { with(density) { 96.dp.roundToPx() } }
    val richBlocks = remember(entry.id, entry.contentBlocks, entry.type, entry.text, entry.imageFileName) {
        entry.resolvedContentBlocks()
    }
    val summaryText = remember(entry.id, entry.type, entry.text, richBlocks) {
        when (entry.type) {
            StashEntryType.TEXT -> entry.text.orEmpty()
            StashEntryType.RICH -> entry.combinedText()
            else -> ""
        }
    }
    val richImageFileNames = remember(entry.id, entry.contentBlocks, entry.imageFileName) {
        entry.allImageFileNames()
    }
    val singleThumb = rememberLoadedSingleThumb(
        entryId = entry.id,
        loadKey = listOf(thumbPx, entry.type),
        enabled = entry.type == StashEntryType.IMAGE,
        loader = { repo?.loadImageThumbnailForCard(entry, thumbPx, thumbPx) },
    )
    val (richThumbnails, _) = rememberLoadedThumbnails(
        entryId = entry.id,
        loadKey = listOf(richImageFileNames, thumbPx, entry.type),
        enabled = entry.type == StashEntryType.RICH && richImageFileNames.isNotEmpty(),
        loader = { repo?.loadEntryThumbnailsForCard(entry, thumbPx, thumbPx).orEmpty() },
    )
    // 分享 / 保存图片针对缩略图那张：图片条目是它自己，图文条目是第一张。
    val shownBitmap = when (entry.type) {
        StashEntryType.IMAGE -> singleThumb
        StashEntryType.RICH -> richThumbnails.firstOrNull()
        else -> null
    }
    val hasThumb = entry.type == StashEntryType.IMAGE ||
        (entry.type == StashEntryType.RICH && richImageFileNames.isNotEmpty())
    val shareLabel = stringResource(R.string.float_ball_action_share)
    val pinLabel = stringResource(R.string.stash_action_pin)
    val pickLabel = stringResource(R.string.stash_action_open_pick)
    val saveImageLabel = stringResource(R.string.clipboard_action_save_image)
    val deleteLabel = stringResource(R.string.stash_action_delete)
    val editLabel = stringResource(R.string.stash_action_edit)
    val moveToCategoryLabel = stringResource(R.string.stash_category_move_to)
    val moreLabel = stringResource(R.string.notification_filter_more_menu)
    val onLongPressDrag: () -> Unit = {
        val clipData = HistoryEntryDragHelper.buildClipForStashEntry(context, entry, repo)
        if (clipData == null) {
            onShowMessage(R.string.history_drag_unsupported)
        } else {
            val started = HistoryEntryDragHelper.startDrag(
                view = view,
                clipData = clipData,
                preview = HistoryEntryDragHelper.previewForStashEntry(entry, singleThumb, richThumbnails),
                onDragStart = { FloatBallStashPanel.setDragHidden(true) },
                onDragEnd = { FloatBallStashPanel.setDragHidden(false) },
                onDropRejected = {
                    if (ClipboardDragShareFallback.hasShareableContent(clipData) &&
                        !ClipboardDragShareFallback.shareToForegroundHost(context, clipData)
                    ) {
                        onShowMessage(R.string.history_drag_unsupported)
                    }
                },
            )
            if (!started) {
                if (!ClipboardDragShareFallback.shareToForegroundHost(context, clipData)) {
                    onShowMessage(R.string.history_drag_unsupported)
                }
            }
        }
    }

    HistoryStashItemShell(
        leading = if (hasThumb) {
            { PhraseThumb(shownBitmap, onClick = onPreviewImage) }
        } else {
            null
        },
        onClick = onSend,
        onLongPress = onLongPressDrag,
    ) {
        HistoryCollapsedSummaryText(text = summaryText, maxLines = 2)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (buttonsAtEnd) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhraseActionButton(
                icon = Icons.Default.ContentCopy,
                contentDescription = stringResource(R.string.clipboard_history_float_copy),
                onClick = onCopy,
            )
            PhraseActionButton(
                icon = Icons.Default.VerticalAlignTop,
                contentDescription = stringResource(
                    if (entry.starred) R.string.stash_category_unpin_top else R.string.stash_category_pin_top,
                ),
                onClick = onTogglePinTop,
                // 已置顶用主题色标出来。
                tint = if (entry.starred) theme.accentSolid else null,
            )
            PhraseOverflowMenu(
                contentDescription = moreLabel,
                actions = buildList {
                    if (onEdit != null) {
                        add(HistoryCardMenuAction(label = editLabel, icon = Icons.Outlined.Edit, onClick = onEdit))
                    }
                    add(
                        HistoryCardMenuAction(
                            label = shareLabel,
                            icon = Icons.Default.Share,
                            onClick = {
                                when {
                                    entry.type == StashEntryType.RICH && shownBitmap != null ->
                                        FloatBallTextPick.shareScreenshot(context, shownBitmap)
                                    entry.type == StashEntryType.RICH && summaryText.isNotBlank() ->
                                        FloatBallTextPick.shareText(context, summaryText)
                                    else -> onShare()
                                }
                            },
                        ),
                    )
                    add(HistoryCardMenuAction(label = pinLabel, icon = Icons.Default.PushPin, onClick = onPin))
                    add(
                        HistoryCardMenuAction(
                            label = pickLabel,
                            icon = Icons.Outlined.TextFields,
                            onClick = { PickResultFromHistoryCoordinator.openFromStash(context, entry, 0) },
                        ),
                    )
                    add(
                        HistoryCardMenuAction(
                            label = moveToCategoryLabel,
                            icon = Icons.Outlined.Folder,
                            onClick = { showCategoryMenu = true },
                        ),
                    )
                    if (entry.type == StashEntryType.RICH && shownBitmap != null) {
                        add(
                            HistoryCardMenuAction(
                                label = saveImageLabel,
                                icon = Icons.Outlined.Save,
                                onClick = {
                                    val saved = FloatBallTextPick.saveScreenshot(context, shownBitmap)
                                    onShowMessage(
                                        if (saved) R.string.float_ball_screenshot_saved else R.string.float_ball_action_failed,
                                    )
                                },
                            ),
                        )
                    }
                    add(
                        HistoryCardMenuAction(
                            label = deleteLabel,
                            icon = Icons.Default.Delete,
                            onClick = onDelete,
                            iconTint = MiuixTheme.colorScheme.error,
                            destructive = true,
                        ),
                    )
                },
                // 「移入分类」的选择菜单锚在溢出菜单按钮的位置。
                anchoredContent = {
                    StashCategoryPickerMenu(
                        expanded = showCategoryMenu,
                        onDismiss = { showCategoryMenu = false },
                        ui = categoryUi,
                    )
                },
            )
        }
    }
}

/** 一枚小号操作按钮；[tint] 为空就跟随正文色（略淡，和上游卡片的操作行一致）。 */
@Composable
private fun PhraseActionButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color? = null,
) {
    val theme = historyTheme()
    IconButton(onClick = onClick, modifier = Modifier.size(PhraseActionButtonSize)) {
        MiuixIcon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(PhraseActionButtonSize - 12.dp),
            tint = tint ?: theme.text.copy(alpha = 0.84f),
        )
    }
}

/** 小号 ⋮ 溢出菜单；[anchoredContent] 和菜单按钮锚在同一个位置（比如「移入分类」的二级菜单）。 */
@Composable
private fun PhraseOverflowMenu(
    contentDescription: String,
    actions: List<HistoryCardMenuAction>,
    anchoredContent: @Composable () -> Unit,
) {
    if (actions.isEmpty()) return
    val scheme = MiuixTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(PhraseActionButtonSize)) {
            MiuixIcon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = contentDescription,
                modifier = Modifier.size(PhraseActionButtonSize - 12.dp),
                tint = scheme.onBackground,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            actions.forEachIndexed { index, action ->
                if (action.destructive && index > 0) {
                    HorizontalDivider(color = scheme.dividerLine)
                }
                DropdownMenuItem(
                    text = { Text(action.label, style = HistoryPanelTypography.content()) },
                    onClick = {
                        expanded = false
                        action.onClick()
                    },
                    leadingIcon = {
                        MiuixIcon(
                            imageVector = action.icon,
                            contentDescription = null,
                            tint = action.iconTint ?: scheme.onBackground,
                        )
                    },
                )
            }
        }
        anchoredContent()
    }
}

/** 左侧缩略图：固定方块，图还没加载出来时先占位，免得列表跳动；点按弹出大图预览。 */
@Composable
private fun PhraseThumb(bitmap: Bitmap?, onClick: () -> Unit) {
    val imageBitmap = rememberHistoryImageBitmap(bitmap)
    Box(
        modifier = Modifier
            .size(PhraseThumbSize)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
    ) {
        if (imageBitmap != null) {
            Image(
                bitmap = imageBitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

/**
 * 「全部」和各分类下的话术列表：[HistoryPhraseEntryCard] 一条挨一条，条与条之间是分隔线。
 *
 * 列表状态 [listState] 由上面的 `HistoryStashTabBody` 持有（头部收起判断、新条目回顶都读它），
 * 这里只负责画。点一条 = 发送；操作都落在 [HistoryPhraseEntryCard] 的按钮和 ⋮ 菜单里。
 */
@Composable
internal fun HistoryPhraseList(
    entries: List<StashEntry>,
    listState: LazyListState,
    listBottomPadding: Dp,
    repo: StashRepository?,
    categories: List<StashCategory>,
    buttonsAtEnd: Boolean,
    onPreviewImage: (StashEntry) -> Unit,
    onEditEntry: (StashEntry) -> Unit,
    onDeleteEntry: (StashEntry) -> Unit,
    onShowMessage: (Int) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        // 没有卡片了：内容贴着抽屉边，条与条之间由每条自带的分隔线隔开。
        contentPadding = PaddingValues(top = 2.dp, bottom = 8.dp + listBottomPadding),
    ) {
        items(entries, key = { it.id }, contentType = { "phrase_entry" }) { entry ->
            HistoryPhraseEntryCard(
                entry = entry,
                buttonsAtEnd = buttonsAtEnd,
                onPreviewImage = { onPreviewImage(entry) },
                onShowMessage = onShowMessage,
                onPin = {
                    when (entry.type) {
                        StashEntryType.TEXT -> StashCoordinator.pinTextToScreen(context, entry.text.orEmpty())
                        StashEntryType.IMAGE -> {
                            val bitmap = repo?.loadImage(entry) ?: return@HistoryPhraseEntryCard
                            StashCoordinator.pinImageFromStash(context, entry, bitmap)
                        }
                        StashEntryType.RICH -> StashCoordinator.pinRichFromStash(context, entry)
                    }
                },
                onCopy = {
                    val ok = StashCoordinator.copyStashEntry(context, entry)
                    if (ok && entry.type != StashEntryType.IMAGE) {
                        onShowMessage(R.string.float_ball_text_copied)
                    }
                },
                onShare = {
                    when (entry.type) {
                        StashEntryType.TEXT -> FloatBallTextPick.shareText(context, entry.text.orEmpty())
                        StashEntryType.IMAGE -> {
                            val bitmap = repo?.loadImage(entry) ?: return@HistoryPhraseEntryCard
                            FloatBallTextPick.shareScreenshot(context, bitmap)
                        }
                        StashEntryType.RICH -> {
                            // 用 exportText 而不是 combinedText：后者是“正文”，语音块不在里面，
                            // 一条只录了音的条目分享出去会是空的。
                            val combined = entry.exportText()
                            if (combined.isNotBlank()) {
                                FloatBallTextPick.shareText(context, combined)
                            } else {
                                val fileName = entry.allImageFileNames().firstOrNull()
                                val bitmap = fileName?.let { repo?.loadBitmapByFileName(it) }
                                    ?: return@HistoryPhraseEntryCard
                                FloatBallTextPick.shareScreenshot(context, bitmap)
                            }
                        }
                    }
                },
                onSend = { StashCoordinator.sendStashEntry(context, entry) },
                // 「星标」在话术里就是置顶：复用 starred 字段，列表里置顶的排在最前。
                onTogglePinTop = { scope.launch { repo?.toggleStar(entry.id) } },
                onDelete = { onDeleteEntry(entry) },
                categoryUi = StashCardCategoryUi(
                    currentCategoryId = entry.categoryId,
                    categories = categories,
                    onMove = { targetCategoryId ->
                        scope.launch {
                            if (repo?.moveToCategory(entry.id, targetCategoryId) == true) {
                                onShowMessage(R.string.stash_category_moved)
                            }
                        }
                    },
                    onManage = { openStashCategoryManagement(context) },
                ),
                onEdit = { onEditEntry(entry) },
            )
        }
        item(key = "stash_list_footer") {
            Spacer(modifier = Modifier.height(HistoryListFooterPadding))
        }
    }
}
