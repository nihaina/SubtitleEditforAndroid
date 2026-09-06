package com.subtitleedit.util

import android.content.Context
import android.net.Uri
import java.io.File

/** 字幕格式转换工具页和文件管理页共用的转换入口。 */
object SubtitleFormatConverter {
    val supportedTargetFormats = listOf(
        SubtitleParser.SubtitleFormat.SRT,
        SubtitleParser.SubtitleFormat.LRC,
        SubtitleParser.SubtitleFormat.VTT
    )
    val supportedSourceFormats = supportedTargetFormats + SubtitleParser.SubtitleFormat.TXT

    data class Source(
        val fileName: String,
        val content: String,
        val format: SubtitleParser.SubtitleFormat
    )

    fun readFile(context: Context, file: File): Source {
        val content = FileUtils.readFile(
            file,
            charset = SettingsManager.getInstance(context).getDefaultEncoding()
        )
        return source(content, file.name)
    }

    fun readUri(context: Context, uri: Uri, fileName: String): Source {
        val content = FileUtils.readUri(
            context,
            uri,
            charset = SettingsManager.getInstance(context).getDefaultEncoding()
        )
        return source(content, fileName)
    }

    fun convert(source: Source, targetFormat: SubtitleParser.SubtitleFormat): String =
        SubtitleParser.convertFormat(source.content, source.format, targetFormat)

    fun extension(format: SubtitleParser.SubtitleFormat): String =
        when (format) {
            SubtitleParser.SubtitleFormat.SRT -> "srt"
            SubtitleParser.SubtitleFormat.LRC -> "lrc"
            SubtitleParser.SubtitleFormat.VTT -> "vtt"
            else -> error("不支持的字幕目标格式")
        }

    fun displayName(format: SubtitleParser.SubtitleFormat): String =
        when (format) {
            SubtitleParser.SubtitleFormat.VTT -> "WebVTT"
            else -> format.name
        }

    private fun source(content: String, fileName: String): Source = Source(
        fileName = fileName,
        content = content,
        format = SubtitleParser.detectFormat(content, fileName)
    )
}
