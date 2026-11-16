package com.subtitleedit.util

import android.content.SharedPreferences

/** A named group of user settings, including effective defaults that are not persisted yet. */
internal interface SettingsSnapshotStore {
    fun exportSettingsSnapshot(): Map<String, Any>
    fun restoreSettingsSnapshot(values: Map<String, Any>)
}

/** Merge only known keys of the expected type; missing keys leave current settings untouched. */
internal fun SharedPreferences.restoreSettingsSnapshot(
    values: Map<String, Any>,
    current: Map<String, Any>
) {
    val editor = edit()
    current.forEach { (key, expected) ->
        val value = values[key]
        when {
            expected is Boolean && value is Boolean -> editor.putBoolean(key, value)
            expected is String && value is String -> editor.putString(key, value)
            expected is Int && value is Int -> editor.putInt(key, value)
            expected is Float && value is Float && value.isFinite() -> editor.putFloat(key, value)
        }
    }
    editor.apply()
}
