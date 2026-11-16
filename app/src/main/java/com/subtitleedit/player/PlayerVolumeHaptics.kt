package com.subtitleedit.player

import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View

/** Ordinary changes use a perceptible short pulse; boundaries use the system's distinct tick. */
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
            PlayerVolumeFeedback.BOUNDARY -> view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            PlayerVolumeFeedback.LIGHT -> {
                if (!view.isHapticFeedbackEnabled || Settings.System.getInt(view.context.contentResolver,
                        Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0) return
                val motor = vibrator?.takeIf { it.hasVibrator() }
                if (motor == null) {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    return
                }
                try {
                    val amplitude = if (motor.hasAmplitudeControl()) 160 else VibrationEffect.DEFAULT_AMPLITUDE
                    motor.vibrate(VibrationEffect.createOneShot(18L, amplitude), attributes)
                } catch (_: SecurityException) {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
            }
        }
    }
}
