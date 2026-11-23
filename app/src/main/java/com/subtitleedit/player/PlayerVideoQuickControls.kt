package com.subtitleedit.player

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.subtitleedit.R

private val QuickControlBackground = Color(0xDD1B1B1B)

@Composable
internal fun PlayerVideoQuickControls(
    pictureInPictureEnabled: Boolean,
    muted: Boolean,
    mirrored: Boolean,
    audioMode: Boolean,
    onPictureInPicture: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleMirror: () -> Unit,
    onToggleAudioMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier.testTag("player-video-quick-controls")) {
        FilledIconButton(
            onClick = onPictureInPicture,
            enabled = pictureInPictureEnabled,
            modifier = Modifier.testTag("player-picture-in-picture"),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = QuickControlBackground,
                contentColor = Color.White,
                disabledContainerColor = QuickControlBackground,
                disabledContentColor = Color.White.copy(alpha = 0.38f)
            )
        ) {
            QuickControlIcon(R.drawable.ic_player_picture_in_picture, R.string.player_picture_in_picture)
        }
        QuickControlToggle(
            checked = muted,
            onToggle = onToggleMute,
            icon = R.drawable.ic_player_muted,
            description = R.string.player_video_mute,
            modifier = Modifier.testTag("player-video-mute")
        )
        QuickControlToggle(
            checked = mirrored,
            onToggle = onToggleMirror,
            icon = R.drawable.ic_player_mirror,
            description = R.string.player_video_mirror,
            modifier = Modifier.testTag("player-video-mirror"),
            enabled = !audioMode
        )
        QuickControlToggle(
            checked = audioMode,
            onToggle = onToggleAudioMode,
            icon = R.drawable.ic_music_note,
            description = R.string.player_video_audio_mode,
            modifier = Modifier.testTag("player-video-audio-mode")
        )
    }
}

@Composable
private fun QuickControlToggle(
    checked: Boolean,
    onToggle: () -> Unit,
    @DrawableRes icon: Int,
    @StringRes description: Int,
    modifier: Modifier,
    enabled: Boolean = true
) {
    FilledIconToggleButton(
        checked = checked,
        onCheckedChange = { onToggle() },
        modifier = modifier,
        enabled = enabled,
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconToggleButtonColors(
            containerColor = QuickControlBackground,
            contentColor = Color.White,
            checkedContainerColor = Color.White,
            checkedContentColor = Color.Black
        )
    ) {
        QuickControlIcon(icon, description)
    }
}

@Composable
private fun QuickControlIcon(@DrawableRes icon: Int, @StringRes description: Int) {
    Icon(painterResource(icon), stringResource(description), Modifier.size(22.dp))
}
