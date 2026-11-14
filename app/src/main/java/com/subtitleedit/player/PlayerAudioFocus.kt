package com.subtitleedit.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.core.content.ContextCompat

/** Pauses on competing audio or unplugged headphones; registration lasts only while playing. */
internal class PlayerAudioFocus(context: Context, private val onInterrupted: (Boolean) -> Unit) {
    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private var held = false
    private var registered = false
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) onInterrupted(true)
        }
    }
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setOnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) onInterrupted(true)
        }
        .build()

    fun request(): Boolean {
        if (held) return true
        held = audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (held && !registered) {
            ContextCompat.registerReceiver(app, noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
        }
        return held
    }

    fun abandon() {
        if (held) audio.abandonAudioFocusRequest(request)
        held = false
        if (registered) {
            app.unregisterReceiver(noisyReceiver)
            registered = false
        }
    }
}
