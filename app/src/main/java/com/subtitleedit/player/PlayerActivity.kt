package com.subtitleedit.player

import android.graphics.drawable.ColorDrawable
import android.content.Intent
import android.content.res.Configuration
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.subtitleedit.filepicker.AppFilePickerContracts
import com.subtitleedit.filepicker.FilePickerMode
import com.subtitleedit.filepicker.FilePickerRequest
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import com.subtitleedit.util.FileTypePolicy
import com.subtitleedit.util.PickedUriAccess
import java.io.File

/** Immersive playback page of 音声模式; the queue is handed in by the directory browser. */
class PlayerActivity : AppComposeActivity() {

    private val viewModel: PlayerViewModel by viewModels()
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private var outputDeviceIds: Set<Int>? = null
    private val audioDevices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            refreshAudioRoute()
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            refreshAudioRoute()
        }
    }
    private var portraitCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
    private val tagEditor = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.resumeAfterTagEditing()
    }
    private var subtitlePickerTrackPath: String? = null
    private val subtitlePickerContract = AppFilePickerContracts.OpenDocument(SUBTITLE_EXTENSIONS)
    private val subtitlePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val expectedTrackPath = subtitlePickerTrackPath
        subtitlePickerTrackPath = null
        val uri = subtitlePickerContract.parseResult(result.resultCode, result.data)
        if (expectedTrackPath != null && viewModel.state.value.currentTrack?.path == expectedTrackPath) {
            uri?.let(PickedUriAccess::file)?.let { viewModel.importSubtitle(it, expectedTrackPath) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC
        subtitlePickerTrackPath = savedInstanceState?.getString(STATE_SUBTITLE_PICKER_TRACK)
        if (Build.VERSION.SDK_INT >= 28) {
            portraitCutoutMode = window.attributes.layoutInDisplayCutoutMode
        }
        updatePlayerWindowLayout(resources.configuration)
        window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.hidePlayerSystemBars()
        val savedPaths = savedInstanceState?.getStringArrayList(EXTRA_PLAYLIST)
        val tracks = (savedPaths ?: intent.getStringArrayListExtra(EXTRA_PLAYLIST)).orEmpty().map { path ->
            val file = File(path)
            PlayerTrack(path = path, name = file.name, isVideo = FileTypePolicy.isVideo(file))
        }
        val initialMode = (savedInstanceState?.getString(EXTRA_PLAY_MODE)
            ?: intent.getStringExtra(EXTRA_PLAY_MODE))?.let { value ->
            runCatching { PlayerPlayMode.valueOf(value) }.getOrNull()
        }
        if (savedInstanceState != null && savedPaths != null) {
            viewModel.restoreSession(tracks, savedInstanceState.getInt(EXTRA_START_INDEX), initialMode,
                savedInstanceState.getLong(STATE_POSITION), savedInstanceState.getBoolean(STATE_PLAYING),
                expanded = true, queueSource = savedInstanceState.getPlayerQueueSource())
        } else {
            viewModel.configure(tracks, intent.getIntExtra(EXTRA_START_INDEX, 0), initialMode,
                intent.getPlayerQueueSource())
        }
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
                PlayerScreen(viewModel, onEditTags = ::openTagEditor, onPickSubtitle = ::openSubtitlePicker)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updatePlayerWindowLayout(newConfig)
        // AppCompat 1.6 applies in-place night changes without notifying the Compose view.
        window.decorView.dispatchConfigurationChanged(newConfig)
    }

    override fun onStart() {
        super.onStart()
        refreshAudioRoute()
        audioManager.registerAudioDeviceCallback(audioDevices, Handler(Looper.getMainLooper()))
    }

    private fun refreshAudioRoute() {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).mapTo(mutableSetOf()) { it.id }
        val changed = outputDeviceIds?.let { outputs != it } ?: false
        outputDeviceIds = outputs
        viewModel.refreshSystemVolume(resetBoost = changed)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshSystemVolume()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            viewModel.refreshSystemVolume()
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

    internal fun openSubtitlePicker() {
        val path = viewModel.state.value.currentTrack?.path ?: return
        if (subtitlePickerTrackPath != null) return
        subtitlePickerTrackPath = path
        viewModel.closePanel()
        subtitlePicker.launch(FilePickerRequest(
            mode = FilePickerMode.OPEN_FILE,
            mimeTypes = listOf("*/*"),
            initialPath = File(path).parent,
            allowedExtensions = SUBTITLE_EXTENSIONS.toList()
        ).toIntent(this))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        subtitlePickerTrackPath?.let { outState.putString(STATE_SUBTITLE_PICKER_TRACK, it) }
        val state = viewModel.state.value
        if (state.currentTrack != null) {
            outState.putStringArrayList(EXTRA_PLAYLIST, ArrayList(state.playlist.map { it.path }))
            outState.putInt(EXTRA_START_INDEX, state.currentIndex)
            outState.putString(EXTRA_PLAY_MODE, state.mode.name)
            outState.putLong(STATE_POSITION, state.positionMs)
            outState.putBoolean(STATE_PLAYING, state.playing)
            outState.putPlayerQueueSource(state.queueSource)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        audioManager.unregisterAudioDeviceCallback(audioDevices)
        viewModel.endVolumeGesture()
        viewModel.saveProgress()
        super.onStop()
    }

    companion object {
        const val EXTRA_PLAYLIST = "player_playlist"
        const val EXTRA_START_INDEX = "player_start_index"
        const val EXTRA_PLAY_MODE = "player_play_mode"
        private const val STATE_SUBTITLE_PICKER_TRACK = "player_subtitle_picker_track"
        private const val STATE_POSITION = "player_position"
        private const val STATE_PLAYING = "player_playing"
        private val SUBTITLE_EXTENSIONS = setOf("srt", "lrc", "vtt", "ass", "ssa")
    }
}
