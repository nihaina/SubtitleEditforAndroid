package com.subtitleedit.player

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subtitleedit.R
import com.subtitleedit.library.MediaLibraryTrack
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerPanelSheet(
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    onPickSubtitle: () -> Unit = {}
) {
    if (state.panel == PlayerPanel.NONE) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scrollState = rememberScrollState()
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.9f
    val scope = rememberCoroutineScope()
    // Sheet-level gestures stay off so lists scroll freely, so the handle drives its own drag.
    var handleDrag by remember { mutableFloatStateOf(0f) }
    val dismissThreshold = with(LocalDensity.current) { 96.dp.toPx() }
    LaunchedEffect(state.panel) { handleDrag = 0f }
    fun settleOrDismiss() {
        suspend fun unwind(durationMillis: Int) {
            animate(initialValue = handleDrag, targetValue = 0f, animationSpec = tween(durationMillis)) { value: Float, _: Float ->
                handleDrag = value
            }
        }
        scope.launch {
            if (handleDrag > dismissThreshold) {
                // Unwind the handle offset while the sheet plays its own hide animation, so it leaves
                // from where the finger released it instead of snapping back first.
                launch { unwind(220) }
                sheetState.hide()
                if (sheetState.currentValue == SheetValue.Hidden) viewModel.closePanel()
            } else {
                unwind(180)
            }
        }
    }
    ModalBottomSheet(
        onDismissRequest = viewModel::closePanel,
        sheetState = sheetState,
        modifier = Modifier.testTag("player-panel-surface")
            .semantics { testTagsAsResourceId = true }
            .graphicsLayer { translationY = handleDrag },
        // Keep the expanded panel stationary; only its list handles vertical scroll/flings.
        // Back, the scrim and the handle still dismiss the menu.
        sheetGesturesEnabled = false,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        containerColor = PlayerColors.Surface,
        contentColor = PlayerColors.OnDark,
        dragHandle = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 28.dp)
                    .testTag("player-panel-handle")
                    .pointerInput(sheetState) {
                        detectVerticalDragGestures(
                            onDragStart = { handleDrag = 0f },
                            onDragEnd = { settleOrDismiss() },
                            onDragCancel = { settleOrDismiss() },
                            onVerticalDrag = { change, amount ->
                                change.consume()
                                handleDrag = (handleDrag + amount).coerceAtLeast(0f)
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(42.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(PlayerColors.SurfaceHigh)
                )
            }
        }
    ) {
        PlayerPanelImmersiveWindow()
        val contentModifier = Modifier.heightIn(max = maxHeight - 24.dp)
            .testTag("player-panel-content")
            .padding(horizontal = 20.dp).padding(bottom = 28.dp)
        Column(
            if (state.panel == PlayerPanel.PLAYLIST || state.panel == PlayerPanel.BOOKMARK) contentModifier
            else contentModifier.verticalScroll(scrollState)
        ) {
            if (state.panel != PlayerPanel.PLAYLIST) {
                Text(
                    stringResource(panelTitle(state.panel)),
                    color = PlayerColors.OnDark,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(bottom = 14.dp)
                )
            }
            when (state.panel) {
                PlayerPanel.PLAYLIST -> PlayerPlaylistPanel(state, viewModel)
                PlayerPanel.EQUALIZER -> PlayerEqualizerPanel(state, viewModel)
                PlayerPanel.SUBTITLE -> PlayerSubtitlePanel(state, viewModel, onPickSubtitle)
                PlayerPanel.BOOKMARK -> PlayerBookmarkPanel(state, viewModel)
                PlayerPanel.TIMER -> PlayerTimerPanel(state, viewModel)
                PlayerPanel.SPEED -> PlayerSpeedPanel(state, viewModel)
                PlayerPanel.SETTINGS -> PlayerSettingsPanel(state, viewModel)
                PlayerPanel.NONE -> Unit
            }
        }
    }
}

private fun panelTitle(panel: PlayerPanel): Int = when (panel) {
    PlayerPanel.PLAYLIST -> R.string.player_panel_playlist
    PlayerPanel.EQUALIZER -> R.string.player_panel_equalizer
    PlayerPanel.SUBTITLE -> R.string.player_panel_subtitle
    PlayerPanel.BOOKMARK -> R.string.player_panel_bookmark
    PlayerPanel.TIMER -> R.string.player_panel_timer
    PlayerPanel.SPEED -> R.string.player_panel_speed
    else -> R.string.player_panel_settings
}

