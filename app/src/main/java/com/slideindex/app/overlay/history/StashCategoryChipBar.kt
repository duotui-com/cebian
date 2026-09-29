package com.slideindex.app.overlay.history

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slideindex.app.MainActivity
import com.slideindex.app.R
import com.slideindex.app.overlay.FloatBallStashPanel
import com.slideindex.app.settings.TopAppBarBlurStyle
import com.slideindex.app.stash.StashCategory
import com.slideindex.app.stash.StashCategoryFilter
import com.slideindex.app.ui.miuix.MiuixBlurredTopBar
import com.slideindex.app.ui.miuix.miuixAppBarColor
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 暂存夹页顶部分类筛选条的固定高度；列表要在自己的顶部让出这块空间。 */
internal val StashCategoryBarHeight = 44.dp

/**
 * 暂存夹页顶部的分类筛选条：全部 / 未分类 / 各分类，末尾是打开管理页的「管理」。
 *
 * 叠在页面顶部（顶栏正下方）而不是放进列表，这样切换分类不必滚回列表顶部，
 * 分类下没有条目时列表为空，筛选条也一直在。
 */
@Composable
internal fun StashCategoryChipBar(
    categories: List<StashCategory>,
    selected: StashCategoryFilter,
    panelBlurActive: Boolean,
    backdrop: LayerBackdrop?,
    onSelect: (StashCategoryFilter) -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val blurActive = backdrop != null
    MiuixBlurredTopBar(
        backdrop = backdrop,
        enabled = blurActive,
        blurStyle = TopAppBarBlurStyle.GAUSSIAN,
        modifier = modifier
            .fillMaxWidth()
            .height(StashCategoryBarHeight),
    ) {
        LazyRow(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    if (blurActive) {
                        backdrop.miuixAppBarColor()
                    } else {
                        HistoryPanelColors.panelChrome(panelBlurActive)
                    },
                ),
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            item(key = "filter_all") {
                StashCategoryChip(
                    label = stringResource(R.string.stash_category_all),
                    selected = selected == StashCategoryFilter.All,
                    onClick = { onSelect(StashCategoryFilter.All) },
                )
            }
            item(key = "filter_uncategorized") {
                StashCategoryChip(
                    label = stringResource(R.string.stash_category_uncategorized),
                    selected = selected == StashCategoryFilter.Uncategorized,
                    onClick = { onSelect(StashCategoryFilter.Uncategorized) },
                )
            }
            items(categories, key = { it.id }) { category ->
                val filter = StashCategoryFilter.Category(category.id)
                StashCategoryChip(
                    label = category.name,
                    selected = selected == filter,
                    onClick = { onSelect(filter) },
                )
            }
            item(key = "manage") {
                Text(
                    text = stringResource(R.string.stash_category_manage),
                    style = HistoryPanelTypography.hint(),
                    color = MiuixTheme.colorScheme.primary,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(15.dp))
                        .clickable(onClick = onManage)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun StashCategoryChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    Box(
        modifier = Modifier
            .height(30.dp)
            .widthIn(max = 140.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(if (selected) scheme.primary.copy(alpha = 0.16f) else scheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = HistoryPanelTypography.hint(),
            color = if (selected) scheme.primary else scheme.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
    /** 条目当前所属分类的名字；没有分类为 null。 */
    val categoryName: String?,
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
