package com.subtitleedit.player

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.subtitleedit.AppComposeActivity
import com.subtitleedit.R
import com.subtitleedit.editor.EditorColorPickerDialog
import com.subtitleedit.ui.settings.ModelSettingsScaffold
import com.subtitleedit.ui.settings.ModelSettingsSliderTile
import com.subtitleedit.ui.settings.SettingsTileGroup
import com.subtitleedit.ui.settings.SettingsTileRow
import com.subtitleedit.ui.settings.SettingsTileSwitchRow
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import com.subtitleedit.util.SubtitleColorOps
import kotlin.math.roundToInt

class PlayerSubtitleSettingsActivity : AppComposeActivity() {
    private val preferences by lazy { PlayerPreferences.getInstance(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val showLyrics by preferences.notificationLyricsChanges.collectAsStateWithLifecycle(
                initialValue = preferences.notificationsShowLyrics
            )
            SubtitleEditComposeTheme {
                ModelSettingsScaffold(stringResource(R.string.player_subtitle_settings_title), ::navigateBack) {
                    SettingsTileGroup {
                        SettingsTileRow(
                            title = stringResource(R.string.player_floating_subtitle_title),
                            iconRes = R.drawable.ic_subtitle_format,
                            onClick = ::openFloatingSubtitleSettings,
                        )
                        SettingsTileRow(
                            title = stringResource(R.string.player_status_bar_lyrics_title),
                            iconRes = R.drawable.ic_subtitle_format,
                            onClick = ::openStatusBarLyricsSettings,
                        )
                        SettingsTileSwitchRow(
                            title = stringResource(R.string.player_notification_lyrics_title),
                            description = stringResource(R.string.player_notification_lyrics_description),
                            checked = showLyrics,
                            onCheckedChange = { preferences.notificationsShowLyrics = it },
                        )
                    }
                }
            }
        }
    }

    private fun openFloatingSubtitleSettings() {
        startActivity(Intent(this, PlayerFloatingSubtitleSettingsActivity::class.java))
    }

    private fun openStatusBarLyricsSettings() {
        startActivity(Intent(this, PlayerStatusBarLyricsSettingsActivity::class.java))
    }
}

