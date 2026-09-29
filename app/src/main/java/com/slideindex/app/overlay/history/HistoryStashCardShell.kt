package com.slideindex.app.overlay.history

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.slideindex.app.ui.miuix.CardSegment
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 暂存夹卡片的紧凑外壳：一列竖排的操作按钮 + 内容。
 *
 * 话术类内容一条只需要两行文案，原来「头部 / 内容 / 分割线 / 操作行」的卡片太高，同屏放不下几条。
 * 这里把操作按钮竖着排在卡片的一侧（[actionsOnStart] 为 true 在左，否则在右；调用方按面板停靠的边取
 * 「抽屉外侧」），内容占剩下的宽度。
 */
@Composable
internal fun HistoryStashCardShell(
    starred: Boolean,
    actionsOnStart: Boolean,
    actions: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val cardShape = RoundedCornerShape(16.dp)
    CardSegment(
        isFirst = true,
        isLast = true,
        color = HistoryPanelColors.cardBackground(starred),
        contentColor = scheme.onSurfaceContainer,
        cornerRadius = 16.dp,
        outerHorizontalPadding = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (starred) {
                    Modifier.border(
                        width = 1.dp,
                        color = scheme.primary.copy(alpha = 0.45f),
                        shape = cardShape,
                    )
                } else {
                    Modifier
                },
            ),
        insidePadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (actionsOnStart) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, content = actions)
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                content = content,
            )
            if (!actionsOnStart) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, content = actions)
            }
        }
    }
}
