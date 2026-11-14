package com.subtitleedit.player

import android.view.ViewParent
import android.view.ViewTreeObserver
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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

/** Material sheets own another window, so they must opt into the player's immersive mode. */
@Composable
internal fun PlayerPanelImmersiveWindow() {
    val view = LocalView.current
    DisposableEffect(view) {
        var parent: ViewParent? = view.parent
        var window: Window? = null
        while (parent != null) {
            if (parent is DialogWindowProvider) {
                window = parent.window
                break
            }
            parent = parent.parent
        }
        val panelWindow = window
        val observer = view.viewTreeObserver
        val listener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (focused) panelWindow?.hidePlayerSystemBars()
        }
        panelWindow?.hidePlayerSystemBars()
        observer.addOnWindowFocusChangeListener(listener)
        onDispose {
            if (observer.isAlive) observer.removeOnWindowFocusChangeListener(listener)
        }
    }
}
