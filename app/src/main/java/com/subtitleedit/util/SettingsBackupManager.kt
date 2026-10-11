package com.subtitleedit.util

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** JSON export/import for user settings and references to locally selected resources. */
object SettingsBackupManager {
    private const val SCHEMA_VERSION = 1
    private const val FAVORITES_PREFS = "favorite_directories"
    private const val FAVORITES_KEY = "paths"

    data class RestoreResult(val skippedPaths: Int = 0, val skippedFavorites: Int = 0) {
        val skippedCount: Int get() = skippedPaths + skippedFavorites
    }

    fun export(context: Context): String {
        val settings = SettingsManager.getInstance(context)
        val root = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("exportedAt", System.currentTimeMillis())
            .put("softwareDirectory", settings.getSoftwareDirectoryPath())
        val settingsJson = JSONObject()
        settings.exportSettingsSnapshot().forEach { (key, value) ->
            encodeValue(value)?.let { settingsJson.put(key, it) }
        }
        root.put("settings", settingsJson)

        val credentials = JSONObject()
        settings.exportAiApiKeys().forEach { (provider, key) -> credentials.put(provider, key) }
        root.put("credentials", JSONObject().put("aiApiKeys", credentials))

        val favorites = context.getSharedPreferences(FAVORITES_PREFS, Context.MODE_PRIVATE)
            .getStringSet(FAVORITES_KEY, emptySet())
            .orEmpty()
        root.put("favorites", JSONObject().put("directories", JSONArray(favorites.toList())))
        return root.toString(2)
    }

    fun restore(context: Context, json: String): RestoreResult {
        val root = JSONObject(json)
        val version = root.optInt("schemaVersion", -1)
        require(version in 1..SCHEMA_VERSION) { "不支持的备份版本" }
        val settings = SettingsManager.getInstance(context)
        val restored = linkedMapOf<String, Any?>()
        var skippedPaths = 0
        val settingsJson = root.optJSONObject("settings") ?: JSONObject()
        val keys = settingsJson.keys()
        var softwareDirectory: String? = root.optString("softwareDirectory")
            .takeIf { it.isNotBlank() && isAccessibleDirectory(context, it) }
        if (root.has("softwareDirectory") && softwareDirectory == null) skippedPaths++
        while (keys.hasNext()) {
            val key = keys.next()
            val value = decodeValue(settingsJson.optJSONObject(key)) ?: continue
            if (key == "model_directory") {
                if (value is String && isAccessibleDirectory(context, value)) {
                    softwareDirectory = value
                } else {
                    skippedPaths++
                }
            } else if (isPathLikeKey(key) && value is String && value.isNotBlank()) {
                when {
                    isAccessibleReference(context, value) -> restored[key] = value
                    else -> resolveLocalDocumentReference(context, value)?.let {
                        restored[key] = Uri.fromFile(it).toString()
                    } ?: run { skippedPaths++ }
                }
            } else {
                restored[key] = value
            }
        }

        // The software directory also updates ModelDirectoryManager, so use its setter.
        softwareDirectory?.let { settings.setSoftwareDirectory(it) }
        settings.restoreSettingsSnapshot(restored)

        val apiKeys = root.optJSONObject("credentials")?.optJSONObject("aiApiKeys")
        if (apiKeys != null) {
            val values = linkedMapOf<String, String>()
            val apiKeyNames = apiKeys.keys()
            while (apiKeyNames.hasNext()) {
                val provider = apiKeyNames.next()
                apiKeys.optString(provider).takeIf { it.isNotEmpty() }?.let { values[provider] = it }
            }
            settings.restoreAiApiKeys(values)
        }

        var skippedFavorites = 0
        root.optJSONObject("favorites")?.optJSONArray("directories")?.let { directories ->
            val valid = linkedSetOf<String>()
            for (index in 0 until directories.length()) {
                val path = directories.optString(index).trim()
                if (path.isNotEmpty() && File(path).isDirectory && File(path).canRead()) {
                    valid += File(path).absolutePath
                } else if (path.isNotEmpty()) {
                    skippedFavorites++
                }
            }
            context.getSharedPreferences(FAVORITES_PREFS, Context.MODE_PRIVATE)
                .edit().putStringSet(FAVORITES_KEY, valid).apply()
        }
        return RestoreResult(skippedPaths, skippedFavorites)
    }

