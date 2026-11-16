package com.subtitleedit.player

import androidx.compose.runtime.staticCompositionLocalOf

/** The main window opens queues in its persistent player instead of starting another Activity. */
internal val LocalPlayerLauncher =
    staticCompositionLocalOf<((List<String>, Int, PlayerPlayMode?) -> Unit)?> { null }
