@file:OptIn(ExperimentalFoundationApi::class)

package com.slideindex.app.overlay.history

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.style.TextOverflow
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

/** 暂存夹卡片的操作按钮边长；四个竖排，卡片高度就由它们决定。 */
private val StashCardButtonSize = 28.dp

/**
 * 暂存夹卡片：操作按钮（复制 / 分享 / 发送 / 菜单）竖排在 [actionsOnStart] 指定的一侧，
 * 内容区文案固定最多两行、超出截断，同屏能放下更多条。不再展开，想看全文去管理页。
 */
@Composable
internal fun HistoryStashEntryCard(
    entry: StashEntry,
    actionsOnStart: Boolean,
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
    val thumbWidthPx = remember(density) { with(density) { 160.dp.roundToPx() } }
    val thumbHeightPx = remember(density) { with(density) { StashCardThumbHeight.roundToPx() } }
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
        loadKey = listOf(thumbWidthPx, thumbHeightPx, entry.type),
        enabled = entry.type == StashEntryType.IMAGE,
        loader = { repo?.loadImageThumbnailForCard(entry, thumbWidthPx, thumbHeightPx) },
    )
    val (richThumbnails, _) = rememberLoadedThumbnails(
        entryId = entry.id,
        loadKey = listOf(richImageFileNames, thumbWidthPx, thumbHeightPx, entry.type),
        enabled = entry.type == StashEntryType.RICH && richImageFileNames.isNotEmpty(),
        loader = {
            repo?.loadEntryThumbnailsForCard(entry, thumbWidthPx, thumbHeightPx).orEmpty()
        },
    )
    // 分享 / 保存图片针对卡片上看得到的那张图：图片条目是它自己，图文条目是第一张。
    val shownBitmap = when (entry.type) {
        StashEntryType.IMAGE -> singleThumb
        StashEntryType.RICH -> richThumbnails.firstOrNull()
        else -> null
    }
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

    HistoryStashCardShell(
        starred = entry.starred,
        actionsOnStart = actionsOnStart,
        actions = {
            HistoryCardActionIcon(
                icon = Icons.Default.ContentCopy,
                contentDescription = stringResource(R.string.clipboard_history_float_copy),
                onClick = onCopy,
                buttonSize = StashCardButtonSize,
            )
            HistoryCardActionIcon(
                icon = Icons.Default.Share,
                contentDescription = stringResource(R.string.float_ball_action_share),
                onClick = {
                    when {
                        entry.type == StashEntryType.RICH && shownBitmap != null ->
                            FloatBallTextPick.shareScreenshot(context, shownBitmap)
                        entry.type == StashEntryType.RICH && summaryText.isNotBlank() ->
                            FloatBallTextPick.shareText(context, summaryText)
                        else -> onShare()
                    }
                },
                buttonSize = StashCardButtonSize,
            )
            HistoryCardActionIcon(
                icon = Icons.AutoMirrored.Filled.Send,
                contentDescription = stringResource(R.string.stash_send_action),
                onClick = onSend,
                buttonSize = StashCardButtonSize,
            )
            HistoryCardOverflowMenu(
                contentDescription = moreLabel,
                buttonSize = StashCardButtonSize,
                actions = buildList {
                    add(
                        HistoryCardMenuAction(
                            label = pinLabel,
                            icon = Icons.Default.PushPin,
                            onClick = onPin,
                        ),
                    )
                    add(
                        HistoryCardMenuAction(
                            label = pickLabel,
                            icon = Icons.Outlined.TextFields,
                            onClick = {
                                PickResultFromHistoryCoordinator.openFromStash(context, entry, 0)
                            },
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
        },
        content = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = formatHistoryRelativeTime(entry.createdAtEpochMs),
                        style = HistoryPanelTypography.meta(),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                    )
                    categoryUi.categoryName?.let { categoryName ->
                        Text(
                            text = categoryName,
                            style = HistoryPanelTypography.meta(),
                            color = MiuixTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                }
                IconButton(onClick = onToggleStar, modifier = Modifier.size(24.dp)) {
                    MiuixIcon(
                        imageVector = if (entry.starred) Icons.Default.Star else Icons.Outlined.StarOutline,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (entry.starred) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MiuixTheme.colorScheme.onBackground
                        },
                    )
                }
            }
            // 点按不做任何事，只留长按拖出；文案固定两行，超出截断。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(onClick = {}, onLongClick = onLongPressDrag),
            ) {
                when (entry.type) {
                    StashEntryType.TEXT -> HistoryCollapsedSummaryText(text = summaryText, maxLines = 2)
                    StashEntryType.IMAGE -> StashCardThumb(singleThumb, fillWidth = true)
                    StashEntryType.RICH -> Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        richThumbnails.firstOrNull()?.let { StashCardThumb(it, fillWidth = false) }
                        HistoryCollapsedSummaryText(text = summaryText, maxLines = 2)
                    }
                }
            }
        },
    )
}

private val StashCardThumbHeight = 56.dp

/** 卡片里的小缩略图：图片条目铺满内容宽度，图文条目是文字前面的方块。 */
@Composable
private fun StashCardThumb(bitmap: Bitmap?, fillWidth: Boolean) {
    val imageBitmap = rememberHistoryImageBitmap(bitmap) ?: return
    Image(
        bitmap = imageBitmap,
        contentDescription = null,
        modifier = (if (fillWidth) Modifier.fillMaxWidth() else Modifier.size(StashCardThumbHeight))
            .height(StashCardThumbHeight)
            .clip(RoundedCornerShape(8.dp)),
        contentScale = ContentScale.Crop,
    )
}

@Composable
private fun clipboardEntryTypeLabel(type: ClipboardEntryType): String = when (type) {
    ClipboardEntryType.TEXT -> stringResource(R.string.clipboard_entry_type_text)
    ClipboardEntryType.URI -> stringResource(R.string.clipboard_entry_type_uri)
    ClipboardEntryType.INTENT -> stringResource(R.string.clipboard_entry_type_intent)
    ClipboardEntryType.HTML -> stringResource(R.string.clipboard_entry_type_html)
}
