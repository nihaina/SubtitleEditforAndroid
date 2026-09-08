package com.subtitleedit.editor

import android.content.pm.ActivityInfo
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
    private val inlineViewportIndex: () -> Int,
    private val previousOrientation: () -> Int,
    private val setPreviousOrientation: (Int) -> Unit
) {
    fun bind(onInteraction: () -> Unit) {
        if (!isVideo) return
        binding.btnVideoFullscreen.setOnClickListener {
            onInteraction()
            toggle()
        }
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
    }

    fun toggle() {
        if (isFullscreen()) exit() else enter()
    }

    fun exitIfActive() {
        if (isFullscreen()) exit()
    }

    fun onWindowFocusChanged(hasFocus: Boolean) {
        if (hasFocus && isFullscreen()) hideSystemBars()
    }

    fun onConfigurationChanged() {
        if (isVideo && !isFullscreen()) {
            binding.videoViewportContainer.layoutParams = createInlineVideoLayoutParams()
        }
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
    }

    private fun exit() {
        if (!isFullscreen()) return
        val viewport = binding.videoViewportContainer
        binding.videoFullscreenHost.removeView(viewport)
        binding.videoSection.addView(
            viewport,
            inlineViewportIndex().coerceAtMost(binding.videoSection.childCount),
            createInlineVideoLayoutParams()
        )
        binding.videoFullscreenHost.visibility = View.GONE
        setFullscreen(false)
        binding.editorAppBar.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        binding.editorContent.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        showSystemBars()
        activity.requestedOrientation = previousOrientation()
        ViewCompat.requestApplyInsets(binding.videoControlsOverlay)
        renderButton()
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
            activity.resources.getDimensionPixelSize(R.dimen.editor_video_height)
        )
}
