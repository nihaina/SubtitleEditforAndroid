package com.subtitleedit.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import com.subtitleedit.R

@Composable
internal fun rememberPlayerPictureInPicture(): Boolean {
    val activity = LocalActivity.current as? ComponentActivity
    var inPictureInPicture by remember(activity) { mutableStateOf(activity?.isInPictureInPictureMode == true) }
    DisposableEffect(activity) {
        val listener = Consumer<PictureInPictureModeChangedInfo> { inPictureInPicture = it.isInPictureInPictureMode }
        activity?.addOnPictureInPictureModeChangedListener(listener)
        inPictureInPicture = activity?.isInPictureInPictureMode == true
        onDispose { activity?.removeOnPictureInPictureModeChangedListener(listener) }
    }
    return inPictureInPicture
}

@Composable
internal fun rememberPlayerPictureInPictureAction(viewModel: PlayerViewModel): (() -> Unit)? {
    val activity = LocalActivity.current ?: return null
    val supported = remember(activity) { activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) }
    if (!supported) return null
    return remember(activity, viewModel) {
        {
            val state = viewModel.state.value
            if (state.showsVideo && state.ready && !activity.enterPlayerPictureInPicture()) {
                viewModel.hint(R.string.player_picture_in_picture_unavailable)
            }
        }
    }
}

private fun Activity.enterPlayerPictureInPicture(): Boolean {
    if (isFinishing || isDestroyed) return false
    val params = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
    return try {
        enterPictureInPictureMode(params)
    } catch (_: IllegalStateException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
