@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.slideindex.app.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.slideindex.app.R
import com.slideindex.app.stash.StashCategory
import com.slideindex.app.stash.StashCategoryRepository
import com.slideindex.app.ui.miuix.MiuixConfirmDialog
import com.slideindex.app.ui.miuix.MiuixFormDialog
import com.slideindex.app.ui.miuix.MiuixLabeledTextField
import com.slideindex.app.ui.miuix.groupedCardItems
import com.slideindex.app.ui.miuix.miuixGroupedCardItem
import com.slideindex.app.ui.settings.components.LazySettingsItem
import com.slideindex.app.ui.settings.components.SettingLinkRow
import com.slideindex.app.ui.settings.components.SettingNavigationRow
import com.slideindex.app.ui.settings.components.SettingSwitchRow
import com.slideindex.app.ui.settings.components.SettingsScreenScaffold
import com.slideindex.app.ui.settings.components.SettingsVerticalReorderList
import com.slideindex.app.ui.settings.components.settingsCardScopeItem
import com.slideindex.app.ui.settings.components.settingsLazyHint
import com.slideindex.app.ui.settings.components.settingsLazySmallTitle
import com.slideindex.app.ui.settings.components.settingsLazyTipCard
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.AddFolder
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Edit

/** 新增（[categoryId] 为 null）或重命名分类的输入框状态。 */
private data class CategoryNameDialogState(val categoryId: String?, val initialName: String)

/** 「分类与话术管理」：一键发送的设置 + 分类列表（长按拖动排序）。 */
@Composable
fun StashCategoriesScreen(
    categories: List<StashCategory>,
    entryCounts: Map<String, Int>,
    collapsePanelAfterSend: Boolean,
    accessibilityGranted: Boolean,
    onBack: () -> Unit,
    onOpenCategory: (String) -> Unit,
    onAddCategory: (String) -> Unit,
    onRenameCategory: (String, String) -> Unit,
    onDeleteCategory: (String) -> Unit,
    onReorderCategories: (List<String>) -> Unit,
    onCollapsePanelAfterSendChange: (Boolean) -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
) {
    var nameDialog by remember { mutableStateOf<CategoryNameDialogState?>(null) }
    var deleting by remember { mutableStateOf<StashCategory?>(null) }

    // LazyListScope 顶层不能调用 @Composable，文案先在外面取好。
    val sendSectionTitle = stringResource(R.string.stash_category_send_section)
    val wechatEnvHint = stringResource(R.string.stash_category_wechat_env_hint)
    val categoriesSectionTitle = stringResource(R.string.stash_category_section)
    val categoriesEmptyHint = stringResource(R.string.stash_category_empty)

    SettingsScreenScaffold(
        title = stringResource(R.string.stash_category_settings_title),
        subtitle = stringResource(R.string.stash_category_settings_subtitle),
        onBack = onBack,
    ) {
        settingsLazySmallTitle(
            key = "stash-send-section",
            title = sendSectionTitle,
        )
        groupedCardItems(
            keyPrefix = "stash-send",
            items = listOf(
                settingsCardScopeItem("accessibility") {
                    SettingLinkRow(
                        title = stringResource(R.string.stash_category_accessibility_title),
                        subtitle = stringResource(
                            if (accessibilityGranted) {
                                R.string.stash_category_accessibility_on
                            } else {
                                R.string.stash_category_accessibility_off
                            },
                        ),
                        onClick = onOpenAccessibilitySettings,
                    )
                },
                settingsCardScopeItem("collapse-panel") {
                    SettingSwitchRow(
                        title = stringResource(R.string.stash_category_collapse_title),
                        subtitle = stringResource(R.string.stash_category_collapse_desc),
                        checked = collapsePanelAfterSend,
                        enabled = true,
                        onCheckedChange = onCollapsePanelAfterSendChange,
                    )
                },
            ),
        )
        settingsLazyTipCard(
            key = "stash-wechat-env-hint",
            text = wechatEnvHint,
        )

        settingsLazySmallTitle(
            key = "stash-categories-section",
            title = categoriesSectionTitle,
        )
        groupedCardItems(
            keyPrefix = "stash-category-add",
            items = listOf(
                settingsCardScopeItem("add-category") {
                    SettingNavigationRow(
                        icon = { label ->
                            Icon(
                                MiuixIcons.AddFolder,
                                contentDescription = label,
                                modifier = Modifier.size(24.dp),
                            )
                        },
                        title = stringResource(R.string.stash_category_add),
                        subtitle = stringResource(R.string.stash_category_add_desc),
                        onClick = { nameDialog = CategoryNameDialogState(categoryId = null, initialName = "") },
                    )
                },
            ),
        )
        if (categories.isEmpty()) {
            settingsLazyHint(
                key = "stash-categories-empty",
                text = categoriesEmptyHint,
            )
        } else {
            LazySettingsItem(key = "stash-category-list") {
                SettingsVerticalReorderList(
                    items = categories,
                    key = { it.id },
                    onReorder = { ordered -> onReorderCategories(ordered.map { it.id }) },
                ) { category, _, segmentIndex, segmentCount, dragModifier ->
                    StashCategoryRow(
                        category = category,
                        entryCount = entryCounts[category.id] ?: 0,
                        segmentIndex = segmentIndex,
                        segmentCount = segmentCount,
                        onClick = { onOpenCategory(category.id) },
                        onRename = {
                            nameDialog = CategoryNameDialogState(category.id, category.name)
                        },
                        onDelete = { deleting = category },
                        modifier = dragModifier,
                    )
                }
            }
        }
    }

    nameDialog?.let { dialog ->
        CategoryNameDialog(
            state = dialog,
            onDismiss = { nameDialog = null },
            onConfirm = { name ->
                if (dialog.categoryId == null) {
                    onAddCategory(name)
                } else {
                    onRenameCategory(dialog.categoryId, name)
                }
            },
        )
    }

    val categoryToDelete = deleting
    MiuixConfirmDialog(
        show = categoryToDelete != null,
        onDismissRequest = { deleting = null },
        title = stringResource(R.string.stash_category_delete_title),
        message = categoryToDelete?.let {
            stringResource(
                R.string.stash_category_delete_message,
                it.name,
                entryCounts[it.id] ?: 0,
            )
        },
        onConfirm = { categoryToDelete?.let { onDeleteCategory(it.id) } },
    )
}

