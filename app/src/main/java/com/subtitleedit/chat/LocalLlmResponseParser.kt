package com.subtitleedit.chat

/**
 * Removes reasoning/control channels from local model output.
 *
 * llama.cpp normally normalizes these channels before they reach Kotlin, but
 * model-specific chat parsers can leave the raw protocol in a response (and a
 * channel marker can be split over multiple native callbacks). Keeping this
 * parser here makes the UI contract independent of the native parser result.
 */
internal object LocalLlmResponseParser {
    data class Parsed(
        val visible: String,
        val reasoning: String,
    )

    private enum class Action {
        START_REASONING,
        END_REASONING,
        VISIBLE,
    }

    private data class Marker(
        val text: String,
        val action: Action,
    )

    // Longest markers must be considered first because the shorter channel
    // prefix is also a prefix of the complete marker.
    private val markers = listOf(
        Marker("<|channel|>analysis<|message|>", Action.START_REASONING),
        Marker("<|channel|>final<|message|>", Action.VISIBLE),
        Marker("<|channel|>thought", Action.START_REASONING),
        Marker("<|channel>thought", Action.START_REASONING),
        Marker("<|channel|>analysis", Action.START_REASONING),
        Marker("<|channel|>final", Action.VISIBLE),
        Marker("<|channel>final", Action.VISIBLE),
        Marker("<|end|>", Action.END_REASONING),
        Marker("</think>", Action.END_REASONING),
        Marker("<channel|>", Action.END_REASONING),
        Marker("<|channel|>", Action.END_REASONING),
        Marker("<think>", Action.START_REASONING),
    ).sortedByDescending { it.text.length }

    /**
     * Parses the complete text seen so far. A possible marker prefix at the
     * end is withheld so it cannot flash in the visible output while a stream
     * callback is still delivering the rest of the marker.
     */
    fun parse(text: String): Parsed {
        val visible = StringBuilder()
        val reasoning = StringBuilder()
        var inReasoning = false
        var cursor = 0

        while (cursor < text.length) {
            val marker = markers.firstOrNull { text.startsWith(it.text, cursor, ignoreCase = true) }
            if (marker != null) {
                when (marker.action) {
                    Action.START_REASONING -> inReasoning = true
                    Action.END_REASONING,
                    Action.VISIBLE -> inReasoning = false
                }
                cursor += marker.text.length
                continue
            }

            // Do not emit a marker prefix until the next stream callback
            // confirms whether it is a control token or ordinary text.
            val remaining = text.length - cursor
            if (markers.any {
                    it.text.length > remaining &&
                        it.text.regionMatches(0, text, cursor, remaining, ignoreCase = true)
                }) {
                break
            }

            if (inReasoning) reasoning.append(text[cursor]) else visible.append(text[cursor])
            cursor++
        }

        return Parsed(visible.toString(), reasoning.toString())
    }
}
