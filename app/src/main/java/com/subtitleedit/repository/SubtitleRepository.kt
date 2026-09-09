package com.subtitleedit.repository

import android.content.Context
import android.net.Uri
import com.subtitleedit.util.SubtitleParser
import com.subtitleedit.util.subtitle.SubtitleDocument
import java.io.File
import java.nio.charset.Charset

internal interface SubtitleRepository {
    fun load(content: String, fileName: String? = null): SubtitleDocument

    fun save(document: SubtitleDocument): String

    fun convert(
        content: String,
        from: SubtitleParser.SubtitleFormat,
        to: SubtitleParser.SubtitleFormat
    ): String

    fun readFile(file: File, charset: Charset? = null): String

    fun readUri(context: Context, uri: Uri, charset: Charset? = null): String

    fun writeFile(file: File, content: String, charset: Charset)

    fun writeUri(context: Context, uri: Uri, content: String, charset: Charset)
}