@Composable
private fun StashCategoryRow(
    category: StashCategory,
    entryCount: Int,
    segmentIndex: Int,
    segmentCount: Int,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BasicComponent(
        modifier = modifier.miuixGroupedCardItem(segmentIndex, segmentCount),
        title = category.name,
        summary = stringResource(R.string.stash_category_entry_count, entryCount),
        onClick = onClick,
        endActions = {
            IconButton(onClick = onRename) {
                MiuixIcon(
                    imageVector = MiuixIcons.Edit,
                    contentDescription = stringResource(R.string.stash_category_rename),
                )
            }
            IconButton(onClick = onDelete) {
                MiuixIcon(
                    imageVector = MiuixIcons.Delete,
                    contentDescription = stringResource(R.string.stash_category_delete),
                )
            }
        },
    )
}

@Composable
private fun CategoryNameDialog(
    state: CategoryNameDialogState,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var input by remember(state) { mutableStateOf(state.initialName) }
    MiuixFormDialog(
        show = true,
        onDismissRequest = onDismiss,
        title = stringResource(
            if (state.categoryId == null) {
                R.string.stash_category_add_title
            } else {
                R.string.stash_category_rename_title
            },
        ),
        confirmEnabled = input.trim().isNotEmpty(),
        onConfirm = { onConfirm(input.trim()) },
    ) {
        MiuixLabeledTextField(
            value = input,
            onValueChange = { input = it },
            label = stringResource(R.string.stash_category_name_hint),
            maxLength = StashCategoryRepository.MAX_NAME_LENGTH,
        )
    }
}
