package com.subtitleedit

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal data class PersistedTranslationConfig(
    val provider: String,
    val model: String,
    val targetLanguage: String,
    val customPrompt: String,
    val baseUrl: String,
    val reasoningLevel: String,
    val thinkingEnabled: Boolean
)

internal data class AutoTranslateFileSnapshot(
    val uri: String,
    val fileName: String,
    val fileSize: Long,
    val sessionId: String,
    val status: String,
    val processingStage: String,
    val totalLines: Int,
    val processedLines: Int,
    val progressStage: String?,
    val documentContent: String?,
    val originalText: String?,
    val punctuationPredictionCompleted: Boolean,
    val translatedTexts: List<String>,
    val translationResumeStart: Int?,
    val translationBatchSize: Int?,
    val translationConfig: PersistedTranslationConfig?,
    val punctuationProvider: String?,
    val punctuationEnabled: Boolean,
    val translationEnabled: Boolean,
    val overwriteOutput: Boolean,
    val punctuationCompletedTexts: List<String>,
    val textPunctuationPredictions: List<String>,
    val textTranslations: List<String>,
    val punctuationResumeHistory: Boolean,
    val translationResumeHistory: Boolean
)

internal data class AutoTranslateQueueSnapshot(
    val punctuationPredictionEnabled: Boolean,
    val translationEnabled: Boolean,
    val files: List<AutoTranslateFileSnapshot>
)

internal class AutoTranslateQueueStore(context: Context) {
    private val file = AtomicFile(File(context.applicationContext.filesDir, FILE_NAME))

    @Synchronized
    fun load(): AutoTranslateQueueSnapshot? = runCatching {
        file.openRead().bufferedReader().use { reader ->
            AutoTranslateQueueCodec.decode(reader.readText())
        }
    }.getOrNull()

    @Synchronized
    fun save(snapshot: AutoTranslateQueueSnapshot) {
        val output = file.startWrite()
        try {
            output.write(AutoTranslateQueueCodec.encode(snapshot).toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }

    companion object {
        private const val FILE_NAME = "auto_translate_queue.json"
    }
}

internal object AutoTranslateQueueCodec {
    fun encode(snapshot: AutoTranslateQueueSnapshot): String = JSONObject()
        .put("punctuationPredictionEnabled", snapshot.punctuationPredictionEnabled)
        .put("translationEnabled", snapshot.translationEnabled)
        .put("files", JSONArray().apply { snapshot.files.forEach { put(it.toJson()) } })
        .toString()

    fun decode(source: String): AutoTranslateQueueSnapshot {
        val json = JSONObject(source)
        val files = json.optJSONArray("files") ?: JSONArray()
        return AutoTranslateQueueSnapshot(
            punctuationPredictionEnabled = json.optBoolean("punctuationPredictionEnabled", false),
            translationEnabled = json.optBoolean("translationEnabled", true),
            files = buildList {
                for (index in 0 until files.length()) {
                    files.optJSONObject(index)?.toSnapshot()?.let(::add)
                }
            }
        )
    }

    private fun AutoTranslateFileSnapshot.toJson() = JSONObject()
        .put("uri", uri)
        .put("fileName", fileName)
        .put("fileSize", fileSize)
        .put("sessionId", sessionId)
        .put("status", status)
        .put("processingStage", processingStage)
        .put("totalLines", totalLines)
        .put("processedLines", processedLines)
        .putNullable("progressStage", progressStage)
        .putNullable("documentContent", documentContent)
        .putNullable("originalText", originalText)
        .put("punctuationPredictionCompleted", punctuationPredictionCompleted)
        .put("translatedTexts", JSONArray(translatedTexts))
        .putNullable("translationResumeStart", translationResumeStart)
        .putNullable("translationBatchSize", translationBatchSize)
        .putNullable("translationConfig", translationConfig?.toJson())
        .putNullable("punctuationProvider", punctuationProvider)
        .put("punctuationEnabled", punctuationEnabled)
        .put("translationEnabled", translationEnabled)
        .put("overwriteOutput", overwriteOutput)
        .put("punctuationCompletedTexts", JSONArray(punctuationCompletedTexts))
        .put("textPunctuationPredictions", JSONArray(textPunctuationPredictions))
        .put("textTranslations", JSONArray(textTranslations))
        .put("punctuationResumeHistory", punctuationResumeHistory)
        .put("translationResumeHistory", translationResumeHistory)

    private fun JSONObject.toSnapshot(): AutoTranslateFileSnapshot? {
        val uri = optString("uri").takeIf(String::isNotBlank) ?: return null
        val fileName = optString("fileName").takeIf(String::isNotBlank) ?: return null
        val sessionId = optString("sessionId").takeIf(String::isNotBlank) ?: return null
        return AutoTranslateFileSnapshot(
            uri = uri,
            fileName = fileName,
            fileSize = optLong("fileSize", 0L),
            sessionId = sessionId,
            status = optString("status", "WAITING"),
            processingStage = optString("processingStage", "READING"),
            totalLines = optInt("totalLines", 0),
            processedLines = optInt("processedLines", 0),
            progressStage = nullableString("progressStage"),
            documentContent = nullableString("documentContent"),
            originalText = nullableString("originalText"),
            punctuationPredictionCompleted = optBoolean("punctuationPredictionCompleted", false),
            translatedTexts = stringList("translatedTexts"),
            translationResumeStart = nullableInt("translationResumeStart"),
            translationBatchSize = nullableInt("translationBatchSize"),
            translationConfig = optJSONObject("translationConfig")?.toConfig(),
            punctuationProvider = nullableString("punctuationProvider"),
            punctuationEnabled = optBoolean("punctuationEnabled", false),
            translationEnabled = optBoolean("translationEnabled", true),
            overwriteOutput = optBoolean("overwriteOutput", false),
            punctuationCompletedTexts = stringList("punctuationCompletedTexts"),
            textPunctuationPredictions = stringList("textPunctuationPredictions"),
            textTranslations = stringList("textTranslations"),
            punctuationResumeHistory = optBoolean("punctuationResumeHistory", false),
            translationResumeHistory = optBoolean("translationResumeHistory", false)
        )
    }

    private fun PersistedTranslationConfig.toJson() = JSONObject()
        .put("provider", provider)
        .put("model", model)
        .put("targetLanguage", targetLanguage)
        .put("customPrompt", customPrompt)
        .put("baseUrl", baseUrl)
        .put("reasoningLevel", reasoningLevel)
        .put("thinkingEnabled", thinkingEnabled)

    private fun JSONObject.toConfig(): PersistedTranslationConfig? {
        val provider = optString("provider").takeIf(String::isNotBlank) ?: return null
        return PersistedTranslationConfig(
            provider = provider,
            model = optString("model"),
            targetLanguage = optString("targetLanguage"),
            customPrompt = optString("customPrompt"),
            baseUrl = optString("baseUrl"),
            reasoningLevel = optString("reasoningLevel", "AUTO"),
            thinkingEnabled = optBoolean("thinkingEnabled", true)
        )
    }

    private fun JSONObject.putNullable(key: String, value: Any?): JSONObject =
        put(key, value ?: JSONObject.NULL)

    private fun JSONObject.nullableString(key: String): String? =
        if (isNull(key)) null else optString(key).takeUnless { it == "null" }

    private fun JSONObject.nullableInt(key: String): Int? =
        if (isNull(key)) null else optInt(key)

    private fun JSONObject.stringList(key: String): List<String> {
        val array = optJSONArray(key) ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) add(array.optString(index))
        }
    }
}
