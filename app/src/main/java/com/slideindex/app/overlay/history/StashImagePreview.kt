package com.slideindex.app.overlay.history

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.slideindex.app.R
import com.slideindex.app.stash.StashEntry
import com.slideindex.app.stash.StashEntryType
import com.slideindex.app.stash.StashRepository
import com.slideindex.app.stash.allImageFileNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close

/**
 * 暂存夹里一条话术的大图预览：盖在收纳面板上，右上角关闭，底部「发送」。
 *
 * 点「发送」发出的是**这一整条话术**（文字和图片都按顺序发），不是单独这张图。
 * 不用 `Dialog`：面板是悬浮窗里的 Compose，没有 Activity 的窗口令牌，开不了系统弹窗。
 */
@Composable
internal fun StashImagePreview(
    entry: StashEntry,
    repo: StashRepository?,
    onClose: () -> Unit,
    onSend: () -> Unit,
) {
    val bitmap by produceState<Bitmap?>(null, entry.id) {
        value = withContext(Dispatchers.IO) {
            when (entry.type) {
                StashEntryType.IMAGE -> repo?.loadImage(entry)
                // 图文条目：预览卡片上显示的那张（第一张）。
                StashEntryType.RICH -> entry.allImageFileNames().firstOrNull()?.let { fileName ->
                    repo?.loadThumbnailByFileNameForCard(entry.id, fileName, PreviewMaxSidePx, PreviewMaxSidePx)
                }
                StashEntryType.TEXT -> null
            }
        }
    }
    val imageBitmap = rememberHistoryImageBitmap(bitmap)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.82f))
            // 点空白处也关闭；同时挡住下面列表的点击。
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
    ) {
        if (imageBitmap != null) {
            Image(
                bitmap = imageBitmap,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 72.dp),
                contentScale = ContentScale.Fit,
            )
        }
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .size(40.dp),
        ) {
            MiuixIcon(
                imageVector = MiuixIcons.Close,
                contentDescription = stringResource(R.string.stash_send_preview_close),
                tint = Color.White,
            )
        }
        Button(
            onClick = onSend,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 20.dp),
        ) {
            Text(text = stringResource(R.string.stash_send_preview_send))
        }
    }
}

/** 预览图解码的最大边长（像素）：够把图铺满面板，又不会为一张大图占太多内存。 */
private const val PreviewMaxSidePx = 1600
