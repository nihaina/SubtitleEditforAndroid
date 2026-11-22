package com.subtitleedit.work

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import android.util.Base64
import com.subtitleedit.R
import com.subtitleedit.util.PickedUriAccess
import com.subtitleedit.util.SubtitleOutputWriter
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal data class SpeechToSubtitleFile(
    val uri: Uri,
    val fileName: String,
    val fileSize: Long?,
    val outputDirectory: Uri,
)

internal data class SpeechToSubtitleRequest(
    val files: List<SpeechToSubtitleFile>,
    val language: String,
    val format: String,
    val modelType: String,
    val encoderPath: String,
    val decoderPath: String,
    val joinerPath: String,
    val tokensPath: String,
    val vadModelPath: String,
    val forcedAlignerPath: String,
    val useVad: Boolean,
    val useTokenTimeline: Boolean,
    val saveToSourceDirectory: Boolean,
    val outputDirectory: Uri,
    val overwriteOutput: Boolean,
    val addToAutoTranslate: Boolean,
    val disableVadForTxt: Boolean,
)

internal data class SpeechToSubtitleContent(val text: String, val segmentCount: Int)

internal data class SpeechToSubtitleOutput(val uri: Uri, val fileName: String, val segmentCount: Int)

internal data class SpeechToSubtitleFileOutcome(
    val output: SpeechToSubtitleOutput? = null,
    val error: String? = null,
)

/** Stores the batch and its checkpoints outside WorkManager's Data size limit. */
internal class SpeechToSubtitleTaskStore(private val context: Context, id: UUID) {
    private val directory = File(root(context), id.toString())

    fun writeRequest(request: SpeechToSubtitleRequest) {
        val files = JSONArray()
        request.files.forEach { file ->
            files.put(JSONObject()
                .put("uri", file.uri.toString())
                .put("fileName", file.fileName)
                .put("fileSize", file.fileSize)
                .put("outputDirectory", file.outputDirectory.toString()))
        }
        write("request.json", JSONObject()
            .put("files", files)
            .put("language", request.language)
            .put("format", request.format)
            .put("modelType", request.modelType)
            .put("encoderPath", request.encoderPath)
            .put("decoderPath", request.decoderPath)
            .put("joinerPath", request.joinerPath)
            .put("tokensPath", request.tokensPath)
            .put("vadModelPath", request.vadModelPath)
            .put("forcedAlignerPath", request.forcedAlignerPath)
            .put("useVad", request.useVad)
            .put("useTokenTimeline", request.useTokenTimeline)
            .put("saveToSourceDirectory", request.saveToSourceDirectory)
            .put("outputDirectory", request.outputDirectory.toString())
            .put("overwriteOutput", request.overwriteOutput)
            .put("addToAutoTranslate", request.addToAutoTranslate)
            .put("disableVadForTxt", request.disableVadForTxt)
            .toString())
    }

    fun readRequest(): SpeechToSubtitleRequest {
        val json = JSONObject(checkNotNull(read("request.json")) {
            context.getString(R.string.speech_to_subtitle_missing_inputs)
        })
        val files = json.getJSONArray("files")
        return SpeechToSubtitleRequest(
            files = List(files.length()) { index ->
                val file = files.getJSONObject(index)
                SpeechToSubtitleFile(
                    uri = Uri.parse(file.getString("uri")),
                    fileName = file.getString("fileName"),
                    fileSize = file.optLong("fileSize", -1L).takeIf { it >= 0 },
                    outputDirectory = Uri.parse(file.getString("outputDirectory")),
                )
            },
            language = json.getString("language"),
            format = json.getString("format"),
            modelType = json.getString("modelType"),
            encoderPath = json.getString("encoderPath"),
            decoderPath = json.getString("decoderPath"),
            joinerPath = json.getString("joinerPath"),
            tokensPath = json.getString("tokensPath"),
            vadModelPath = json.getString("vadModelPath"),
            forcedAlignerPath = json.getString("forcedAlignerPath"),
            useVad = json.getBoolean("useVad"),
            useTokenTimeline = json.getBoolean("useTokenTimeline"),
            saveToSourceDirectory = json.getBoolean("saveToSourceDirectory"),
            outputDirectory = Uri.parse(json.getString("outputDirectory")),
            overwriteOutput = json.getBoolean("overwriteOutput"),
            addToAutoTranslate = json.getBoolean("addToAutoTranslate"),
            disableVadForTxt = json.getBoolean("disableVadForTxt"),
        )
    }

