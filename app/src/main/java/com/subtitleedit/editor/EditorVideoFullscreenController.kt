package com.subtitleedit.editor

import android.content.pm.ActivityInfo
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.PopupMenu
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.subtitleedit.R
import com.subtitleedit.databinding.ActivityEditorBinding

internal class EditorVideoFullscreenController(
    private val activity: AppCompatActivity,
    private val binding: ActivityEditorBinding,
    private val isVideo: Boolean,
    private val isFullscreen: () -> Boolean,
    private val setFullscreen: (Boolean) -> Unit,
    private val isCollapsed: () -> Boolean,
    private val setCollapsed: (Boolean) -> Unit,
    private val isMixed: () -> Boolean,
    private val setMixed: (Boolean) -> Unit,
    private val expandWaveform: () -> Unit,
    private val onControlsPinnedChanged: (Boolean) -> Unit,
    private val inlineViewportIndex: () -> Int,
    private val previousOrientation: () -> Int,
    private val setPreviousOrientation: (Int) -> Unit
) {
    private var isMoreMenuOpen = false

    fun bind(onInteraction: () -> Unit) {
        if (!isVideo) return
        binding.btnVideoFullscreen.setOnClickListener {
            onInteraction()
            toggle()
        }
        binding.btnVideoMore.setOnClickListener { showMoreMenu() }
        ViewCompat.setOnApplyWindowInsetsListener(binding.videoControlsOverlay) { view, insets ->
            val safeArea = if (isFullscreen()) {
                insets.getInsets(
                    WindowInsetsCompat.Type.displayCutout() or
                        WindowInsetsCompat.Type.systemGestures()
                )
            } else {
                androidx.core.graphics.Insets.NONE
            }
            view.setPadding(safeArea.left, 0, safeArea.right, 0)
            insets
        }
        renderButton()
        renderCollapsed()
    }

    fun toggle() {
        if (isFullscreen()) exit() else enter()
    }

    fun exitIfActive() {
        if (isFullscreen()) exit()
    }

    fun resetMixedDisplay() {
        if (!isMixed()) return
        setMixed(false)
        renderCollapsed()
    }

    /**
     * Expands the inline video viewport for a transient editor overlay.
     *
     * Mixed display forces the video into the collapsed waveform layout.  A
     * coordinate picker needs the full video frame, but the normal collapsed
     * state must remain untouched when the picker was opened from elsewhere.
     */
    fun expandInlineDisplay() {
        if (!isVideo || isFullscreen() || !isCollapsed()) return
        setCollapsed(false)
        renderCollapsed()
    }

    /** Restores the mixed video/waveform layout after a transient editor overlay. */
    fun setMixedDisplay(mixed: Boolean) {
        if (!isVideo || isFullscreen() || isMixed() == mixed) return
        setMixed(mixed)
        renderCollapsed()
    }

    fun onWindowFocusChanged(hasFocus: Boolean) {
        if (hasFocus && isFullscreen()) hideSystemBars()
    }

    fun onConfigurationChanged() {
        if (isVideo && !isFullscreen()) {
            binding.videoViewportContainer.layoutParams = createInlineVideoLayoutParams()
        }
        renderCollapsed()
    }

    private fun enter() {
        if (!isVideo || isFullscreen()) return
        val viewport = binding.videoViewportContainer
        (viewport.parent as? ViewGroup)?.removeView(viewport)
        binding.videoFullscreenHost.addView(
            viewport,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        binding.videoFullscreenHost.visibility = View.VISIBLE
        setFullscreen(true)
        binding.editorAppBar.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        binding.editorContent.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        setPreviousOrientation(activity.requestedOrientation)
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        hideSystemBars()
        ViewCompat.requestApplyInsets(binding.videoControlsOverlay)
        renderButton()
        renderCollapsed()
    }

    private fun exit() {
        if (!isFullscreen()) return
        val viewport = binding.videoViewportContainer
        binding.videoFullscreenHost.removeView(viewport)
        setFullscreen(false)
        binding.videoSection.addView(
            viewport,
            inlineViewportIndex().coerceAtMost(binding.videoSection.childCount),
            createInlineVideoLayoutParams()
        )
        binding.videoFullscreenHost.visibility = View.GONE
        binding.editorAppBar.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        binding.editorContent.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        showSystemBars()
        activity.requestedOrientation = previousOrientation()
        ViewCompat.requestApplyInsets(binding.videoControlsOverlay)
        renderButton()
        renderCollapsed()
    }

    private fun showMoreMenu() {
        if (!isVideo) return
        isMoreMenuOpen = true
        updateControlsPinned()
        val popup = PopupMenu(activity, binding.btnVideoMore)
        popup.menu.add(
            if (isCollapsed() && !isFullscreen()) R.string.editor_video_expand
            else R.string.editor_video_collapse
        ).setOnMenuItemClickListener {
            val collapse = !(isCollapsed() && !isFullscreen())
            setMixed(false)
            setCollapsed(collapse)
            if (isFullscreen()) exit()
            else renderCollapsed()
            true
        }
        if (!isMixed() || isFullscreen()) {
            popup.menu.add(R.string.editor_video_mixed_display).setOnMenuItemClickListener {
                expandWaveform()
                setMixed(true)
                if (isFullscreen()) exit()
                else renderCollapsed()
                true
            }
        }
        popup.setOnDismissListener {
            isMoreMenuOpen = false
            updateControlsPinned()
        }
        popup.show()
    }

    private fun renderCollapsed() {
        if (!isVideo) return
        val collapsed = isCollapsed() && !isFullscreen()
        val params = binding.videoViewportContainer.layoutParams
        val height = if (collapsed) {
            activity.resources.getDimensionPixelSize(R.dimen.editor_video_controls_height) * 2
        } else if (!isFullscreen()) {
            activity.resources.getDimensionPixelSize(R.dimen.editor_video_height)
        } else {
            ViewGroup.LayoutParams.MATCH_PARENT
        }
        if (!isFullscreen() && params.height != height) {
            params.height = height
            binding.videoViewportContainer.layoutParams = params
        }
        binding.videoControlsOverlay.setBackgroundColor(if (collapsed) Color.BLACK else Color.TRANSPARENT)
        renderVideoOutput()
        updateControlsPinned()
    }

    private fun renderVideoOutput() {
        val mixed = isMixed() && !isFullscreen()
        val video = binding.mpvView
        val host = if (mixed) binding.waveformVideoOverlay else binding.videoViewportContainer
        binding.waveformVideoOverlay.visibility = if (mixed) View.VISIBLE else View.GONE
        if (video.parent !== host) {
            (video.parent as? ViewGroup)?.removeView(video)
            host.addView(
                video,
                0,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        video.alpha = if (mixed) 0.5f else 1f
        // The decorative video layer must let waveform gestures reach the timeline below it.
        video.isClickable = !mixed
        video.isFocusable = false
    }

    private fun updateControlsPinned() {
        onControlsPinnedChanged(isMoreMenuOpen || (isCollapsed() && !isFullscreen()))
    }

    private fun renderButton() {
        if (!isVideo) return
        binding.btnVideoFullscreen.setImageResource(
            if (isFullscreen()) R.drawable.ic_video_fullscreen_exit
            else R.drawable.ic_video_fullscreen
        )
        binding.btnVideoFullscreen.contentDescription = activity.getString(
            if (isFullscreen()) R.string.editor_video_exit_fullscreen
            else R.string.editor_video_fullscreen
        )
    }

    private fun hideSystemBars() {
        binding.editorRoot.fitsSystemWindows = false
        binding.editorRoot.setPadding(0, 0, 0, 0)
        binding.editorAppBar.fitsSystemWindows = false
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        WindowCompat.getInsetsController(activity.window, binding.editorRoot).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun showSystemBars() {
        WindowCompat.getInsetsController(activity.window, binding.editorRoot)
            .show(WindowInsetsCompat.Type.systemBars())
        WindowCompat.setDecorFitsSystemWindows(activity.window, true)
        binding.editorRoot.fitsSystemWindows = true
        binding.editorAppBar.fitsSystemWindows = true
        ViewCompat.requestApplyInsets(binding.editorRoot)
    }

    private fun createInlineVideoLayoutParams() =
        android.widget.LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (isCollapsed()) {
                activity.resources.getDimensionPixelSize(R.dimen.editor_video_controls_height) * 2
            } else {
                activity.resources.getDimensionPixelSize(R.dimen.editor_video_height)
            }
        )
}
