package com.subtitleedit

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.subtitleedit.ui.ToolsScreen
import com.subtitleedit.ui.ToolDestination
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme

/** Tools page containing shortcuts to the available subtitle utilities. */
class ToolsActivity : AppCompatActivity() {

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolsPage(
    onBack: () -> Unit,
    onOpen: (ToolDestination) -> Unit
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.menu_main_title_01)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_back),
                            contentDescription = stringResource(R.string.tools_navigate_back),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        ToolsScreen(
            onOpen = onOpen,
            modifier = Modifier.padding(innerPadding)
        )
    }
}