    fun readOutcome(index: Int): SpeechToSubtitleFileOutcome? {
        val json = read("outcome-$index.json")?.let(::JSONObject) ?: return null
        return if (json.has("error")) {
            SpeechToSubtitleFileOutcome(error = json.getString("error"))
        } else {
            SpeechToSubtitleFileOutcome(output = SpeechToSubtitleOutput(
                Uri.parse(json.getString("uri")), json.getString("fileName"), json.getInt("segmentCount")
            ))
        }
    }

    fun recoverOutput(index: Int): SpeechToSubtitleOutput? {
        val pending = read("pending-$index.json")?.let(::JSONObject) ?: return null
        val name = pending.getString("fileName")
        val uri = outputUri(Uri.parse(pending.getString("directory")), name) ?: return null
        val digest = runCatching {
            val input = PickedUriAccess.file(uri)?.inputStream() ?: context.contentResolver.openInputStream(uri)
            input?.use(::hash)
        }.getOrNull()
        if (digest != pending.getString("digest")) return null
        return SpeechToSubtitleOutput(uri, name, pending.getInt("segmentCount")).also { writeOutput(index, it) }
    }

    fun writeFailure(index: Int, message: String) {
        write("outcome-$index.json", JSONObject().put("error", message).toString())
        deletePending(index)
    }

    fun export(
        index: Int,
        file: SpeechToSubtitleFile,
        format: String,
        content: SpeechToSubtitleContent,
        overwrite: Boolean,
    ): SpeechToSubtitleOutput {
        val digest = content.text.byteInputStream(Charsets.UTF_8).use { hash(it) }
        val name = SubtitleOutputWriter.writeText(
            context, file.outputDirectory, file.fileName.substringBeforeLast("."),
            format.lowercase(), content.text, overwrite,
        ) { targetName ->
            write("pending-$index.json", JSONObject()
                .put("directory", file.outputDirectory.toString())
                .put("fileName", targetName)
                .put("segmentCount", content.segmentCount)
                .put("digest", digest)
                .toString())
        }
        val uri = outputUri(file.outputDirectory, name)
            ?: error(context.getString(R.string.speech_to_subtitle_error_output_file_not_found))
        return SpeechToSubtitleOutput(uri, name, content.segmentCount).also { writeOutput(index, it) }
    }

    private fun writeOutput(index: Int, output: SpeechToSubtitleOutput) {
        write("outcome-$index.json", JSONObject()
            .put("uri", output.uri.toString())
            .put("fileName", output.fileName)
            .put("segmentCount", output.segmentCount)
            .toString())
        deletePending(index)
    }

    fun writeLog(text: String) = write("log.txt", text)

    fun readLog(): String = read("log.txt").orEmpty()

    fun isAcknowledged(): Boolean = read("acknowledged") != null

    fun acknowledge() = write("acknowledged", "1")

    fun delete() = synchronized(fileLock) { directory.deleteRecursively() }

    private fun deletePending(index: Int) = synchronized(fileLock) {
        atomicFile("pending-$index.json").delete()
    }

    private fun outputUri(directoryUri: Uri, fileName: String): Uri? {
        return PickedUriAccess.file(directoryUri)?.let { Uri.fromFile(File(it, fileName)) }
            ?: PickedUriAccess.directory(context, directoryUri)?.findFile(fileName)?.uri
    }

    private fun atomicFile(name: String) = AtomicFile(File(directory, name))

    private fun read(name: String): String? = synchronized(fileLock) {
        try {
            atomicFile(name).readFully().toString(Charsets.UTF_8)
        } catch (_: FileNotFoundException) {
            null
        }
    }

    private fun write(name: String, content: String) = synchronized(fileLock) {
        val file = atomicFile(name)
        val stream = file.startWrite()
        try {
            stream.write(content.toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Throwable) {
            file.failWrite(stream)
            throw error
        }
    }

    private fun hash(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return Base64.encodeToString(digest.digest(), Base64.NO_WRAP)
    }

    companion object {
        private val fileLock = Any()
        private fun root(context: Context) = File(context.noBackupFilesDir, "speech-to-subtitle")
    }
}
