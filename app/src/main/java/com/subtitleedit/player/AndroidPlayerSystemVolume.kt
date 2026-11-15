package com.subtitleedit.player

import android.content.Context
import android.media.AudioManager
import android.os.Build

/** Uses the same media stream as Android's volume keys and system volume panel. */
internal class AndroidPlayerSystemVolume(context: Context) : PlayerSystemVolume {
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)

    override fun read(): SystemMediaVolume = SystemMediaVolume(
        index = audio.getStreamVolume(AudioManager.STREAM_MUSIC),
        minIndex = if (Build.VERSION.SDK_INT >= 28) audio.getStreamMinVolume(AudioManager.STREAM_MUSIC) else 0,
        maxIndex = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
        muted = audio.isStreamMute(AudioManager.STREAM_MUSIC),
    )

    override fun setIndex(index: Int) {
        if (audio.isVolumeFixed) return
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
        } catch (_: SecurityException) {
            // Device policy may block this request; the controller reads the actual setting back.
        }
    }
}
