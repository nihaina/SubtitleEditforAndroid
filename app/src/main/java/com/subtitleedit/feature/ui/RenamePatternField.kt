package com.subtitleedit.feature.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import com.subtitleedit.util.BatchRename
import com.subtitleedit.R

@Composable
internal fun RenamePatternField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val tokenColor = if (MaterialTheme.colorScheme.surface.luminance() < 0.2f) {
        Color(0xFFA9B4FF)
    } else {
        Color(0xFF4F63ED)
    }
    val transformation = remember(tokenColor) { RenamePatternTransformation(tokenColor) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().onFocusChanged { onFocusChanged(it.isFocused) },
        label = { Text(stringResource(R.string.batch_rename_file_name)) },
        enabled = enabled,
        singleLine = true,
        visualTransformation = transformation,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false)
    )
}

/** Each placeholder occupies one editable character, so IME deletion always removes it whole. */
internal class RenamePatternTransformation(private val tokenColor: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val originalOffsets = IntArray(text.length + 1)
        val transformedOffsets = mutableListOf(0)
        val rendered = buildAnnotatedString {
            text.text.forEachIndexed { index, character ->
                val label = when (character.toString()) {
                    BatchRename.FILE_NAME_TOKEN -> "文件名"
                    BatchRename.NUMBER_TOKEN -> "序号"
                    else -> null
                }
                val displayed = label ?: character.toString()
                if (label != null) {
                    withStyle(SpanStyle(color = tokenColor, fontWeight = FontWeight.Medium)) {
                        append(displayed)
                    }
                } else {
                    append(displayed)
                }
                originalOffsets[index + 1] = length
                // A tap inside a label snaps to its nearest editable boundary.
                for (offset in 1..displayed.length) {
                    transformedOffsets += index + if (offset * 2 >= displayed.length) 1 else 0
                }
            }
        }
        return TransformedText(rendered, object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int =
                originalOffsets[offset.coerceIn(originalOffsets.indices)]

            override fun transformedToOriginal(offset: Int): Int =
                transformedOffsets[offset.coerceIn(transformedOffsets.indices)]
        })
    }
}

/** Insert at the active field selection; an unfocused name field receives an append. */
internal fun insertRenameToken(value: TextFieldValue, token: String, useSelection: Boolean): TextFieldValue {
    val start = if (useSelection) value.selection.min else value.text.length
    val end = if (useSelection) value.selection.max else value.text.length
    return TextFieldValue(
        text = value.text.replaceRange(start, end, token),
        selection = TextRange(start + token.length)
    )
}
