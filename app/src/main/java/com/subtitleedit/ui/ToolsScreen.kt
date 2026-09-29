package com.subtitleedit.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.subtitleedit.R

enum class ToolDestination {
    BATCH_CONVERT,
    SUBTITLE_FORMAT,
    AUTO_TRANSLATE,
    VOCAL_SEPARATION,
    SPEECH_TO_SUBTITLE,
    TRANSCRIPT_MATCH,
    MEDIA_CONVERT,
    AUTO_TIMESTAMP
}

private data class ToolItem(
    @StringRes val title: Int,
    @StringRes val description: Int,
    @DrawableRes val icon: Int,
    val destination: ToolDestination
)

private val toolItems = listOf(
    ToolItem(
        R.string.activity_tools_text_01,
        R.string.activity_tools_text_02,
        R.drawable.ic_convert,
        ToolDestination.BATCH_CONVERT
    ),
    ToolItem(
        R.string.activity_tools_text_03,
        R.string.activity_tools_text_04,
        R.drawable.ic_subtitle_format,
        ToolDestination.SUBTITLE_FORMAT
    ),
    ToolItem(
        R.string.activity_tools_contentdescription_07,
        R.string.activity_tools_text_09,
        R.drawable.ic_ai_translate,
        ToolDestination.AUTO_TRANSLATE
    ),
    ToolItem(
        R.string.activity_tools_contentdescription_03,
        R.string.activity_tools_text_05,
        R.drawable.ic_vocal_separation,
        ToolDestination.VOCAL_SEPARATION
    ),
    ToolItem(
        R.string.activity_tools_contentdescription_04,
        R.string.activity_tools_text_06,
        R.drawable.ic_speech_to_subtitle,
        ToolDestination.SPEECH_TO_SUBTITLE
    ),
    ToolItem(
        R.string.transcript_match_title,
        R.string.transcript_match_description,
        R.drawable.ic_transcript_match,
        ToolDestination.TRANSCRIPT_MATCH
    ),
    ToolItem(
        R.string.activity_tools_contentdescription_05,
        R.string.activity_tools_text_07,
        R.drawable.ic_media_convert,
        ToolDestination.MEDIA_CONVERT
    ),
    ToolItem(
        R.string.activity_tools_contentdescription_06,
        R.string.activity_tools_text_08,
        R.drawable.ic_auto_timestamp,
        ToolDestination.AUTO_TIMESTAMP
    )
)

@Composable
fun ToolsScreen(
    onOpen: (ToolDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(toolItems, key = { it.destination }) { item ->
            ToolCard(item = item, onClick = { onOpen(item.destination) })
        }
    }
}

@Composable
private fun ToolCard(
    item: ToolItem,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(item.icon),
                contentDescription = null,
                modifier = Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                    .padding(8.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(item.title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    text = stringResource(item.description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                painter = painterResource(R.drawable.ic_arrow_right),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
