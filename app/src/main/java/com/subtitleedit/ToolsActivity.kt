package com.subtitleedit

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.subtitleedit.ui.ToolsScreen
import com.subtitleedit.ui.ToolDestination
import com.subtitleedit.ui.components.AppToolScaffold
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme

/** Tools page containing shortcuts to the available subtitle utilities. */
class ToolsActivity : AppComposeActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SubtitleEditComposeTheme {
                ToolsPage(
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onOpen = ::openTool
                )
            }
        }
    }

    private fun openTool(destination: ToolDestination) {
        val activity = when (destination) {
            ToolDestination.BATCH_CONVERT -> BatchConvertActivity::class.java
            ToolDestination.SUBTITLE_FORMAT -> SubtitleFormatSelectActivity::class.java
            ToolDestination.AUTO_TRANSLATE -> AutoTranslateActivity::class.java
            ToolDestination.VOCAL_SEPARATION -> VocalSeparationActivity::class.java
            ToolDestination.SPEECH_TO_SUBTITLE -> SpeechToSubtitleActivity::class.java
            ToolDestination.TRANSCRIPT_MATCH -> TranscriptMatchActivity::class.java
            ToolDestination.MEDIA_CONVERT -> MediaConvertActivity::class.java
            ToolDestination.AUTO_TIMESTAMP -> AutoTimestampActivity::class.java
        }
        startActivity(Intent(this, activity))
    }
}

@Composable
private fun ToolsPage(
    onBack: () -> Unit,
    onOpen: (ToolDestination) -> Unit
) {
    AppToolScaffold(
        title = stringResource(R.string.menu_main_title_01),
        onBack = onBack
    ) {
        ToolsScreen(onOpen = onOpen, modifier = Modifier.fillMaxSize())
    }
}
