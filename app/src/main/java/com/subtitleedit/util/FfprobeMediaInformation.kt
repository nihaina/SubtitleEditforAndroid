package com.subtitleedit.util

import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.MediaInformation
import com.arthenica.ffmpegkit.MediaInformationJsonParser
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import org.json.JSONObject

/** FFmpeg 8 writes probe JSON to stdout, outside FFmpegKit's session log collector. */
internal object FfprobeMediaInformation {
    fun read(input: String, cacheDirectory: File, execute: (Array<String>) -> Unit = { arguments ->
        val session = FFprobeKit.executeWithArguments(arguments)
        check(ReturnCode.isSuccess(session.getReturnCode())) {
            session.getAllLogsAsString().takeLast(2_000).trim().ifBlank { "FFprobe 无法读取媒体文件" }
        }
    }): MediaInformation {
        require(input.isNotBlank()) { "缺少媒体文件路径" }
        val output = File.createTempFile("ffprobe_", ".json", cacheDirectory)
        try {
            execute(arrayOf("-v", "error", "-hide_banner", "-print_format", "json",
                "-show_format", "-show_streams", "-show_chapters", "-o", output.absolutePath, "-i", input))
            return parse(output.readText(Charsets.UTF_8))
        } finally {
            output.delete()
        }
    }

    internal fun parse(output: String): MediaInformation {
        check(output.isNotBlank()) { "FFprobe 未返回媒体信息" }
        var start = output.indexOf('{')
        while (start >= 0) {
            val end = objectEnd(output, start)
            if (end < 0) {
                start = output.indexOf('{', start + 1)
                continue
            }
            val json = output.substring(start, end + 1)
            val root = runCatching { JSONObject(json) }.getOrNull()
            val streams = root?.optJSONArray("streams")
            val hasStream = streams != null && (0 until streams.length()).any { index ->
                val stream = streams.optJSONObject(index)
                stream != null && stream.optInt("index", -1) >= 0 && stream.optString("codec_type").isNotBlank()
            }
            if (hasStream) return MediaInformationJsonParser.fromWithError(json)
            start = output.indexOf('{', end + 1)
        }
        throw IllegalStateException("FFprobe 返回的信息无效或没有媒体流")
    }

    /** Braces inside quoted metadata, including escaped quotes, are part of its text. */
    private fun objectEnd(text: String, start: Int): Int {
        var depth = 0
        var quoted = false
        var escaped = false
        for (index in start until text.length) {
            val character = text[index]
            if (quoted) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> quoted = false
                }
            } else when (character) {
                '"' -> quoted = true
                '{' -> depth++
                '}' -> if (--depth == 0) return index
            }
        }
        return -1
    }
}
