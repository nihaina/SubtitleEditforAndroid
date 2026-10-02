package com.subtitleedit.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.subtitleedit.ui.components.AppCard
import com.subtitleedit.ui.components.staggeredAppear
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
    @param:StringRes val title: Int,
    @param:StringRes val description: Int,
    @param:DrawableRes val icon: Int,
    @param:StringRes val iconDescription: Int,
    val destination: ToolDestination
)

private val toolItems = listOf(
    ToolItem(
        R.string.activity_tools_text_01,
        R.string.activity_tools_text_02,
        R.drawable.ic_convert,
        R.string.batch_convert,
        ToolDestination.BATCH_CONVERT
    ),
    ToolItem(
        R.string.activity_tools_text_03,
        R.string.activity_tools_text_04,
        R.drawable.ic_subtitle_format,
        R.string.activity_tools_contentdescription_02,
        ToolDestination.SUBTITLE_FORMAT
    ),
    ToolItem(
        R.string.activity_tools_contentdescription_07,
        R.string.activity_tools_text_09,
        R.drawable.ic_ai_translate,
        R.string.activity_tools_contentdescription_07,
        ToolDestination.AUTO_TRANSLATE
    ),
    ToolItem(
        R.string.activity_tools_contentdescription_03,
        R.string.activity_tools_text_05,
        R.drawable.ic_vocal_separation,
        R.string.activity_tools_contentdescription_03,
        ToolDestination.VOCAL_SEPARATION
    ),
    ToolItem(
        R.string.activity_tools_contentdescription_04,
        R.string.activity_tools_text_06,
        R.drawable.ic_speech_to_subtitle,
        R.string.activity_tools_contentdescription_04,
        ToolDestination.SPEECH_TO_SUBTITLE
    ),
    ToolItem(
        R.string.transcript_match_title,
        R.string.transcript_match_description,
        R.drawable.ic_transcript_match,
        R.string.transcript_match_title,
        ToolDestination.TRANSCRIPT_MATCH
    ),
    ToolItem(
        R.string.activity_tools_contentdescription_05,
        R.string.activity_tools_text_07,
        R.drawable.ic_media_convert,
        R.string.activity_tools_contentdescription_05,
        ToolDestination.MEDIA_CONVERT
    ),
    ToolItem(
        R.string.activity_tools_contentdescription_06,
        R.string.activity_tools_text_08,
        R.drawable.ic_auto_timestamp,
        R.string.activity_tools_contentdescription_06,
        ToolDestination.AUTO_TIMESTAMP
    )
)

@Composable
fun ToolsScreen(
    onOpen: (ToolDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        toolItems.forEachIndexed { index, item ->
            ToolCard(
                item = item,
                onClick = { onOpen(item.destination) },
                modifier = Modifier.staggeredAppear(index)
            )
        }
    }
}

@Composable
private fun ToolCard(
    item: ToolItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AppCard(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(item.icon),
                    contentDescription = stringResource(item.iconDescription),
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
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
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_arrow_right),
                contentDescription = stringResource(R.string.activity_tools_contentdescription_01),
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}
