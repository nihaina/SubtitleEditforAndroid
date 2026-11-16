package com.subtitleedit.player

import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View

/** Ordinary changes use a short, low-amplitude pulse; boundaries retain the existing feedback. */
internal class PlayerVolumeHaptics(private val view: View) {
    @Suppress("DEPRECATION")
    private val vibrator = if (Build.VERSION.SDK_INT >= 31) {
        view.context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else view.context.getSystemService(Vibrator::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()

    fun perform(feedback: PlayerVolumeFeedback) {
        when (feedback) {
            PlayerVolumeFeedback.NONE -> Unit
            PlayerVolumeFeedback.BOUNDARY -> view.performHapticFeedback(
                if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
                else HapticFeedbackConstants.CLOCK_TICK
            )
            PlayerVolumeFeedback.LIGHT -> {
                if (!view.isHapticFeedbackEnabled || Settings.System.getInt(view.context.contentResolver,
                        Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0) return
                val motor = vibrator?.takeIf { it.hasVibrator() } ?: return
                // Avoid a full-amplitude substitute on motors that cannot honor a light pulse.
                if (!motor.hasAmplitudeControl()) return
                try {
                    motor.vibrate(VibrationEffect.createOneShot(6L, 24), attributes)
                } catch (_: SecurityException) {
                    // Device policy may disable direct vibration; thresholds still use View feedback.
                }
            }
        }
    }
}
