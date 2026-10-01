package com.slideindex.app.overlay.history

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 暂存夹一条内容四周的内边距；想调整留白改这一个值。 */
internal val StashItemPadding: Dp = 3.dp

/**
 * 暂存夹列表里的一条：没有卡片底色和圆角，条与条之间用分隔线隔开。
 *
 * 左边可选的缩略图 [leading]，右边是 [body]（两行文案 + 一排操作按钮）。点按整条触发 [onClick]（发送），长按拖出内容。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HistoryStashItemShell(
    leading: (@Composable () -> Unit)?,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    body: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongPress)
                .padding(StashItemPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            leading?.invoke()
            Column(modifier = Modifier.weight(1f), content = body)
        }
        HorizontalDivider(color = MiuixTheme.colorScheme.dividerLine.copy(alpha = 0.5f))
    }
}
