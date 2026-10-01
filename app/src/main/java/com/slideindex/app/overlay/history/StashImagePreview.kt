package com.slideindex.app.overlay.history

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import top.yukonga.miuix.kmp.basic.Text

/**
 * 暂存夹里一条话术的大图预览：盖在收纳面板上，底部并排「关闭」和「发送」。
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
    // 预览用原图：直接解码存盘的那份文件，不走卡片缩略图的缓存和缩放。
    val bitmap by produceState<Bitmap?>(null, entry.id) {
        value = withContext(Dispatchers.IO) {
            val fileName = when (entry.type) {
                StashEntryType.IMAGE -> entry.imageFileName
                // 图文条目：卡片上显示的那张（第一张）。
                StashEntryType.RICH -> entry.allImageFileNames().firstOrNull()
                StashEntryType.TEXT -> null
            }
            repo?.loadBitmapByFileName(fileName)
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
                    .padding(horizontal = 12.dp, vertical = 80.dp),
                contentScale = ContentScale.Fit,
            )
        }
        // 关闭和发送并排放在底部，单手好点。
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = onClose, modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.stash_send_preview_close))
            }
            Button(onClick = onSend, modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.stash_send_preview_send))
            }
        }
    }
}

