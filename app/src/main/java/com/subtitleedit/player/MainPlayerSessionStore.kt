package com.subtitleedit.player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class MainPlayerSession(
    val paths: List<String>,
    val index: Int,
    val mode: PlayerPlayMode?,
    val positionMs: Long,
    val queueSource: PlayerQueueSource?,
    val audioMode: Boolean
)

/** Saves the selected occurrence and its position together, independently of listening history. */
internal class MainPlayerSessionStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("main_player_session", Context.MODE_PRIVATE)

    fun read(): MainPlayerSession? = runCatching {
        val data = JSONObject(preferences.getString(KEY_SESSION, null) ?: return null)
        val paths = data.getJSONArray("paths").strings()
        val index = data.getInt("index")
        if (index !in paths.indices) return null
        val source = data.optJSONObject("source")?.let { source ->
            val kind = PlayerQueueSourceKind.entries.firstOrNull { it.name == source.optString("kind") }
            if (kind == null) null else PlayerQueueSource(kind, source.getString("key"), source.getJSONArray("roots").strings())
        }
        MainPlayerSession(
            paths = paths,
            index = index,
            mode = PlayerPlayMode.entries.firstOrNull { it.name == data.optString("mode") },
            positionMs = data.optLong("position_ms").coerceAtLeast(0L),
            queueSource = source,
            audioMode = data.optBoolean("audio_mode")
        )
    }.getOrNull()

    fun save(session: MainPlayerSession) {
        val data = JSONObject()
            .put("paths", JSONArray(session.paths))
            .put("index", session.index)
            .put("mode", session.mode?.name)
            .put("position_ms", session.positionMs)
            .put("audio_mode", session.audioMode)
        session.queueSource?.let { source ->
            data.put("source", JSONObject().put("kind", source.kind.name).put("key", source.key)
                .put("roots", JSONArray(source.roots)))
        }
        preferences.edit().putString(KEY_SESSION, data.toString()).apply()
    }

    fun clear() {
        preferences.edit().remove(KEY_SESSION).apply()
    }

    private fun JSONArray.strings(): List<String> = List(length()) { getString(it) }

    private companion object {
        const val KEY_SESSION = "session"
    }
}
