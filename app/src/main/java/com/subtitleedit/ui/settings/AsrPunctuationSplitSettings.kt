package com.subtitleedit.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.util.AsrPunctuationOptions
import com.subtitleedit.ui.components.SettingsSwitchRow

@Composable
internal fun AsrPunctuationSplitSettings(
    checked: Boolean,
    selectedPunctuation: List<String>,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onPunctuationChange: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dialogVisible by rememberSaveable { mutableStateOf(false) }
    var draft by remember { mutableStateOf(selectedPunctuation.toSet()) }

    Column(modifier = modifier) {
        SettingsSwitchRow(
            title = stringResource(R.string.speech_merge_punctuation_split),
            description = stringResource(R.string.speech_merge_punctuation_split_hint),
            checked = checked,
            enabled = enabled,
            opacity = if (enabled) 1f else 0.5f,
            onCheckedChange = onCheckedChange,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            OutlinedButton(
                enabled = enabled && checked,
                onClick = {
                    draft = selectedPunctuation.toSet()
                    dialogVisible = true
                },
            ) {
                Text(stringResource(R.string.speech_merge_set_punctuation))
            }
        }
    }

    if (dialogVisible) {
        AlertDialog(
            onDismissRequest = { dialogVisible = false },
            title = { Text(stringResource(R.string.speech_merge_set_punctuation)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.speech_merge_select_punctuation_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 44.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .heightIn(max = 320.dp),
                        contentPadding = PaddingValues(2.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(AsrPunctuationOptions.choices, key = { it }) { punctuation ->
                            val selected = punctuation in draft
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .toggleable(
                                        value = selected,
                                        role = Role.Checkbox,
                                    ) {
                                        draft = if (selected) draft - punctuation else draft + punctuation
                                    },
                                shape = MaterialTheme.shapes.medium,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHighest
                                },
                                contentColor = if (selected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                border = BorderStroke(
                                    1.dp,
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant,
                                ),
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(punctuation, style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onPunctuationChange(AsrPunctuationOptions.choices.filter { it in draft })
                        dialogVisible = false
                    },
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { draft = AsrPunctuationOptions.defaults }) {
                        Text(stringResource(R.string.speech_merge_punctuation_default))
                    }
                    TextButton(onClick = { dialogVisible = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            },
        )
    }
}
