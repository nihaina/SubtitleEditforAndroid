package com.subtitleedit.mpv

import android.content.Context
import android.util.AttributeSet
import java.io.File

internal class EditorMpvView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : BaseMPVView(context, attrs) {
    override fun initOptions() {
        writeFontConfig()
        MPVLib.setOptionString("profile", "fast")
        setVo("gpu-next")
        MPVLib.setOptionString("gpu-context", "android")
        MPVLib.setOptionString("opengl-es", "yes")
        MPVLib.setOptionString("hwdec", "mediacodec-copy,mediacodec")
        MPVLib.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
        MPVLib.setOptionString("ao", "audiotrack,opensles")
        MPVLib.setOptionString("audio-set-media-role", "yes")
        MPVLib.setOptionString("audio-display", "no")
        MPVLib.setOptionString("pause", "yes")
        MPVLib.setOptionString("osc", "no")
        MPVLib.setOptionString("input-default-bindings", "no")
        MPVLib.setOptionString("access-references", "no")
        MPVLib.setOptionString("load-unsafe-playlists", "no")
        MPVLib.setOptionString("sub-auto", "no")
        MPVLib.setOptionString("sid", "no")
        MPVLib.setOptionString("terminal", "no")
        MPVLib.setOptionString("msg-level", "all=warn")
        MPVLib.setOptionString("demuxer-max-bytes", (64 * 1024 * 1024).toString())
        MPVLib.setOptionString("demuxer-max-back-bytes", (32 * 1024 * 1024).toString())
    }

    override fun postInitOptions() {
        MPVLib.setOptionString("save-position-on-quit", "no")
        MPVLib.setOptionString("keep-open", "yes")
    }

    override fun observeProperties() {
        MPVLib.observeProperty("time-pos", MPVLib.MpvFormat.DOUBLE)
        MPVLib.observeProperty("duration/full", MPVLib.MpvFormat.DOUBLE)
        MPVLib.observeProperty("pause", MPVLib.MpvFormat.FLAG)
        MPVLib.observeProperty("eof-reached", MPVLib.MpvFormat.FLAG)
        MPVLib.observeProperty("track-list", MPVLib.MpvFormat.NONE)
    }

    private fun writeFontConfig() {
        val config = File(context.filesDir, "fonts.conf")
        val content = """
            <fontconfig>
              <dir>/system/fonts/</dir>
              <dir>/product/fonts/</dir>
              <cachedir>${context.cacheDir.absolutePath}</cachedir>
              <alias><family>sans-serif</family><prefer><family>Roboto</family><family>Noto Sans</family></prefer></alias>
              <alias><family>serif</family><prefer><family>Noto Serif</family></prefer></alias>
              <alias><family>monospace</family><prefer><family>Droid Sans Mono</family></prefer></alias>
            </fontconfig>
        """.trimIndent()
        if (!config.isFile || config.readText() != content) {
            config.writeText(content)
        }
    }
}
