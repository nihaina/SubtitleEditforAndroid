package com.subtitleedit.player

import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Lifecycle
import com.subtitleedit.util.FileTypePolicy
import com.subtitleedit.MainActivity
import com.subtitleedit.MediaTagEditorActivity
import com.subtitleedit.filepicker.AppFilePickerContracts
import com.subtitleedit.filepicker.FilePickerMode
import com.subtitleedit.filepicker.FilePickerRequest
import com.subtitleedit.util.PickedUriAccess
import java.io.File

/** Activity integrations shared by the expanded player and the main window's mini player. */
internal class MainPlayerController(
    private val activity: MainActivity,
    private val viewModel: PlayerViewModel
) : DefaultLifecycleObserver {
    private val playbackOwnerChanged: () -> Unit = {
        if (PlayerOutputHandoff.hasExclusiveOwner) viewModel.suspendOutput()
        else if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) viewModel.resumeOutput()
    }
    init {
        PlayerOutputHandoff.addListener(playbackOwnerChanged)
        viewModel.connectMediaNotifications()
    }
    private val audioManager = activity.getSystemService(AudioManager::class.java)
    private var outputDeviceIds: Set<Int>? = null
    private var audioDevicesRegistered = false
    private val audioDevices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refreshAudioRoute()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refreshAudioRoute()
    }
    private val tagEditor = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.resumeAfterTagEditing()
    }
    private var subtitlePickerTrackPath: String? = null
    private val subtitlePickerContract = AppFilePickerContracts.OpenDocument(SUBTITLE_EXTENSIONS)
    private val subtitlePicker = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val expectedTrackPath = subtitlePickerTrackPath
        subtitlePickerTrackPath = null
        val uri = subtitlePickerContract.parseResult(result.resultCode, result.data)
        if (expectedTrackPath != null && viewModel.state.value.currentTrack?.path == expectedTrackPath) {
            uri?.let(PickedUriAccess::file)?.let { viewModel.importSubtitle(it, expectedTrackPath) }
        }
    }

    fun restoreState(savedInstanceState: Bundle?) {
        subtitlePickerTrackPath = savedInstanceState?.getString(STATE_SUBTITLE_PICKER_TRACK)
        if (!PlayerPreferences.getInstance(activity).playerModeEnabled) {
            viewModel.closeSession()
            return
        }
        if (PlayerOutputHandoff.hasExclusiveOwner) viewModel.suspendOutput()
        savedInstanceState?.getStringArrayList("main_player_queue")?.let { paths ->
            val tracks = paths.map { path ->
                val file = File(path)
                PlayerTrack(path, file.name, FileTypePolicy.isVideo(file))
            }
            viewModel.restoreSession(tracks, savedInstanceState.getInt("main_player_index"),
                savedInstanceState.getString("main_player_mode")?.let { value ->
                    PlayerPlayMode.entries.firstOrNull { it.name == value }
                }, savedInstanceState.getLong("main_player_position"),
                savedInstanceState.getBoolean("main_player_playing"), savedInstanceState.getBoolean("main_player_expanded"),
                savedInstanceState.getPlayerQueueSource())
        }
    }

    fun saveState(outState: Bundle) {
        subtitlePickerTrackPath?.let { outState.putString(STATE_SUBTITLE_PICKER_TRACK, it) }
        val state = viewModel.state.value
        if (state.currentTrack != null) {
            outState.putStringArrayList("main_player_queue", ArrayList(state.playlist.map { it.path }))
            outState.putInt("main_player_index", state.currentIndex)
            outState.putString("main_player_mode", state.mode.name)
            outState.putLong("main_player_position", state.positionMs)
            outState.putBoolean("main_player_playing", state.playing)
            outState.putBoolean("main_player_expanded", viewModel.expanded.value)
            outState.putPlayerQueueSource(state.queueSource)
        }
    }

    fun cancelPlaybackActivityLaunch() {
        if (!PlayerOutputHandoff.hasExclusiveOwner) viewModel.resumeOutput()
    }

    fun openTagEditor() {
        val path = viewModel.prepareTagEditing() ?: return
        tagEditor.launch(Intent(activity, MediaTagEditorActivity::class.java).apply {
            putStringArrayListExtra(MediaTagEditorActivity.EXTRA_FILE_PATHS, arrayListOf(path))
        })
    }

    fun openSubtitlePicker() {
        val path = viewModel.state.value.currentTrack?.path ?: return
        if (subtitlePickerTrackPath != null) return
        subtitlePickerTrackPath = path
        viewModel.closePanel()
        subtitlePicker.launch(FilePickerRequest(
            mode = FilePickerMode.OPEN_FILE,
            mimeTypes = listOf("*/*"),
            initialPath = File(path).parent,
            allowedExtensions = SUBTITLE_EXTENSIONS.toList()
        ).toIntent(activity))
    }

    override fun onStart(owner: LifecycleOwner) {
        refreshAudioRoute()
        if (!audioDevicesRegistered) {
            audioManager.registerAudioDeviceCallback(audioDevices, Handler(Looper.getMainLooper()))
            audioDevicesRegistered = true
        }
    }

    override fun onResume(owner: LifecycleOwner) {
        if (!PlayerOutputHandoff.hasExclusiveOwner) viewModel.resumeOutput()
        viewModel.refreshSystemVolume()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        PlayerOutputHandoff.removeListener(playbackOwnerChanged)
    }

    override fun onStop(owner: LifecycleOwner) {
        if (audioDevicesRegistered) {
            audioManager.unregisterAudioDeviceCallback(audioDevices)
            audioDevicesRegistered = false
        }
        viewModel.endVolumeGesture()
        viewModel.saveProgress()
    }

    private fun refreshAudioRoute() {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).mapTo(mutableSetOf()) { it.id }
        val changed = outputDeviceIds?.let { outputs != it } ?: false
        outputDeviceIds = outputs
        viewModel.refreshSystemVolume(resetBoost = changed)
    }

    private companion object {
        const val STATE_SUBTITLE_PICKER_TRACK = "main_player_subtitle_picker_track"
        val SUBTITLE_EXTENSIONS = setOf("srt", "lrc", "vtt", "ass", "ssa")
    }
}