class PlayerFloatingSubtitleSettingsActivity : AppComposeActivity() {
    private val preferences by lazy { PlayerPreferences.getInstance(this) }
    private val overlayPermission by lazy {
        OverlaySubtitlePermission(this,
            isEnabled = { preferences.floatingSubtitleEnabled },
            setEnabled = { preferences.floatingSubtitleEnabled = it },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overlayPermission.restore(savedInstanceState)
        setContent {
            val style by preferences.floatingSubtitleStyleChanges.collectAsStateWithLifecycle(
                initialValue = preferences.floatingSubtitleStyle
            )
            val enabled by preferences.floatingSubtitleEnabledChanges.collectAsStateWithLifecycle(
                initialValue = preferences.floatingSubtitleEnabled
            )
            val locked by preferences.floatingSubtitleLockedChanges.collectAsStateWithLifecycle(
                initialValue = preferences.floatingSubtitleLocked
            )
            SubtitleEditComposeTheme {
                PlayerFloatingSubtitleSettings(
                    style = style,
                    enabled = enabled && overlayPermission.granted,
                    locked = locked,
                    onBack = ::navigateBack,
                    onEnabledChanged = overlayPermission::onCheckedChange,
                    onLockedChanged = { preferences.floatingSubtitleLocked = it },
                    onUpdateStyle = { update ->
                        preferences.floatingSubtitleStyle = update(preferences.floatingSubtitleStyle)
                    },
                    onPickColor = { target ->
                        showFloatingSubtitleColorPicker(
                            context = this@PlayerFloatingSubtitleSettingsActivity,
                            color = preferences.floatingSubtitleStyle.colorFor(target),
                            background = target == PlayerFloatingSubtitleColor.BACKGROUND,
                            outline = target == PlayerFloatingSubtitleColor.OUTLINE,
                        ) { color ->
                            preferences.floatingSubtitleStyle = preferences.floatingSubtitleStyle.withColor(target, color)
                        }
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        overlayPermission.onResume()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        overlayPermission.save(outState)
        super.onSaveInstanceState(outState)
    }
}

class PlayerStatusBarLyricsSettingsActivity : AppComposeActivity() {
    private val preferences by lazy { PlayerPreferences.getInstance(this) }
    private val overlayPermission by lazy {
        OverlaySubtitlePermission(this,
            isEnabled = { preferences.statusBarLyrics.enabled },
            setEnabled = { preferences.statusBarLyrics = preferences.statusBarLyrics.copy(enabled = it) },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overlayPermission.restore(savedInstanceState)
        setContent {
            val settings by preferences.statusBarLyricsChanges.collectAsStateWithLifecycle(
                initialValue = preferences.statusBarLyrics,
            )
            SubtitleEditComposeTheme {
                ModelSettingsScaffold(stringResource(R.string.player_status_bar_lyrics_title), ::navigateBack) {
                    SettingsTileGroup {
                        SettingsTileSwitchRow(
                            title = stringResource(R.string.player_status_bar_lyrics_enabled),
                            description = stringResource(R.string.player_status_bar_lyrics_description),
                            checked = settings.enabled && overlayPermission.granted,
                            onCheckedChange = overlayPermission::onCheckedChange,
                        )
                    }
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        PlayerFloatingSubtitle(
                            text = stringResource(R.string.player_status_bar_lyrics_preview),
                            style = settings.style,
                            modifier = Modifier.fillMaxWidth(settings.widthPercent / 100f),
                        )
                    }
                    SettingsTileGroup {
                        Box(Modifier.testTag("status-bar-lyrics-vertical-position")) {
                            ModelSettingsSliderTile(
                                title = stringResource(R.string.player_status_bar_lyrics_vertical_position),
                                description = stringResource(R.string.player_status_bar_lyrics_vertical_position_description),
                                value = settings.verticalPositionPercent.toFloat(),
                                valueLabel = stringResource(R.string.player_floating_subtitle_percent, settings.verticalPositionPercent),
                                onValueChange = { percent ->
                                    preferences.statusBarLyrics = preferences.statusBarLyrics.copy(verticalPositionPercent = percent.roundToInt())
                                },
                                valueRange = 0f..100f,
                            )
                        }
                        Box(Modifier.testTag("status-bar-lyrics-width")) {
                            ModelSettingsSliderTile(
                                title = stringResource(R.string.player_status_bar_lyrics_width),
                                value = settings.widthPercent.toFloat(),
                                valueLabel = stringResource(R.string.player_floating_subtitle_percent, settings.widthPercent),
                                onValueChange = { percent ->
                                    val current = preferences.statusBarLyrics
                                    preferences.statusBarLyrics = current.copy(widthPercent = percent.roundToInt())
                                },
                                valueRange = PlayerStatusBarLyricsSettings.MIN_WIDTH_PERCENT.toFloat()..100f,
                            )
                        }
                    }
                    SubtitleAppearanceSettings(
                        style = settings.style,
                        onUpdateStyle = ::updateStyle,
                        onPickColor = { target ->
                            showFloatingSubtitleColorPicker(
                                context = this@PlayerStatusBarLyricsSettingsActivity,
                                color = preferences.statusBarLyrics.style.colorFor(target),
                                outline = target == PlayerFloatingSubtitleColor.OUTLINE,
                            ) { color -> updateStyle { it.withColor(target, color) } }
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        overlayPermission.onResume()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        overlayPermission.save(outState)
        super.onSaveInstanceState(outState)
    }

    private fun updateStyle(update: (PlayerFloatingSubtitleStyle) -> PlayerFloatingSubtitleStyle) {
        val current = preferences.statusBarLyrics
        preferences.statusBarLyrics = current.copy(style = update(current.style))
    }
}

private class OverlaySubtitlePermission(
    private val activity: AppComposeActivity,
    private val isEnabled: () -> Boolean,
    private val setEnabled: (Boolean) -> Unit,
) {
    var granted by mutableStateOf(false)
        private set
    private var pending = false

    fun restore(state: Bundle?) {
        pending = state?.getBoolean(STATE_AWAITING_OVERLAY_PERMISSION) ?: false
        granted = Settings.canDrawOverlays(activity)
    }

    fun save(state: Bundle) {
        state.putBoolean(STATE_AWAITING_OVERLAY_PERMISSION, pending)
    }

    fun onResume() {
        granted = Settings.canDrawOverlays(activity)
        if (pending) {
            pending = false
            setEnabled(granted)
        } else if (!granted && isEnabled()) {
            setEnabled(false)
        }
    }

    fun onCheckedChange(enabled: Boolean) {
        if (pending) return
        granted = Settings.canDrawOverlays(activity)
        if (!enabled || granted) {
            setEnabled(enabled)
            return
        }
        pending = true
        val appUri = Uri.fromParts("package", activity.packageName, null)
        if (!openSettings(appUri) && !openSettings(null)) {
            pending = false
            Toast.makeText(activity, R.string.player_floating_subtitle_permission_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openSettings(uri: Uri?): Boolean = try {
        activity.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, uri))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private companion object {
        const val STATE_AWAITING_OVERLAY_PERMISSION = "awaitingOverlayPermission"
    }
}

internal fun showFloatingSubtitleColorPicker(
    context: Context,
    color: Int,
    windowType: Int? = null,
    background: Boolean = false,
    outline: Boolean = false,
    onConfirm: (Int) -> Unit,
): AlertDialog = EditorColorPickerDialog(context).show(
    initialColor = color,
    recentColors = if (outline) listOf(Color.BLACK, Color.WHITE, Color.YELLOW, Color.LTGRAY)
        else listOf(Color.WHITE, Color.YELLOW, Color.CYAN, Color.LTGRAY),
    windowType = windowType,
    title = when {
        background -> context.getText(R.string.player_floating_subtitle_background_color)
        outline -> context.getText(R.string.player_floating_subtitle_outline_color)
        else -> null
    },
    onConfirm = onConfirm,
)

@Composable
private fun PlayerFloatingSubtitleSettings(
    style: PlayerFloatingSubtitleStyle,
    enabled: Boolean,
    locked: Boolean,
    onBack: () -> Unit,
    onEnabledChanged: (Boolean) -> Unit,
    onLockedChanged: (Boolean) -> Unit,
    onUpdateStyle: ((PlayerFloatingSubtitleStyle) -> PlayerFloatingSubtitleStyle) -> Unit,
    onPickColor: (PlayerFloatingSubtitleColor) -> Unit,
) {
    ModelSettingsScaffold(stringResource(R.string.player_floating_subtitle_adjustments), onBack) {
        SettingsTileGroup {
            SettingsTileSwitchRow(
                title = stringResource(R.string.player_floating_subtitle_title),
                description = stringResource(R.string.player_floating_subtitle_description),
                checked = enabled,
                onCheckedChange = onEnabledChanged,
            )
            SettingsTileSwitchRow(
                title = stringResource(R.string.player_floating_subtitle_lock),
                description = stringResource(R.string.player_floating_subtitle_lock_description),
                checked = locked,
                enabled = enabled,
                onCheckedChange = onLockedChanged,
            )
        }
        PlayerFloatingSubtitle(
            text = stringResource(R.string.player_floating_subtitle_preview),
            style = style,
            modifier = Modifier.fillMaxWidth(),
        )
        SubtitleAppearanceSettings(style, onUpdateStyle, onPickColor)
        SettingsTileGroup {
            SettingsTileRow(
                title = stringResource(R.string.player_floating_subtitle_background_color),
                value = SubtitleColorOps.toRgbHex(style.backgroundColorArgb),
                onClick = { onPickColor(PlayerFloatingSubtitleColor.BACKGROUND) },
            )
            Box(Modifier.testTag("floating-subtitle-background-opacity")) {
                ModelSettingsSliderTile(
                    title = stringResource(R.string.player_floating_subtitle_background_opacity),
                    value = style.backgroundOpacityPercent.toFloat(),
                    valueLabel = stringResource(R.string.player_floating_subtitle_percent, style.backgroundOpacityPercent),
                    onValueChange = { value ->
                        val percent = value.roundToInt()
                        onUpdateStyle { if (it.backgroundOpacityPercent == percent) it else it.withBackgroundOpacity(percent) }
                    },
                    valueRange = 0f..100f,
                )
            }
        }
    }
}

@Composable
private fun SubtitleAppearanceSettings(
    style: PlayerFloatingSubtitleStyle,
    onUpdateStyle: ((PlayerFloatingSubtitleStyle) -> PlayerFloatingSubtitleStyle) -> Unit,
    onPickColor: (PlayerFloatingSubtitleColor) -> Unit,
) {
    SettingsTileGroup {
        Box(Modifier.testTag("floating-subtitle-size")) {
            val minSize = PlayerPreferences.MIN_SUBTITLE_FONT_SIZE.toFloat()
            val maxSize = PlayerPreferences.MAX_SUBTITLE_FONT_SIZE.toFloat()
            ModelSettingsSliderTile(
                title = stringResource(R.string.vtt_style_font_size),
                value = style.fontSize.toFloat(),
                valueLabel = stringResource(R.string.player_floating_subtitle_font_size_value, style.fontSize),
                onValueChange = { value -> onUpdateStyle { it.copy(fontSize = value.roundToInt()) } },
                valueRange = minSize..maxSize,
            )
        }
        Box(Modifier.testTag("floating-subtitle-left-margin")) {
            ModelSettingsSliderTile(
                title = stringResource(R.string.player_floating_subtitle_left_margin),
                value = style.leftMarginPercent.toFloat(),
                valueLabel = stringResource(R.string.player_floating_subtitle_percent, style.leftMarginPercent),
                onValueChange = { value -> onUpdateStyle { it.copy(leftMarginPercent = value.roundToInt()) } },
                valueRange = 0f..100f,
            )
        }
        SubtitleStyleOptions(
            title = stringResource(R.string.player_floating_subtitle_font_weight),
            selected = style.weight,
            options = PlayerFloatingSubtitleWeight.entries,
            label = { weight ->
                stringResource(when (weight) {
                    PlayerFloatingSubtitleWeight.LIGHT -> R.string.player_floating_subtitle_weight_light
                    PlayerFloatingSubtitleWeight.NORMAL -> R.string.player_floating_subtitle_weight_normal
                    PlayerFloatingSubtitleWeight.MEDIUM -> R.string.player_floating_subtitle_weight_medium
                    PlayerFloatingSubtitleWeight.BOLD -> R.string.player_floating_subtitle_weight_bold
                })
            },
            onSelected = { weight -> onUpdateStyle { it.copy(weight = weight) } },
        )
        SettingsTileRow(
            title = stringResource(R.string.player_floating_subtitle_font_color),
            value = "#${SubtitleColorOps.toArgbHex(style.colorArgb)}",
            onClick = { onPickColor(PlayerFloatingSubtitleColor.TEXT) },
        )
        SettingsTileRow(
            title = stringResource(R.string.player_floating_subtitle_outline_color),
            value = "#${SubtitleColorOps.toArgbHex(style.outlineColorArgb)}",
            onClick = { onPickColor(PlayerFloatingSubtitleColor.OUTLINE) },
        )
        SettingsTileSwitchRow(
            title = stringResource(R.string.player_floating_subtitle_scroll),
            description = stringResource(R.string.player_floating_subtitle_scroll_description),
            checked = style.scrollEnabled,
            onCheckedChange = { scroll -> onUpdateStyle { it.copy(scrollEnabled = scroll) } },
        )
    }
}

@Composable
private fun <T> SubtitleStyleOptions(
    title: String,
    selected: T,
    options: List<T>,
    label: @Composable (T) -> String,
    onSelected: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        SettingsTileRow(title = title, value = label(selected), onClick = { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(label(option)) },
                    onClick = {
                        expanded = false
                        onSelected(option)
                    },
                )
            }
        }
    }
}
