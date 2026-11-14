package com.subtitleedit.player

import android.graphics.drawable.ColorDrawable
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowInsetsControllerCompat
import com.subtitleedit.AppComposeActivity
import com.subtitleedit.MediaTagEditorActivity
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import com.subtitleedit.util.FileTypePolicy
import java.io.File

/** Immersive playback page of 音声模式; the queue is handed in by the directory browser. */
class PlayerActivity : AppComposeActivity() {

    private val viewModel: PlayerViewModel by viewModels()
    private var portraitCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
    private val tagEditor = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.resumeAfterTagEditing()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 28) {
            portraitCutoutMode = window.attributes.layoutInDisplayCutoutMode
        }
        updatePlayerWindowLayout(resources.configuration)
        window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.hidePlayerSystemBars()
        val tracks = intent.getStringArrayListExtra(EXTRA_PLAYLIST).orEmpty().map { path ->
            val file = File(path)
            PlayerTrack(path = path, name = file.name, isVideo = FileTypePolicy.isVideo(file))
        }
        viewModel.configure(tracks, intent.getIntExtra(EXTRA_START_INDEX, 0))
        setContent {
            SubtitleEditComposeTheme {
                val background = MaterialTheme.colorScheme.background
                SideEffect {
                    window.setBackgroundDrawable(ColorDrawable(background.toArgb()))
                    WindowInsetsControllerCompat(window, window.decorView).apply {
                        isAppearanceLightStatusBars = background.luminance() > 0.5f
                        isAppearanceLightNavigationBars = background.luminance() > 0.5f
                    }
                }
                LaunchedEffect(Unit) {
                    viewModel.finishRequests.collect { requests -> if (requests > 0) finish() }
                }
                PlayerScreen(viewModel, onEditTags = ::openTagEditor)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updatePlayerWindowLayout(newConfig)
        // AppCompat 1.6 applies in-place night changes without notifying the Compose view.
        window.decorView.dispatchConfigurationChanged(newConfig)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            updatePlayerWindowLayout(resources.configuration)
            window.hidePlayerSystemBars()
        }
    }

    private fun updatePlayerWindowLayout(configuration: Configuration) {
        window.updatePlayerCutoutMode(
            landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
            portraitMode = portraitCutoutMode
        )
    }

    internal fun openTagEditor() {
        val path = viewModel.prepareTagEditing() ?: return
        tagEditor.launch(Intent(this, MediaTagEditorActivity::class.java).apply {
            putStringArrayListExtra(MediaTagEditorActivity.EXTRA_FILE_PATHS, arrayListOf(path))
        })
    }

    override fun onStop() {
        viewModel.saveProgress()
        super.onStop()
    }

    companion object {
        const val EXTRA_PLAYLIST = "player_playlist"
        const val EXTRA_START_INDEX = "player_start_index"
    }
}