@Composable
private fun PlayerPlaylistPanel(state: PlayerUiState, viewModel: PlayerViewModel) {
    val actions = rememberPlaybackQueueLibraryActions(immersive = true)
    val tracks = remember(state.playlist, state.currentTrack?.path, state.durationMs) {
        state.playlist.map { track ->
            MediaLibraryTrack(track.path, track.name, isVideo = track.isVideo,
                durationMs = if (track.path == state.currentTrack?.path) state.durationMs else 0L)
        }
    }
    PlaybackQueueContent(
        tracks = tracks,
        currentPath = state.currentTrack?.path,
        playing = state.playing,
        onPlay = { entryId ->
            viewModel.playQueueEntry(entryId)
            viewModel.closePanel()
        },
        onMove = viewModel::moveQueueEntry,
        onRemove = viewModel::removeQueueTracks,
        onFavorite = actions.favorite,
        onAddToPlaylist = actions.addToPlaylist,
        onShowInfo = actions.showInfo,
        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
        queueVersion = state.queueVersion,
        tagPrefix = "player-queue",
        overlaySelectionScope = true,
        entryIds = state.playlist.map { it.queueEntryId },
        currentEntryId = state.currentTrack?.queueEntryId,
        onRemoveEntry = { viewModel.removeQueueEntries(setOf(it)) }
    ) { sortButton ->
        Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.player_panel_playlist), color = PlayerColors.OnDark, fontSize = 18.sp,
                modifier = Modifier.weight(1f))
            sortButton()
        }
    }
}

