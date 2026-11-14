package com.subtitleedit.player

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Per-media bookmark lists persisted as one JSON document in app-private storage. */
internal class PlayerBookmarkStore(private val storeFile: File) {

    fun load(mediaPath: String): List<PlayerBookmark> = synchronized(storeLock) {
        readAll()[mediaPath].orEmpty()
    }

    fun save(mediaPath: String, bookmarks: List<PlayerBookmark>): Boolean = synchronized(storeLock) {
        val all = readAll()
        val valid = bookmarks.filter { it.positionMs >= 0L }.distinctBy { it.positionMs }.sortedBy { it.positionMs }
        if (valid.isEmpty()) all.remove(mediaPath) else all[mediaPath] = valid.toMutableList()
        runCatching {
            storeFile.parentFile?.mkdirs()
            val json = JSONObject()
            all.forEach { (path, list) ->
                json.put(path, JSONArray(list.map(::toJson)))
            }
            val pending = File.createTempFile("bookmarks-", ".tmp", storeFile.absoluteFile.parentFile)
            try {
                pending.outputStream().use { output ->
                    output.write(json.toString().toByteArray(Charsets.UTF_8))
                    output.fd.sync()
                }
                Files.move(pending.toPath(), storeFile.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE)
            } finally {
                pending.delete()
            }
        }.isSuccess
    }

    private fun toJson(bookmark: PlayerBookmark): JSONObject = JSONObject().apply {
        put(KEY_POSITION, bookmark.positionMs)
        put(KEY_CREATED, bookmark.createdAtMs)
        put(KEY_LABEL, bookmark.label)
    }

    private fun readAll(): MutableMap<String, MutableList<PlayerBookmark>> {
        val result = linkedMapOf<String, MutableList<PlayerBookmark>>()
        val file = storeFile
        if (!file.isFile) return result
        val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return result
        json.keys().forEach { path ->
            val array = runCatching { json.getJSONArray(path) }.getOrNull() ?: return@forEach
            val list = mutableListOf<PlayerBookmark>()
            for (index in 0 until array.length()) {
                val item = runCatching { array.getJSONObject(index) }.getOrNull() ?: continue
                if (!item.has(KEY_POSITION) || item.optLong(KEY_POSITION, -1L) < 0L) continue
                list += PlayerBookmark(
                    positionMs = item.optLong(KEY_POSITION),
                    createdAtMs = item.optLong(KEY_CREATED),
                    label = item.optString(KEY_LABEL, "")
                )
            }
            result[path] = list.sortedBy { it.positionMs }.toMutableList()
        }
        return result
    }

    companion object {
        private val storeLock = Any()
        private const val KEY_POSITION = "positionMs"
        private const val KEY_CREATED = "createdAtMs"
        private const val KEY_LABEL = "label"

        fun create(filesDir: File): PlayerBookmarkStore =
            PlayerBookmarkStore(File(filesDir, "player/bookmarks.json"))
    }
}
