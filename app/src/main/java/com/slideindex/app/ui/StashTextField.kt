package com.slideindex.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.distinctUntilChanged
import top.yukonga.miuix.kmp.basic.TextField

/**
 * 分类与话术管理页用的 Miuix 输入框，与外部 [value] 双向同步；[maxLength] 不为空时限制最大字符数。
 *
 * 不直接用 `MiuixLabeledTextField`：它在 `LaunchedEffect(state)` 里捕获的是**首次组合时**的
 * `value` / `onValueChange`，用户把输入改回“首次组合时的值”（最常见的就是清空回到最初的空串）时，
 * 这次修改会被当成没变化而丢掉，外部状态还停在上一个值。`MiuixSearchField` 已经用
 * `rememberUpdatedState` 修过同一个问题，这里照它的写法。
 *
 * 共享组件有几十处调用，功能 PR 里不去动它；要修的话按同样的写法改 `MiuixLabeledTextField` 即可。
 */
@Composable
internal fun StashTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 6,
    maxLength: Int? = null,
) {
    val state = rememberTextFieldState(initialText = value)
    val latestValue by rememberUpdatedState(value)
    val latestOnValueChange by rememberUpdatedState(onValueChange)

    LaunchedEffect(value) {
        if (state.text.toString() != value) {
            state.edit {
                replace(0, length, value)
            }
        }
    }

    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }
            .distinctUntilChanged()
            .collect { text ->
                // 必须和最新的 value 比：拿首次组合时的旧值比，会把“改回旧值”的输入判成没变化。
                if (text != latestValue) {
                    latestOnValueChange(text)
                }
            }
    }

    val lineLimits = if (singleLine) {
        TextFieldLineLimits.SingleLine
    } else {
        TextFieldLineLimits.MultiLine(
            minHeightInLines = minLines.coerceAtLeast(1),
            maxHeightInLines = maxLines.coerceAtLeast(minLines),
        )
    }

    TextField(
        state = state,
        modifier = modifier.fillMaxWidth(),
        label = label,
        useLabelAsPlaceholder = true,
        lineLimits = lineLimits,
        inputTransformation = maxLength?.let { InputTransformation.maxLength(it) },
    )
}
