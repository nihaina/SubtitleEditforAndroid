package com.subtitleedit.util

/** Keeps sensitive API-key actions unlocked only while the settings session stays foregrounded. */
object AiKeyAccessSession {
    @Volatile
    var isAuthorized: Boolean = false
        private set

    fun authorize() {
        isAuthorized = true
    }

    fun reset() {
        isAuthorized = false
    }
}
