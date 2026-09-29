package com.slideindex.app.overlay.history

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.slideindex.app.R
import com.slideindex.app.clipboard.ClipboardDragShareFallback
import com.slideindex.app.clipboard.ClipboardEntry
import com.slideindex.app.clipboard.ClipboardEntryType
import com.slideindex.app.clipboard.ClipboardThumbnailCache
import com.slideindex.app.clipboard.ClipboardWriter
import com.slideindex.app.clipboard.displayTypeLabelKey
import com.slideindex.app.clipboard.hasImageContent
import com.slideindex.app.clipboard.hasRichPinContent
import com.slideindex.app.clipboard.resolvedContentBlocks
import com.slideindex.app.clipboard.shouldOfferExpand
import com.slideindex.app.overlay.FloatBallStashPanel
import com.slideindex.app.overlay.FloatBallTextPick
import com.slideindex.app.overlay.PickResultFromHistoryCoordinator
import com.slideindex.app.stash.StashAccess
import com.slideindex.app.stash.StashCoordinator
import com.slideindex.app.stash.StashEntry
import com.slideindex.app.stash.StashEntryType
import com.slideindex.app.stash.allImageFileNames
import com.slideindex.app.stash.combinedText
import com.slideindex.app.stash.resolvedContentBlocks
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun HistoryClipboardEntryCard(
    entry: ClipboardEntry,
    expanded: Boolean,
    onExpandedChange: () -> Unit,
    selectedImageIndex: Int,
    onSelectedImageIndexChange: (Int) -> Unit,
    previewWidthPx: Int,
    previewHeightPx: Int,
    onShowMessage: (Int) -> Unit,
    onCopy: () -> Unit,
    onStash: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val hasImageContent = entry.hasImageContent()
    val contentBlocks = remember(entry.id, entry.contentBlocks, entry.text, entry.htmlText, entry.imageFileNames) {
        entry.resolvedContentBlocks()
    }
    val canExpand = remember(entry.id, contentBlocks) { entry.shouldOfferExpand() }
    val (thumbnails, imageLoadFailed) = rememberLoadedThumbnails(
        entryId = entry.id,
        loadKey = listOf(
            entry.imageFileName,
            entry.imageFileNames,
            entry.uri,
            entry.mimeType,
            entry.htmlText,
            previewWidthPx,
            previewHeightPx,
            hasImageContent,
        ),
        enabled = hasImageContent,
        loader = {
            ClipboardThumbnailCache.loadEntryThumbnailsForCard(
                context,
                entry,
                previewWidthPx,
                previewHeightPx,
            )
        },
    )
    val hasImages = thumbnails.isNotEmpty()
    val selectedBitmap = thumbnails.getOrNull(selectedImageIndex)
    val bodyText = entry.text.trim()
    val showBodyText = bodyText.isNotEmpty() && bodyText != entry.uri
    val summaryText = when {
        showBodyText -> bodyText
        !hasImages && !imageLoadFailed -> entry.uri ?: entry.intentUri.orEmpty()
        else -> ""
    }
    val pinLabel = stringResource(R.string.stash_action_pin)
    val shareLabel = stringResource(R.string.float_ball_action_share)
    val saveImageLabel = stringResource(R.string.clipboard_action_save_image)
    val deleteLabel = stringResource(R.string.stash_action_delete)
    val moreLabel = stringResource(R.string.notification_filter_more_menu)
    val onLongPressDrag: () -> Unit = {
        val clipData = ClipboardWriter.buildClipForEntry(context, entry)
        if (clipData == null) {
            onShowMessage(R.string.history_drag_unsupported)
        } else {
            val started = HistoryEntryDragHelper.startDrag(
                view = view,
                clipData = clipData,
                preview = HistoryEntryDragHelper.previewForClipboardEntry(entry, thumbnails),
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

    HistoryEntryCardShell(
        entryId = entry.id,
        createdAtEpochMs = entry.createdAtEpochMs,
        starred = false,
        headerTrailing = {
            IconButton(
                onClick = {
                    PickResultFromHistoryCoordinator.openFromClipboard(
                        context,
                        entry,
                        selectedImageIndex,
                    )
                },
                modifier = Modifier.size(32.dp),
            ) {
                MiuixIcon(
                    imageVector = Icons.Outlined.TextFields,
                    contentDescription = stringResource(R.string.stash_action_open_pick),
                    modifier = Modifier.size(18.dp),
                    tint = MiuixTheme.colorScheme.onBackground,
                )
            }
            Text(
                text = clipboardEntryTypeLabel(entry.displayTypeLabelKey()),
                style = HistoryPanelTypography.meta(),
                color = MiuixTheme.colorScheme.primary,
            )
        },
        content = {
            HistoryExpandableContentSection(
                entryId = entry.id,
                canExpand = canExpand,
                expanded = expanded,
                onExpandedChange = onExpandedChange,
                contentBlocks = contentBlocks,
                imageSource = HistoryImageSource.Clipboard,
                previewWidthPx = previewWidthPx,
                previewHeightPx = previewHeightPx,
                onLongPressDrag = onLongPressDrag,
                collapsedContent = {
                    if (hasImages) {
                        HistoryImagePagerSection(
                            thumbnails = thumbnails,
                            selectedIndex = selectedImageIndex,
                            onSelectedIndexChange = onSelectedImageIndexChange,
                            onLongPressDrag = onLongPressDrag,
                        )
                    } else if (imageLoadFailed) {
                        Text(
                            text = stringResource(R.string.clipboard_image_unavailable),
                            style = HistoryPanelTypography.hint(),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    HistoryCollapsedSummaryText(text = summaryText)
                },
            )
        },
        actions = {
            HistoryCardActionIcon(
                icon = Icons.Default.ContentCopy,
                contentDescription = stringResource(R.string.clipboard_history_float_copy),
                onClick = onCopy,
            )
            HistoryCardActionIcon(
                icon = Icons.Outlined.Archive,
                contentDescription = stringResource(R.string.float_ball_action_stash),
                onClick = onStash,
            )
            Spacer(modifier = Modifier.weight(1f))
            HistoryCardOverflowMenu(
                contentDescription = moreLabel,
                actions = buildList {
                    if (entry.hasRichPinContent() || hasImages || showBodyText) {
                        add(
                            HistoryCardMenuAction(
                                label = pinLabel,
                                icon = Icons.Default.PushPin,
                                onClick = {
                                    when {
                                        entry.hasRichPinContent() -> StashCoordinator.pinRichFromClipboard(context, entry)
                                        hasImages && selectedBitmap != null -> {
                                            StashCoordinator.pinImageToScreen(context, selectedBitmap)
                                        }
                                        showBodyText -> StashCoordinator.pinTextToScreen(context, bodyText)
                                    }
                                },
                            ),
                        )
                    }
                    if (!expanded && hasImages && selectedBitmap != null) {
                        add(
                            HistoryCardMenuAction(
                                label = shareLabel,
                                icon = Icons.Default.Share,
                                onClick = { FloatBallTextPick.shareScreenshot(context, selectedBitmap) },
                            ),
                        )
                        add(
                            HistoryCardMenuAction(
                                label = saveImageLabel,
                                icon = Icons.Outlined.Save,
                                onClick = {
                                    val saved = FloatBallTextPick.saveScreenshot(context, selectedBitmap)
                                    onShowMessage(
                                        if (saved) R.string.float_ball_screenshot_saved else R.string.float_ball_action_failed,
                                    )
                                },
                            ),
                        )
                    } else if (!expanded && showBodyText) {
                        add(
                            HistoryCardMenuAction(
                                label = shareLabel,
                                icon = Icons.Default.Share,
                                onClick = { FloatBallTextPick.shareText(context, bodyText) },
                            ),
                        )
                    }
                    add(
                        HistoryCardMenuAction(
                            label = deleteLabel,
                            icon = Icons.Default.Delete,
                            onClick = onDelete,
                            iconTint = MiuixTheme.colorScheme.error,
                        ),
                    )
                },
            )
        },
    )
}

/** 暂存夹一条内容里的操作按钮边长。 */
private val StashCardButtonSize = 28.dp

/** 左侧缩略图的边长。 */
private val StashCardThumbSize = 64.dp

/**
 * 暂存夹里的一条：左边缩略图（有图才有），右边两行文案 + 一排按钮（复制 / 发送 / 星标 / 更多）。
 * 文案固定最多两行、超出截断，不再展开；没有卡片底色，条与条之间是分隔线。
 */
@Composable
internal fun HistoryStashEntryCard(
    entry: StashEntry,
    onShowMessage: (Int) -> Unit,
    onPin: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onSend: () -> Unit,
    onToggleStar: () -> Unit,
    onDelete: () -> Unit,
    categoryUi: StashCardCategoryUi,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val repo = StashAccess.repository
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
            { StashCardThumb(shownBitmap) }
        } else {
            null
        },
        onLongPress = onLongPressDrag,
    ) {
        HistoryCollapsedSummaryText(text = summaryText, maxLines = 2)
        Row(verticalAlignment = Alignment.CenterVertically) {
            HistoryCardActionIcon(
                icon = Icons.Default.ContentCopy,
                contentDescription = stringResource(R.string.clipboard_history_float_copy),
                onClick = onCopy,
                buttonSize = StashCardButtonSize,
            )
            HistoryCardActionIcon(
                icon = Icons.AutoMirrored.Filled.Send,
                contentDescription = stringResource(R.string.stash_send_action),
                onClick = onSend,
                buttonSize = StashCardButtonSize,
            )
            IconButton(onClick = onToggleStar, modifier = Modifier.size(StashCardButtonSize)) {
                MiuixIcon(
                    imageVector = if (entry.starred) Icons.Default.Star else Icons.Outlined.StarOutline,
                    contentDescription = null,
                    modifier = Modifier.size(StashCardButtonSize - 12.dp),
                    tint = if (entry.starred) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.onBackground
                    },
                )
            }
            HistoryCardOverflowMenu(
                contentDescription = moreLabel,
                buttonSize = StashCardButtonSize,
                actions = buildList {
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

/** 左侧缩略图：固定方块，图还没加载出来时先占位，免得列表跳动。 */
@Composable
private fun StashCardThumb(bitmap: Bitmap?) {
    val imageBitmap = rememberHistoryImageBitmap(bitmap)
    Box(
        modifier = Modifier
            .size(StashCardThumbSize)
            .clip(RoundedCornerShape(8.dp)),
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

@Composable
private fun clipboardEntryTypeLabel(type: ClipboardEntryType): String = when (type) {
    ClipboardEntryType.TEXT -> stringResource(R.string.clipboard_entry_type_text)
    ClipboardEntryType.URI -> stringResource(R.string.clipboard_entry_type_uri)
    ClipboardEntryType.INTENT -> stringResource(R.string.clipboard_entry_type_intent)
    ClipboardEntryType.HTML -> stringResource(R.string.clipboard_entry_type_html)
}
