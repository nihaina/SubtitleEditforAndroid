package com.subtitleedit.repository

import android.content.Context
import android.net.Uri
import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.FileUtils
import com.subtitleedit.util.subtitle.SubtitleDocument
import java.io.File
import java.nio.charset.Charset

internal class DefaultSubtitleRepository : SubtitleRepository {
    override fun load(content: String, fileName: String?): SubtitleDocument =
        SubtitleParser.parseDocument(content, fileName)

    override fun save(document: SubtitleDocument): String = SubtitleParser.serialize(document)

    override fun convert(
        content: String,
        from: SubtitleParser.SubtitleFormat,
        to: SubtitleParser.SubtitleFormat
    ): String = SubtitleParser.convertFormat(content, from, to)

    override fun readFile(file: File, charset: Charset?): String =
        FileUtils.readFile(file, charset)

    override fun readUri(context: Context, uri: Uri, charset: Charset?): String =
        FileUtils.readUri(context, uri, charset)

    override fun writeFile(file: File, content: String, charset: Charset) {
        FileUtils.writeFile(file, content, charset)
    }

    override fun writeUri(context: Context, uri: Uri, content: String, charset: Charset) {
        val outputStream = context.contentResolver.openOutputStream(uri)
            ?: throw IllegalStateException("无法打开目标文件")
        outputStream.use { it.write(content.toByteArray(charset)) }
    }
}
