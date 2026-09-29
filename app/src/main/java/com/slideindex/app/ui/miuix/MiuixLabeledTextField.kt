package com.slideindex.app.ui.miuix

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
import androidx.compose.ui.unit.DpSize
import kotlinx.coroutines.flow.distinctUntilChanged
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextFieldDefaults

/**
 * 与外部 [value] 双向同步的 Miuix 表单输入框，用于替代 Material [androidx.compose.material3.OutlinedTextField]。
 */
@Composable
fun MiuixLabeledTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    insideMargin: DpSize = TextFieldDefaults.InsideMargin,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 6,
    /** 不为空时限制最大字符数（由输入变换保证，输入框里不会出现超长文字）。 */
    maxLength: Int? = null,
) {
    val state = rememberTextFieldState(initialText = value)
    // LaunchedEffect(state) 只在首次组合时启动，里面不能直接用 value / onValueChange，
    // 否则拿到的永远是首次组合时的旧值（用户把输入改回那个旧值时会被当成没变化而丢掉）。
    val latestValue by rememberUpdatedState(value)
    val latestOnValueChange by rememberUpdatedState(onValueChange)

    LaunchedEffect(value) {
        val current = state.text.toString()
        if (current != value) {
            state.edit {
                replace(0, length, value)
            }
        }
    }

    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }
            .distinctUntilChanged()
            .collect { text ->
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
        insideMargin = insideMargin,
        inputTransformation = maxLength?.let { InputTransformation.maxLength(it) },
    )
}
