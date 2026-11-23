package com.subtitleedit.mpv

import android.content.Context
import com.subtitleedit.util.SettingsManager

internal object MpvVideoDecoding {
    fun configure(context: Context) {
        MPVLib.setOptionString("hwdec", option(context))
        MPVLib.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
    }

    fun refresh(context: Context) {
        val requested = option(context)
        if (MPVLib.getPropertyString("hwdec") != requested) {
            MPVLib.setPropertyString("hwdec", requested)
        }
    }

    private fun option(context: Context): String =
        if (SettingsManager.getInstance(context).isHardwareDecodingEnabled()) "mediacodec-copy,mediacodec" else "no"
}
