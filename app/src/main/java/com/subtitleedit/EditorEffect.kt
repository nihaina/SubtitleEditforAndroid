package com.subtitleedit

internal sealed interface EditorEffect {
    data object InvalidateOptionsMenu : EditorEffect
    data class ShowMessage(val message: String) : EditorEffect
}
