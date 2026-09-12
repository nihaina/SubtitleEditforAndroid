package com.subtitleedit.editor

import androidx.recyclerview.widget.LinearLayoutManager
import com.subtitleedit.databinding.ActivityEditorBinding
import com.subtitleedit.adapter.SubtitleAdapter

/** Coordinates editor controller cleanup and state preservation across lifecycle changes. */
internal class EditorLifecycleCoordinator(
    private val binding: ActivityEditorBinding,
    private val subtitleAdapter: SubtitleAdapter,
    private val sourcePreview: EditorSourcePreviewController,
    private val sourceWaveformSync: EditorSourceWaveformSyncController,
    private val subtitlePreview: EditorSubtitlePreviewController?,
    private val playback: EditorPlaybackController,
    private val waveform: EditorWaveformController,
    private val tts: EditorTtsController,
    private val translation: EditorTranslationController,
    private val transcribe: EditorTranscribeController,
    private val videoFullscreen: EditorVideoFullscreenController?,
    private val mediaRelease: () -> Unit,
    private val isDocumentLoaded: () -> Boolean,
    private val isSourceMode: () -> Boolean,
    private val hasPendingSourceEdits: () -> Boolean,
    private val snapshotSource: () -> Unit,
    private val scheduleSourcePreview: () -> Unit,
    private val scheduleSubtitlePreview: () -> Unit,
    private val cancelSourceParse: () -> Unit,
    private val savePlaybackState: (Long, Float) -> Unit,
    private val saveSelectedIndices: (Set<Int>) -> Unit,
    private val saveSourceScroll: (Int) -> Unit,
    private val saveListScroll: (Int, Int) -> Unit
) {
    fun onStop() {
        sourcePreview.cancel()
        sourceWaveformSync.cancel()
        subtitlePreview?.cancelPending()
        cancelSourceParse()
        if (isSourceMode() && hasPendingSourceEdits()) snapshotSource()
        saveSelectedIndices(subtitleAdapter.getSelectedPositions())
        if (isSourceMode()) {
            saveSourceScroll(binding.etSourceView.getDocumentScrollOffset())
        } else {
            val manager = binding.rvSubtitles.layoutManager as? LinearLayoutManager
            val position = manager?.findFirstVisibleItemPosition() ?: -1
            if (position >= 0) saveListScroll(position, manager?.findViewByPosition(position)?.top ?: 0)
        }
        savePlaybackState(playback.currentPositionMs, playback.playbackSpeed)
        playback.pauseForLifecycle()
    }

    fun onStart() {
        if (!isDocumentLoaded()) return
        if (isSourceMode() && hasPendingSourceEdits()) scheduleSourcePreview()
        else scheduleSubtitlePreview()
    }

    fun onDestroy() {
        sourcePreview.cancel()
        sourceWaveformSync.cancel()
        tts.release()
        subtitlePreview?.release()
        mediaRelease()
        playback.release()
        waveform.release()
        translation.release()
        transcribe.release()
    }

    fun onWindowFocusChanged(hasFocus: Boolean) {
        videoFullscreen?.onWindowFocusChanged(hasFocus)
    }

    fun onConfigurationChanged() {
        videoFullscreen?.onConfigurationChanged()
    }
}
