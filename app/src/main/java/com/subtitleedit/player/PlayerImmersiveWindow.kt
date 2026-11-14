package com.subtitleedit.player

import android.content.res.Configuration
import android.os.Build
import android.view.ViewParent
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

internal fun Window.hidePlayerSystemBars() {
    WindowCompat.setDecorFitsSystemWindows(this, false)
    WindowInsetsControllerCompat(this, decorView).apply {
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        hide(WindowInsetsCompat.Type.systemBars())
    }
}

/** Landscape media spans the complete display; portrait retains its original window policy. */
internal fun Window.updatePlayerCutoutMode(landscape: Boolean, portraitMode: Int) {
    if (Build.VERSION.SDK_INT < 28) return
    val mode = when {
        !landscape -> portraitMode
        Build.VERSION.SDK_INT >= 30 -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        else -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }
    if (attributes.layoutInDisplayCutoutMode != mode) {
        attributes = attributes.apply { layoutInDisplayCutoutMode = mode }
    }
}

/** Material sheets own another window, so they must opt into the player's immersive mode. */
@Composable
internal fun PlayerPanelImmersiveWindow() {
    val view = LocalView.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val panelWindow = remember(view) {
        var parent: ViewParent? = view.parent
        var window: Window? = null
        while (parent != null) {
            if (parent is DialogWindowProvider) {
                window = parent.window
                break
            }
            parent = parent.parent
        }
        window
    }
    val portraitCutoutMode = remember(panelWindow) {
        if (Build.VERSION.SDK_INT >= 28) panelWindow?.attributes?.layoutInDisplayCutoutMode
            ?: WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
        else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
    }
    DisposableEffect(view, panelWindow, landscape) {
        val observer = view.viewTreeObserver
        val listener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (focused) {
                panelWindow?.updatePlayerCutoutMode(landscape, portraitCutoutMode)
                panelWindow?.hidePlayerSystemBars()
            }
        }
        panelWindow?.updatePlayerCutoutMode(landscape, portraitCutoutMode)
        panelWindow?.hidePlayerSystemBars()
        observer.addOnWindowFocusChangeListener(listener)
        onDispose {
            if (observer.isAlive) observer.removeOnWindowFocusChangeListener(listener)
        }
    }
}
