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
        EditorMpvPlaybackOptions.configure()
        MPVLib.setOptionString("hr-seek-framedrop", "yes")
        setVo("gpu-next")
        MPVLib.setOptionString("gpu-context", "android")
        MPVLib.setOptionString("opengl-es", "yes")
        MPVLib.setOptionString("hwdec", "mediacodec-copy,mediacodec")
        MPVLib.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
    }

    override fun postInitOptions() {
        EditorMpvPlaybackOptions.configureAfterInit()
    }

    override fun observeProperties() {
        EditorMpvPlaybackOptions.observe()
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
