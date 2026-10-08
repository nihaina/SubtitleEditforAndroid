package com.subtitleedit.util

/** HTML style toggles matching MainViewModel.ToggleItalic/ToggleBold in Subtitle Edit. */
object SubtitleStyleOps {
    enum class Style(val tag: String) {
        ITALIC("i"),
        BOLD("b")
    }

    /**
     * The first cue determines the action for the whole selection. Subtitle Edit detects
     * only the exact lowercase opening tag, but removes both lowercase and uppercase tags
     * before optionally wrapping each nonempty cue once.
     */
    fun toggle(texts: List<String>, style: Style): List<String> {
        val opening = "<${style.tag}>"
        val closing = "</${style.tag}>"
        val uppercaseOpening = "<${style.tag.uppercase()}>"
        val uppercaseClosing = "</${style.tag.uppercase()}>"
        val addStyle = texts.firstOrNull()?.contains(opening) != true

        return texts.map { text ->
            val unstyled = text.replace(opening, "")
                .replace(closing, "")
                .replace(uppercaseOpening, "")
                .replace(uppercaseClosing, "")
            if (addStyle && unstyled.isNotEmpty()) opening + unstyled + closing else unstyled
        }
    }
}
