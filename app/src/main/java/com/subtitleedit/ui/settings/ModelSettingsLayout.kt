package com.subtitleedit.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.subtitleedit.ui.components.AppSlider
import com.subtitleedit.ui.components.AppToolScaffold

@Composable
internal fun ModelSettingsScaffold(
    title: String,
    onBack: () -> Unit,
    imePadding: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val background = settingsPageBackground()
    AppToolScaffold(
        title = title,
        onBack = onBack,
        imePadding = imePadding,
        contentBackground = background,
        topBarContainerColor = background,
        topBarScrolledContainerColor = background,
        content = content,
    )
}

@Composable
internal fun ModelSettingsGroup(
    title: String,
    description: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = settingsPrimaryTextColor())
            description?.let { ModelSettingsHint(it) }
        }
        SettingsTileGroup(content = content)
    }
}

@Composable
internal fun ModelSettingsHint(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.bodySmall, color = settingsSecondaryTextColor())
}

@Composable
internal fun ModelSettingsSliderTile(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    description: String? = null,
    valueLabel: String? = null,
    enabled: Boolean = true,
    grouped: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
    input: (@Composable () -> Unit)? = null,
) {
    SettingsTileCard(grouped = grouped) {
        val labelOpacity = if (enabled) 1f else 0.5f
        Row(Modifier.fillMaxWidth().alpha(labelOpacity), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = settingsPrimaryTextColor(),
            )
            valueLabel?.let {
                Text(it, style = MaterialTheme.typography.labelLarge, color = settingsSecondaryTextColor())
            }
        }
        description?.let { ModelSettingsHint(it, Modifier.alpha(labelOpacity)) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppSlider(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                enabled = enabled,
                valueRange = valueRange,
                steps = steps,
                onValueChangeFinished = onValueChangeFinished,
            )
            input?.invoke()
        }
    }
}