@Composable
private fun PlayerEqualizerPanel(state: PlayerUiState, viewModel: PlayerViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.player_eq_enable), color = PlayerColors.OnDark, fontSize = 15.sp,
            modifier = Modifier.weight(1f))
        Switch(
            checked = state.equalizer.enabled,
            onCheckedChange = { viewModel.setEqualizer(state.equalizer.copy(enabled = it)) },
            colors = SwitchDefaults.colors(
                checkedThumbColor = PlayerColors.Background,
                checkedTrackColor = PlayerColors.Accent,
                uncheckedThumbColor = PlayerColors.OnDarkVariant,
                uncheckedTrackColor = PlayerColors.SurfaceHigh
            )
        )
    }
    Spacer(Modifier.height(10.dp))
    PlayerEqualizer.PRESETS.chunked(3).forEach { presets ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            presets.forEach { preset ->
                Box(Modifier.weight(1f)) {
                    PlayerChip(
                        text = stringResource(presetLabel(preset.id)),
                        selected = state.equalizer.gains == preset.gains,
                        onClick = { viewModel.applyPreset(preset) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
    Spacer(Modifier.height(14.dp))
    PlayerEqualizer.FREQUENCIES.forEachIndexed { index, frequency ->
        val gain = state.equalizer.gains.getOrNull(index) ?: 0f
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                PlayerEqualizer.formatFrequency(frequency),
                color = PlayerColors.OnDarkVariant,
                fontSize = 12.sp,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.width(36.dp)
            )
            PlayerEqualizerBandSlider(
                gain = gain,
                enabled = state.equalizer.enabled,
                onGainChange = { viewModel.setBandGain(index, it) },
                modifier = Modifier.weight(1f)
            )
            // Fixed width and a single line, so every band keeps the same track length however the value reads.
            Text(
                PlayerEqualizer.formatGain(gain),
                color = if (gain == 0f) PlayerColors.OnDarkVariant else PlayerColors.Accent,
                fontSize = 12.sp,
                maxLines = 1,
                softWrap = false,
                textAlign = TextAlign.End,
                modifier = Modifier.width(56.dp)
            )
        }
    }
}

/** Zero-centred band slider: the fill runs from the 0 dB mark to the thumb, so cut and boost read differently. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerEqualizerBandSlider(
    gain: Float,
    enabled: Boolean,
    onGainChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val activeColor = if (enabled) PlayerColors.Accent else PlayerColors.OnDarkVariant
    val trackColor = PlayerColors.SurfaceHigh
    Slider(
        value = gain,
        onValueChange = onGainChange,
        valueRange = PlayerEqualizer.MIN_GAIN..PlayerEqualizer.MAX_GAIN,
        enabled = enabled,
        colors = SliderDefaults.colors(
            thumbColor = PlayerColors.Accent,
            disabledThumbColor = PlayerColors.OnDarkVariant
        ),
        thumb = {
            Box(
                Modifier.size(20.dp).clip(CircleShape).background(if (enabled) PlayerColors.Accent else PlayerColors.OnDarkVariant)
            )
        },
        track = { sliderState ->
            Box(
                Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(trackColor)
                    .drawBehind {
                        val center = size.width / 2f
                        val x = size.width * sliderState.coercedValueAsFraction
                        drawRect(
                            color = activeColor,
                            topLeft = Offset(minOf(x, center), 0f),
                            size = Size(kotlin.math.abs(x - center), size.height)
                        )
                    }
            )
        },
        modifier = modifier
    )
}

private fun presetLabel(id: String): Int = when (id) {
    "flat" -> R.string.player_preset_flat
    "bass" -> R.string.player_preset_bass
    "vocal" -> R.string.player_preset_vocal
    "treble" -> R.string.player_preset_treble
    "asmr" -> R.string.player_preset_asmr
    else -> R.string.player_preset_night
}

@Composable
private fun PlayerSubtitlePanel(state: PlayerUiState, viewModel: PlayerViewModel, onPickSubtitle: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onPickSubtitle).heightIn(min = 48.dp)
                .testTag("player-subtitle-pick-file").padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(painterResource(R.drawable.ic_folder), contentDescription = null,
                modifier = Modifier.size(18.dp), tint = PlayerColors.Accent)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.player_subtitle_pick_file), color = PlayerColors.Accent, fontSize = 14.sp)
        }
        state.subtitleOptions.forEach { option ->
            val selected = option.id == state.selectedSubtitleId
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) PlayerColors.SurfaceHigh else Color.Transparent)
                    .clickable { viewModel.selectSubtitle(option); viewModel.closePanel() }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_subtitle_format),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (selected) PlayerColors.Accent else PlayerColors.OnDarkVariant
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = if (option.kind == PlayerSubtitleKind.NONE) stringResource(R.string.player_subtitle_off)
                    else option.label,
                    color = if (selected) PlayerColors.Accent else PlayerColors.OnDark,
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
    Spacer(Modifier.height(14.dp))
    Text(
        stringResource(R.string.player_settings_subtitle_size, state.subtitleFontSize),
        color = PlayerColors.OnDarkVariant,
        fontSize = 13.sp
    )
    Slider(
        value = state.subtitleFontSize.toFloat(),
        onValueChange = { viewModel.setSubtitleFontSize(it.toInt()) },
        valueRange = PlayerPreferences.MIN_SUBTITLE_FONT_SIZE.toFloat()..PlayerPreferences.MAX_SUBTITLE_FONT_SIZE.toFloat(),
        steps = PlayerPreferences.MAX_SUBTITLE_FONT_SIZE - PlayerPreferences.MIN_SUBTITLE_FONT_SIZE - 1,
        colors = SliderDefaults.colors(
            thumbColor = PlayerColors.Accent,
            activeTrackColor = PlayerColors.Accent,
            inactiveTrackColor = PlayerColors.SurfaceHigh
        )
    )
    if (!state.showsVideo) {
        PlayerAudioSubtitleLayoutControls(
            lineSpacing = state.audioSubtitleLineSpacing,
            alignment = state.audioSubtitleAlignment,
            onLineSpacingChange = viewModel::setAudioSubtitleLineSpacing,
            onAlignmentChange = viewModel::setAudioSubtitleAlignment
        )
    }
}

@Composable
private fun PlayerBookmarkPanel(state: PlayerUiState, viewModel: PlayerViewModel) {
    PlayerChip(
        text = stringResource(R.string.player_bookmark_add),
        selected = false,
        onClick = viewModel::addBookmark
    )
    Spacer(Modifier.height(10.dp))
    if (state.bookmarks.isEmpty()) {
        Text(stringResource(R.string.player_bookmark_empty), color = PlayerColors.OnDarkVariant)
        return
    }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        itemsIndexed(state.bookmarks, key = { index, bookmark -> "$index:${bookmark.positionMs}" }) { _, bookmark ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { viewModel.seekTo(bookmark.positionMs); viewModel.closePanel() }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_player_bookmarks),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = PlayerColors.AccentSoft
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    formatPlaybackTime(bookmark.positionMs),
                    color = PlayerColors.OnDark,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { viewModel.removeBookmark(bookmark.positionMs) }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.delete),
                        modifier = Modifier.size(16.dp),
                        tint = PlayerColors.Danger
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerTimerPanel(state: PlayerUiState, viewModel: PlayerViewModel) {
    Text(stringResource(R.string.player_timer_stop_title), color = PlayerColors.OnDarkVariant, fontSize = 13.sp)
    Spacer(Modifier.height(6.dp))
    PlayerTimerDurationOptions(
        selectedMinutes = state.sleepTimer.durationMinutes.takeIf { state.sleepTimer.active },
        tag = "stop",
        onSelect = viewModel::setSleepCountdown
    )
    if (state.sleepTimer.active) {
        PlayerTimerStatus(state.sleepRemainingMs, viewModel::clearSleepTimer)
    }
    Spacer(Modifier.height(18.dp))
    Text(stringResource(R.string.player_timer_start_title), color = PlayerColors.OnDarkVariant, fontSize = 13.sp)
    Spacer(Modifier.height(6.dp))
    PlayerTimerDurationOptions(
        selectedMinutes = state.scheduledStartMinutes,
        tag = "start",
        onSelect = viewModel::scheduleStart
    )
    state.scheduledStartRemainingMs?.let { remaining ->
        PlayerTimerStatus(remaining, viewModel::cancelScheduledStart)
    }
}

@Composable
private fun PlayerTimerStatus(remainingMs: Long, onCancel: () -> Unit) {
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.player_timer_remaining, PlayerSleepTimerPolicy.formatRemaining(remainingMs)),
            color = PlayerColors.Accent,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onCancel) {
            Text(stringResource(R.string.player_timer_cancel), color = PlayerColors.OnDarkVariant)
        }
    }
}

@Composable
private fun PlayerTimerDurationOptions(selectedMinutes: Int?, tag: String, onSelect: (Int) -> Unit) {
    val presets = PlayerSleepTimerPolicy.COUNTDOWN_MINUTES
    var showCustom by remember { mutableStateOf(false) }
    var customMinutes by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }
    val parsedMinutes = PlayerSleepTimerPolicy.parseMinutes(customMinutes)
    val invalid = submitted && parsedMinutes == null

    (presets.map { it as Int? } + listOf(null)).chunked(3).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            row.forEach { minutes ->
                PlayerChip(
                    text = if (minutes == null) stringResource(R.string.player_timer_custom)
                        else stringResource(R.string.player_timer_countdown, minutes),
                    selected = if (minutes == null) selectedMinutes != null && selectedMinutes !in presets
                        else selectedMinutes == minutes,
                    onClick = {
                        if (minutes == null) {
                            customMinutes = selectedMinutes?.toString().orEmpty()
                            showCustom = true
                            submitted = false
                        } else {
                            showCustom = false
                            onSelect(minutes)
                        }
                    },
                    modifier = Modifier.weight(1f).testTag("player-timer-$tag-${minutes ?: "custom"}")
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
    if (showCustom) {
        val applyCustom = {
            submitted = true
            parsedMinutes?.let {
                onSelect(it)
                showCustom = false
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = customMinutes,
                onValueChange = { value ->
                    if (value.all(Char::isDigit) && value.length <= 10) customMinutes = value
                },
                label = { Text(stringResource(R.string.player_timer_custom_minutes)) },
                singleLine = true,
                isError = invalid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { applyCustom() }),
                supportingText = if (invalid) {
                    { Text(stringResource(R.string.player_timer_custom_invalid)) }
                } else null,
                modifier = Modifier.weight(1f).testTag("player-timer-$tag-input")
            )
            TextButton(onClick = { applyCustom() }) {
                Text(stringResource(R.string.player_timer_apply), color = PlayerColors.Accent)
            }
        }
    }
}

@Composable
private fun PlayerSpeedPanel(state: PlayerUiState, viewModel: PlayerViewModel) {
    PlayerPreferences.SPEED_STEPS.chunked(4).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            row.forEach { step ->
                PlayerChip(
                    text = "${step}x",
                    selected = state.speed == step,
                    onClick = { viewModel.setSpeed(step) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
    Spacer(Modifier.height(16.dp))
    Slider(
        value = state.speed,
        onValueChange = viewModel::setSpeed,
        valueRange = PlayerPreferences.MIN_SPEED..PlayerPreferences.MAX_SPEED,
        colors = SliderDefaults.colors(
            thumbColor = PlayerColors.Accent,
            activeTrackColor = PlayerColors.Accent,
            inactiveTrackColor = PlayerColors.SurfaceHigh
        )
    )
    Text(
        "${state.speed}x",
        color = PlayerColors.Accent,
        fontSize = 22.sp,
        modifier = Modifier.fillMaxWidth().wrapContentSize(Alignment.Center)
    )
}

@Composable
private fun PlayerSettingsPanel(state: PlayerUiState, viewModel: PlayerViewModel) {
    PlayerSettingsSwitch(
        title = stringResource(R.string.player_settings_resume),
        checked = state.resumePlayback,
        onCheckedChange = viewModel::setResumePlayback
    )
    if (!state.isVideo) {
        PlayerSettingsSwitch(
            title = stringResource(R.string.player_settings_loudness_waveform),
            checked = state.showLoudnessWaveform,
            onCheckedChange = viewModel::setShowLoudnessWaveform
        )
    }
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.player_settings_default_mode), color = PlayerColors.OnDarkVariant, fontSize = 13.sp)
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        val modes = if (state.endBehavior.enabled) PlayerPlayMode.simpleModes else PlayerPlayMode.entries
        modes.forEach { mode ->
            Box(Modifier.weight(1f)) {
                PlayerChip(
                    text = stringResource(modeLabel(mode)),
                    selected = state.mode == mode,
                    onClick = { viewModel.setMode(mode) },
                    modifier = Modifier.fillMaxWidth().testTag("player-settings-mode-${mode.name}")
                )
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    PlayerSettingsSwitch(
        title = stringResource(R.string.player_settings_advanced_options),
        checked = state.endBehavior.enabled,
        onCheckedChange = viewModel::setAdvancedPlaybackEnabled,
        switchModifier = Modifier.testTag("player-settings-advanced-options")
    )
}

@Composable
internal fun modeLabel(mode: PlayerPlayMode): Int = when (mode) {
    PlayerPlayMode.SEQUENCE -> R.string.player_mode_sequence
    PlayerPlayMode.REPEAT_ONE -> R.string.player_mode_repeat_one
    PlayerPlayMode.REPEAT_ALL -> R.string.player_mode_repeat_all
    PlayerPlayMode.SHUFFLE -> R.string.player_mode_shuffle
}

internal fun modeIcon(mode: PlayerPlayMode): Int = when (mode) {
    PlayerPlayMode.SEQUENCE -> R.drawable.ic_player_sequence
    PlayerPlayMode.REPEAT_ONE -> R.drawable.ic_player_repeat_one
    PlayerPlayMode.REPEAT_ALL -> R.drawable.ic_player_repeat
    PlayerPlayMode.SHUFFLE -> R.drawable.ic_player_shuffle
}

@Composable
private fun PlayerSettingsSwitch(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    switchModifier: Modifier = Modifier
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = PlayerColors.OnDark, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = switchModifier,
            colors = SwitchDefaults.colors(
                checkedThumbColor = PlayerColors.Background,
                checkedTrackColor = PlayerColors.Accent,
                uncheckedThumbColor = PlayerColors.OnDarkVariant,
                uncheckedTrackColor = PlayerColors.SurfaceHigh
            )
        )
    }
}

@Composable
internal fun PlayerChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isSelected = selected
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(if (selected) PlayerColors.Accent else PlayerColors.SurfaceHigh)
            .semantics { this.selected = isSelected }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (selected) PlayerColors.Background else PlayerColors.OnDark,
            fontSize = 12.sp,
            softWrap = false,
            maxLines = 1
        )
    }
}