    private fun encodeValue(value: Any?): JSONObject? = when (value) {
        is String -> JSONObject().put("type", "string").put("value", value)
        is Boolean -> JSONObject().put("type", "boolean").put("value", value)
        is Int -> JSONObject().put("type", "int").put("value", value)
        is Long -> JSONObject().put("type", "long").put("value", value)
        is Float -> JSONObject().put("type", "float").put("value", value.toDouble())
        is Set<*> -> JSONObject().put("type", "string_set")
            .put("value", JSONArray(value.filterIsInstance<String>().toList()))
        else -> null
    }

    private fun decodeValue(value: JSONObject?): Any? {
        if (value == null) return null
        return when (value.optString("type")) {
            "string" -> value.optString("value")
            "boolean" -> value.optBoolean("value")
            "int" -> value.optInt("value")
            "long" -> value.optLong("value")
            "float" -> value.optDouble("value").toFloat()
            "string_set" -> value.optJSONArray("value")?.let { array ->
                buildSet { for (index in 0 until array.length()) add(array.optString(index)) }
            }
            else -> null
        }
    }

    private fun isPathLikeKey(key: String): Boolean {
        val normalized = key.lowercase()
        return normalized.contains("path") || normalized.endsWith("_uri") ||
            normalized.startsWith("output_directory_")
    }

    private fun isAccessibleDirectory(context: Context, value: String): Boolean {
        if (value.isBlank()) return false
        return if (value.startsWith("content://")) {
            val uri = Uri.parse(value)
            DocumentFile.fromTreeUri(context, uri)?.isDirectory == true
        } else {
            fileFromReference(value)?.isDirectory == true
        }
    }

    private fun isAccessibleReference(context: Context, value: String): Boolean {
        if (value.isBlank()) return true
        if (value.startsWith("content://")) {
            val uri = Uri.parse(value)
            // A persisted document URI can be readable even when DocumentFile's
            // metadata cache reports exists() == false. Opening it is the same
            // access check used by the model consumers themselves.
            if (runCatching {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { true } == true
                }.getOrDefault(false)
            ) return true
            return DocumentFile.fromTreeUri(context, uri)?.exists() == true ||
                DocumentFile.fromSingleUri(context, uri)?.exists() == true ||
                runCatching { context.contentResolver.openInputStream(uri)?.use { true } == true }
                    .getOrDefault(false)
        }
        val file = fileFromReference(value) ?: return false
        return file.exists() || runCatching { file.canonicalFile.exists() }.getOrDefault(false)
    }

    private fun fileFromReference(value: String): File? = runCatching {
        val uri = Uri.parse(value)
        when (uri.scheme?.lowercase()) {
            null, "" -> File(value)
            "file" -> File(uri.path ?: return null)
            else -> null
        }
    }.getOrNull()

    /** Recover a primary-storage document URI when the provider no longer exposes metadata. */
    private fun resolveLocalDocumentReference(context: Context, value: String): File? = runCatching {
        val uri = Uri.parse(value)
        if (uri.scheme != "content") return null
        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
            ?: runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            ?: return null
        val parts = documentId.split(':', limit = 2)
        if (!parts.firstOrNull().equals("primary", ignoreCase = true)) return null
        val relative = Uri.decode(parts.getOrNull(1).orEmpty())
        if (relative.isBlank()) return null
        File(Environment.getExternalStorageDirectory(), relative)
            .takeIf { it.exists() }
    }.getOrNull()
}
