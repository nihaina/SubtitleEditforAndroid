package com.subtitleedit.util

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Reusable rename rules only; source paths and file metadata never enter history. */
internal object BatchRenameHistory {
    private const val MAX_ENTRIES = 50

    data class Entry(val id: String, val options: BatchRename.Options)

    fun decode(json: String): List<Entry> {
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val ids = mutableSetOf<String>()
        val rules = mutableSetOf<BatchRename.Options>()
        return buildList {
            for (index in 0 until array.length()) {
                if (size >= MAX_ENTRIES) break
                val entry = array.optJSONObject(index)?.let(::decodeEntry) ?: continue
                if (entry.id in ids || entry.options in rules) continue
                ids += entry.id
                rules += entry.options
                add(entry)
            }
        }
    }

    fun encode(entries: List<Entry>): String = JSONArray().apply {
        entries.take(MAX_ENTRIES).forEach { entry ->
            val options = entry.options
            put(JSONObject()
                .put("id", entry.id)
                .put("options", JSONObject()
                    .put("pattern", options.pattern)
                    .put("extension", options.extension ?: JSONObject.NULL)
                    .put("numberWidth", options.numberWidth)
                    .put("startNumber", options.startNumber)
                    .put("find", options.find)
                    .put("replacement", options.replacement)
                    .put("regexEnabled", options.regexEnabled)))
        }
    }.toString()

    fun record(entries: List<Entry>, options: BatchRename.Options): List<Entry> {
        BatchRename.preview(emptyList(), options)
        val id = entries.firstOrNull { it.options == options }?.id ?: UUID.randomUUID().toString()
        return (listOf(Entry(id, options)) + entries.filterNot { it.options == options || it.id == id })
            .take(MAX_ENTRIES)
    }

    fun remove(entries: List<Entry>, id: String): List<Entry> = entries.filterNot { it.id == id }

    private fun decodeEntry(json: JSONObject): Entry? = runCatching {
        val id = json.get("id") as? String ?: return@runCatching null
        if (id.isBlank()) return@runCatching null
        val value = json.getJSONObject("options")
        val defaults = BatchRename.Options()
        val pattern = value.get("pattern") as? String ?: return@runCatching null
        val extension = when (val stored = value.opt("extension")) {
            null, JSONObject.NULL -> null
            is String -> stored
            else -> return@runCatching null
        }
        val options = BatchRename.Options(
            pattern = pattern,
            extension = extension,
            numberWidth = if (value.has("numberWidth")) value.get("numberWidth").toString().toIntOrNull()
                ?: return@runCatching null else defaults.numberWidth,
            startNumber = if (value.has("startNumber")) value.get("startNumber").toString().toLongOrNull()
                ?: return@runCatching null else defaults.startNumber,
            find = if (value.has("find")) value.get("find") as? String ?: return@runCatching null else defaults.find,
            replacement = if (value.has("replacement")) value.get("replacement") as? String ?: return@runCatching null else defaults.replacement,
            regexEnabled = if (value.has("regexEnabled")) value.get("regexEnabled") as? Boolean
                ?: return@runCatching null else defaults.regexEnabled
        )
        BatchRename.preview(emptyList(), options)
        Entry(id, options)
    }.getOrNull()
}
