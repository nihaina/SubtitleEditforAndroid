package com.subtitleedit.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.core.content.ContextCompat

/** Manages audio focus and stops playback on headphone disconnect in either playback mode. */
internal class PlayerAudioFocus(context: Context, private val onInterrupted: () -> Unit) {
    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private var focusRequest: AudioFocusRequest? = null
    private var registered = false
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) onInterrupted()
        }
    }
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    fun request(allowConcurrentPlayback: Boolean): Boolean {
        if (allowConcurrentPlayback) abandonFocus()
        else if (focusRequest == null && !requestFocus()) return false
        if (!registered) {
            ContextCompat.registerReceiver(app, noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
        }
        return true
    }

    fun updatePolicy(allowConcurrentPlayback: Boolean) {
        if (registered && !request(allowConcurrentPlayback)) onInterrupted()
    }

    private fun requestFocus(): Boolean {
        lateinit var request: AudioFocusRequest
        request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change ->
                if (focusRequest === request && change < 0) onInterrupted()
            }
            .build()
        if (audio.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
        focusRequest = request
        return true
    }

    private fun abandonFocus() {
        val request = focusRequest ?: return
        focusRequest = null
        audio.abandonAudioFocusRequest(request)
    }

    fun abandon() {
        abandonFocus()
        if (registered) {
            app.unregisterReceiver(noisyReceiver)
            registered = false
        }
    }
}
