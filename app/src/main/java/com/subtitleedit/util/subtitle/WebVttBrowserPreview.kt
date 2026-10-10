package com.subtitleedit.util.subtitle

import android.net.Uri
import java.io.File
import java.util.Base64
import java.util.Locale

/** Builds the throw-away HTML page used by the VTT browser preview action. */
internal object WebVttBrowserPreview {
    fun isSupportedVideoFile(fileName: String?): Boolean {
        val extension = fileName?.substringAfterLast('.', "")?.lowercase(Locale.US) ?: return false
        return extension in setOf("mp4", "m4v", "webm", "ogv", "ogg", "mov")
    }

    /**
     * The subtitle is embedded as a data URI. A browser will otherwise reject a track loaded
     * from a second local file because the preview page and the track have different origins.
     */
    fun generateHtml(webVttText: String, videoUrl: String, videoFileName: String? = null): String {
        val subtitleBase64 = Base64.getEncoder().encodeToString(webVttText.toByteArray(Charsets.UTF_8))
        val extension = videoFileName?.substringAfterLast('.', "")?.lowercase(Locale.US)
        val mimeType = when (extension) {
            "webm" -> "video/webm"
            "ogv", "ogg" -> "video/ogg"
            else -> "video/mp4"
        }
        val escapedVideoUrl = escapeAttribute(videoUrl)
        return """
            <!doctype html>
            <html lang="zh-CN">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1">
              <title>WebVTT preview</title>
              <style>
                :root { color-scheme: dark; }
                body { background:#202020; color:#eee; font-family:sans-serif; margin:0; padding:1em; }
                video { display:block; max-width:100%; width:100%; }
              </style>
            </head>
            <body>
              <video controls preload="metadata">
                <source src="$escapedVideoUrl" type="$mimeType">
                <track label="WebVTT" kind="subtitles" srclang="zh" src="data:text/vtt;base64,$subtitleBase64" default>
              </video>
            </body>
            </html>
        """.trimIndent()
    }

    fun fileUri(file: File): String = runCatching { Uri.fromFile(file).toString() }
        .getOrElse { "file://${file.absolutePath}" }

    private fun escapeAttribute(value: String): String = value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
