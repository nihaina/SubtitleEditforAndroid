package com.subtitleedit.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subtitleedit.R
import kotlin.math.roundToInt

/** Audio lyric typography changes stay in the subtitle sheet and apply immediately. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerAudioSubtitleLayoutControls(
    lineSpacing: Int,
    alignment: PlayerSubtitleAlignment,
    onLineSpacingChange: (Int) -> Unit,
    onAlignmentChange: (PlayerSubtitleAlignment) -> Unit
) {
    Column(Modifier.fillMaxWidth().testTag("player-audio-subtitle-layout")) {
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.player_audio_subtitle_line_spacing, lineSpacing),
            color = PlayerColors.OnDarkVariant,
            fontSize = 13.sp
        )
        Slider(
            value = lineSpacing.toFloat(),
            onValueChange = { onLineSpacingChange(it.roundToInt()) },
            valueRange = PlayerPreferences.MIN_AUDIO_SUBTITLE_LINE_SPACING.toFloat()..
                PlayerPreferences.MAX_AUDIO_SUBTITLE_LINE_SPACING.toFloat(),
            steps = PlayerPreferences.MAX_AUDIO_SUBTITLE_LINE_SPACING -
                PlayerPreferences.MIN_AUDIO_SUBTITLE_LINE_SPACING - 1,
            modifier = Modifier.fillMaxWidth().testTag("player-subtitle-line-spacing"),
            colors = SliderDefaults.colors(
                thumbColor = PlayerColors.Accent,
                activeTrackColor = PlayerColors.Accent,
                inactiveTrackColor = PlayerColors.SurfaceHigh
            )
        )
        Text(
            stringResource(R.string.player_audio_subtitle_alignment),
            color = PlayerColors.OnDarkVariant,
            fontSize = 13.sp
        )
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            PlayerSubtitleAlignment.entries.forEachIndexed { index, item ->
                val label = stringResource(when (item) {
                    PlayerSubtitleAlignment.LEFT -> R.string.player_audio_subtitle_align_left
                    PlayerSubtitleAlignment.CENTER -> R.string.player_audio_subtitle_align_center
                    PlayerSubtitleAlignment.RIGHT -> R.string.player_audio_subtitle_align_right
                })
                val drawable = when (item) {
                    PlayerSubtitleAlignment.LEFT -> R.drawable.ic_player_align_left
                    PlayerSubtitleAlignment.CENTER -> R.drawable.ic_player_align_center
                    PlayerSubtitleAlignment.RIGHT -> R.drawable.ic_player_align_right
                }
                SegmentedButton(
                    selected = alignment == item,
                    onClick = { onAlignmentChange(item) },
                    shape = SegmentedButtonDefaults.itemShape(index, PlayerSubtitleAlignment.entries.size),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("player-subtitle-align:${item.name}"),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = PlayerColors.Accent.copy(alpha = 0.12f),
                        activeContentColor = PlayerColors.Accent,
                        activeBorderColor = PlayerColors.Accent,
                        inactiveContainerColor = PlayerColors.Surface,
                        inactiveContentColor = PlayerColors.OnDarkVariant,
                        inactiveBorderColor = PlayerColors.OnDarkVariant.copy(alpha = 0.38f)
                    ),
                    icon = {}
                ) {
                    Icon(painterResource(drawable), contentDescription = label, modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}
