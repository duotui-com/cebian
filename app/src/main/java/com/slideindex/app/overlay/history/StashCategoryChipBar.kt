package com.slideindex.app.overlay.history

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.slideindex.app.MainActivity
import com.slideindex.app.R
import com.slideindex.app.overlay.FloatBallStashPanel
import com.slideindex.app.stash.StashCategory
import com.slideindex.app.stash.StashCategoryFilter
import com.slideindex.app.stash.stashFilterOrder
import com.slideindex.app.stash.adjacentTo
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text

/** 暂存夹页底部分类筛选条的固定高度；FAB 要躲到它上面去。 */
internal val StashCategoryBarHeight = 44.dp

/**
 * 暂存夹页底部的分类筛选条：全部 / 各分类 / 未分类，末尾是打开管理页的「管理」。
 *
 * 固定在抽屉底部、列表的下面（不是放进列表），这样列表怎么滚它都在，
 * 分类下没有条目时列表为空，筛选条也一直在。
 * 胶囊复用头部标签行的 [HistoryChip]，两行筛选长得一致；底下不另画底色，透出面板自己的玻璃底。
 */
@Composable
internal fun StashCategoryChipBar(
    categories: List<StashCategory>,
    selected: StashCategoryFilter,
    onSelect: (StashCategoryFilter) -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = historyTheme()
    val listState = rememberLazyListState()
    val order = remember(categories) { stashFilterOrder(categories) }
    // 用滑动切换分类时，被选中的那一项可能在筛选条的可视范围外，把它滚进来。
    LaunchedEffect(selected, order) {
        val index = order.indexOf(selected)
        if (index >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            listState.animateScrollToItem(index)
        }
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(StashCategoryBarHeight),
    ) {
        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            item(key = "filter_all") {
                HistoryChip(
                    label = stringResource(R.string.stash_category_all),
                    dotColor = null,
                    selected = selected == StashCategoryFilter.All,
                    onClick = { onSelect(StashCategoryFilter.All) },
                )
            }
            items(categories, key = { it.id }) { category ->
                val filter = StashCategoryFilter.Category(category.id)
                HistoryChip(
                    label = category.name,
                    dotColor = null,
                    selected = selected == filter,
                    onClick = { onSelect(filter) },
                    modifier = Modifier.widthIn(max = 140.dp),
                )
            }
            item(key = "filter_uncategorized") {
                HistoryChip(
                    label = stringResource(R.string.stash_category_uncategorized),
                    dotColor = null,
                    selected = selected == StashCategoryFilter.Uncategorized,
                    onClick = { onSelect(StashCategoryFilter.Uncategorized) },
                )
            }
            item(key = "manage") {
                Text(
                    text = stringResource(R.string.stash_category_manage),
                    style = HistoryPanelTypography.hint(),
                    color = theme.accentSolid,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(15.dp))
                        .clickable(onClick = onManage)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
        // 底部筛选条与上面列表之间的分隔线。
        HorizontalDivider(
            modifier = Modifier.align(Alignment.TopCenter),
            color = theme.line,
        )
    }
}

/**
 * 卡片上和分类相关的信息与操作，由暂存夹页统一组装后交给每张卡片。
 *
 * 标 [Immutable]：否则 Compose 把它判成不稳定（含 List），每次重组新建的实例都“不相等”，
 * 展开或切换任一张卡片时所有可见卡片都会跟着重组。
 */
@Immutable
internal data class StashCardCategoryUi(
    val currentCategoryId: String?,
    val categories: List<StashCategory>,
    /** 移入某个分类；null 表示移出分类。 */
    val onMove: (String?) -> Unit,
    val onManage: () -> Unit,
)

/** 「移入分类」的选择菜单：锚在它所在的父布局上。 */
@Composable
internal fun StashCategoryPickerMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    ui: StashCardCategoryUi,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (ui.currentCategoryId != null) {
            DropdownMenuItem(
                text = {
                    Text(stringResource(R.string.stash_category_move_out), style = HistoryPanelTypography.content())
                },
                onClick = {
                    onDismiss()
                    ui.onMove(null)
                },
            )
        }
        ui.categories.filter { it.id != ui.currentCategoryId }.forEach { category ->
            DropdownMenuItem(
                text = { Text(category.name, style = HistoryPanelTypography.content()) },
                onClick = {
                    onDismiss()
                    ui.onMove(category.id)
                },
            )
        }
        DropdownMenuItem(
            text = {
                Text(stringResource(R.string.stash_category_move_manage), style = HistoryPanelTypography.content())
            },
            onClick = {
                onDismiss()
                ui.onManage()
            },
        )
    }
}

/** 收起面板，并在应用里打开「分类与话术管理」页。 */
internal fun openStashCategoryManagement(context: Context) {
    FloatBallStashPanel.dismiss()
    context.startActivity(
        Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_NAV_ROUTE, MainActivity.NAV_ROUTE_EXTENSION_STASH_CATEGORIES)
        },
    )
}

/**
 * 在列表上左右滑动切换分类（顺序同底部筛选条：全部、各分类、未分类），不循环。
 * 滑动距离超过阈值才切，避免斜着划列表时误触。
 */
internal fun Modifier.stashCategorySwipe(
    categories: List<StashCategory>,
    selected: StashCategoryFilter,
    onSelect: (StashCategoryFilter) -> Unit,
): Modifier = pointerInput(categories, selected) {
    val threshold = 64.dp.toPx()
    var total = 0f
    detectHorizontalDragGestures(
        onDragStart = { total = 0f },
        onDragCancel = { total = 0f },
        onDragEnd = {
            val step = when {
                total <= -threshold -> 1
                total >= threshold -> -1
                else -> 0
            }
            if (step != 0) {
                stashFilterOrder(categories).adjacentTo(selected, step)?.let(onSelect)
            }
            total = 0f
        },
        onHorizontalDrag = { _, amount -> total += amount },
    )
}
