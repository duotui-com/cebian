@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.slideindex.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.slideindex.app.R
import com.slideindex.app.ui.miuix.MiuixLabeledTextField
import com.slideindex.app.ui.settings.components.LazySettingsItem
import com.slideindex.app.ui.settings.components.SettingsScreenScaffold
import com.slideindex.app.ui.settings.components.settingsLazyTipCard
import com.slideindex.app.ui.viewmodel.StashEditorImage
import com.slideindex.app.ui.viewmodel.StashPhraseEditorState
import top.yukonga.miuix.kmp.basic.Button as MiuixButton
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Photos
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 新增 / 编辑一条话术：一段文字、一张图片，或两者一起（先发文字，再发图片）。 */
@Composable
fun StashPhraseEditorScreen(
    isNew: Boolean,
    state: StashPhraseEditorState,
    /** 条目原有图片的预览；新选的图片直接从 [state] 里取。 */
    existingImage: Bitmap?,
    onBack: () -> Unit,
    onTextChange: (String) -> Unit,
    onPickImage: () -> Unit,
    onRemoveImage: () -> Unit,
    onSave: () -> Unit,
) {
    SettingsScreenScaffold(
        title = stringResource(
            if (isNew) R.string.stash_category_editor_add_title else R.string.stash_category_editor_edit_title,
        ),
        onBack = onBack,
        actions = {
            IconButton(onClick = onSave, enabled = state.canSave) {
                MiuixIcon(
                    imageVector = Icons.Default.Check,
                    contentDescription = stringResource(R.string.stash_category_editor_save),
                )
            }
        },
    ) {
        if (!state.editable) {
            settingsLazyTipCard(
                key = "stash-phrase-not-editable",
                text = stringResource(R.string.stash_category_editor_complex),
            )
        } else {
            LazySettingsItem(key = "stash-phrase-editor") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        insideMargin = PaddingValues(16.dp),
                    ) {
                        SmallTitle(
                            text = stringResource(R.string.stash_category_editor_text_section),
                            insideMargin = PaddingValues(bottom = 8.dp),
                        )
                        MiuixLabeledTextField(
                            value = state.text,
                            onValueChange = onTextChange,
                            label = stringResource(R.string.stash_category_editor_text_label),
                            singleLine = false,
                            minLines = 4,
                            maxLines = 12,
                        )
                    }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        insideMargin = PaddingValues(16.dp),
                    ) {
                        SmallTitle(
                            text = stringResource(R.string.stash_category_editor_image_section),
                            insideMargin = PaddingValues(bottom = 8.dp),
                        )
                        val preview = when (val image = state.image) {
                            is StashEditorImage.Picked -> image.bitmap
                            is StashEditorImage.Existing -> existingImage
                            StashEditorImage.None -> null
                        }
                        if (preview != null) {
                            val imageBitmap = remember(preview) { preview.asImageBitmap() }
                            Image(
                                bitmap = imageBitmap,
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 240.dp)
                                    .clip(RoundedCornerShape(12.dp)),
                                contentScale = ContentScale.Fit,
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = if (preview != null) 12.dp else 0.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            MiuixButton(onClick = onPickImage) {
                                MiuixIcon(
                                    imageVector = MiuixIcons.Photos,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    text = stringResource(
                                        if (state.image is StashEditorImage.None) {
                                            R.string.stash_category_editor_pick_image
                                        } else {
                                            R.string.stash_category_editor_change_image
                                        },
                                    ),
                                    modifier = Modifier.padding(start = 4.dp),
                                    style = MiuixTheme.textStyles.footnote1,
                                    maxLines = 1,
                                )
                            }
                            if (state.image !is StashEditorImage.None) {
                                MiuixButton(onClick = onRemoveImage) {
                                    MiuixIcon(
                                        imageVector = MiuixIcons.Delete,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        text = stringResource(R.string.stash_category_editor_remove_image),
                                        modifier = Modifier.padding(start = 4.dp),
                                        style = MiuixTheme.textStyles.footnote1,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                        Text(
                            text = stringResource(R.string.stash_category_editor_order_hint),
                            modifier = Modifier.padding(top = 12.dp),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }
    }
}
