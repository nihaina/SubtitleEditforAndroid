package com.subtitleedit.mpv

import android.content.Context
import android.util.AttributeSet
import com.subtitleedit.util.SubtitleFontManager

internal class EditorMpvView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : BaseMPVView(context, attrs) {
    override fun initOptions() {
        val fontDir = writeFontConfig()
        // Use the same system and shared local font files as the font picker.
        MPVLib.setOptionString("sub-font-provider", "fontconfig")
        MPVLib.setOptionString("sub-fonts-dir", fontDir.absolutePath)
        EditorMpvPlaybackOptions.configure()
        MPVLib.setOptionString("hr-seek-framedrop", "yes")
        setVo("gpu-next")
        MPVLib.setOptionString("gpu-context", "android")
        MPVLib.setOptionString("opengl-es", "yes")
        MpvVideoDecoding.configure(context)
    }

    override fun playFile(filePath: String) {
        MpvVideoDecoding.refresh(context)
        super.playFile(filePath)
    }

    override fun postInitOptions() {
        EditorMpvPlaybackOptions.configureAfterInit()
    }

    override fun observeProperties() {
        EditorMpvPlaybackOptions.observe()
    }

    private fun writeFontConfig() = SubtitleFontManager.writeFontConfig(context)
}
