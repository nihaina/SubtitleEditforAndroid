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
    private var overlayPermissionGranted by mutableStateOf(false)
    private var awaitingOverlayPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        awaitingOverlayPermission = savedInstanceState?.getBoolean(STATE_AWAITING_OVERLAY_PERMISSION) ?: false
        overlayPermissionGranted = Settings.canDrawOverlays(this)
        setContent {
            val showLyrics by preferences.notificationLyricsChanges.collectAsStateWithLifecycle(
                initialValue = preferences.notificationsShowLyrics
            )
            val floatingSubtitleEnabled by preferences.floatingSubtitleEnabledChanges.collectAsStateWithLifecycle(
                initialValue = preferences.floatingSubtitleEnabled
            )
            val floatingSubtitleLocked by preferences.floatingSubtitleLockedChanges.collectAsStateWithLifecycle(
                initialValue = preferences.floatingSubtitleLocked
            )
            val floatingSubtitleAvailable = floatingSubtitleEnabled && overlayPermissionGranted
            SubtitleEditComposeTheme {
                ModelSettingsScaffold(stringResource(R.string.player_subtitle_settings_title), ::navigateBack) {
                    SettingsTileGroup {
                        SettingsTileSwitchRow(
                            title = stringResource(R.string.player_floating_subtitle_title),
                            description = stringResource(R.string.player_floating_subtitle_description),
                            checked = floatingSubtitleAvailable,
                            onCheckedChange = ::setFloatingSubtitleEnabled,
                        )
                        SettingsTileRow(
                            title = stringResource(R.string.player_floating_subtitle_adjustments),
                            iconRes = R.drawable.ic_subtitle_format,
                            enabled = floatingSubtitleAvailable,
                            onClick = ::openFloatingSubtitleSettings,
                        )
                        SettingsTileSwitchRow(
                            title = stringResource(R.string.player_floating_subtitle_lock),
                            description = stringResource(R.string.player_floating_subtitle_lock_description),
                            checked = floatingSubtitleLocked,
                            enabled = floatingSubtitleAvailable,
                            onCheckedChange = { preferences.floatingSubtitleLocked = it },
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

    override fun onResume() {
        super.onResume()
        overlayPermissionGranted = Settings.canDrawOverlays(this)
        if (awaitingOverlayPermission) {
            awaitingOverlayPermission = false
            preferences.floatingSubtitleEnabled = overlayPermissionGranted
        } else if (!overlayPermissionGranted && preferences.floatingSubtitleEnabled) {
            preferences.floatingSubtitleEnabled = false
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_AWAITING_OVERLAY_PERMISSION, awaitingOverlayPermission)
        super.onSaveInstanceState(outState)
    }

    private fun setFloatingSubtitleEnabled(enabled: Boolean) {
        if (awaitingOverlayPermission) return
        overlayPermissionGranted = Settings.canDrawOverlays(this)
        if (!enabled || overlayPermissionGranted) {
            preferences.floatingSubtitleEnabled = enabled
            return
        }

        awaitingOverlayPermission = true
        val appUri = Uri.fromParts("package", packageName, null)
        if (!openOverlayPermissionSettings(appUri) && !openOverlayPermissionSettings(null)) {
            awaitingOverlayPermission = false
            Toast.makeText(this, R.string.player_floating_subtitle_permission_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openOverlayPermissionSettings(uri: Uri?): Boolean = try {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, uri))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun openFloatingSubtitleSettings() {
        overlayPermissionGranted = Settings.canDrawOverlays(this)
        if (preferences.floatingSubtitleEnabled && overlayPermissionGranted) {
            startActivity(Intent(this, PlayerFloatingSubtitleSettingsActivity::class.java))
        }
    }

    private companion object {
        const val STATE_AWAITING_OVERLAY_PERMISSION = "awaitingOverlayPermission"
    }
}

class PlayerFloatingSubtitleSettingsActivity : AppComposeActivity() {
    private val preferences by lazy { PlayerPreferences.getInstance(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!canOpenSettings()) {
            finish()
            return
        }
        setContent {
            val style by preferences.floatingSubtitleStyleChanges.collectAsStateWithLifecycle(
                initialValue = preferences.floatingSubtitleStyle
            )
            SubtitleEditComposeTheme {
                PlayerFloatingSubtitleSettings(
                    style = style,
                    onBack = ::navigateBack,
                    onSizeChanged = { size ->
                        val current = preferences.floatingSubtitleStyle
                        if (current.fontSize != size) preferences.floatingSubtitleStyle = current.copy(fontSize = size)
                    },
                    onLeftMarginChanged = { percent ->
                        val current = preferences.floatingSubtitleStyle
                        if (current.leftMarginPercent != percent) {
                            preferences.floatingSubtitleStyle = current.copy(leftMarginPercent = percent)
                        }
                    },
                    onWeightChanged = { weight ->
                        preferences.floatingSubtitleStyle = preferences.floatingSubtitleStyle.copy(weight = weight)
                    },
                    onPickColor = {
                        showFloatingSubtitleColorPicker(
                            context = this@PlayerFloatingSubtitleSettingsActivity,
                            color = preferences.floatingSubtitleStyle.colorArgb,
                        ) { color ->
                            preferences.floatingSubtitleStyle = preferences.floatingSubtitleStyle.copy(colorArgb = color)
                        }
                    },
                    onPickBackgroundColor = {
                        showFloatingSubtitleColorPicker(
                            context = this@PlayerFloatingSubtitleSettingsActivity,
                            color = preferences.floatingSubtitleStyle.backgroundColorArgb,
                            background = true,
                        ) { color ->
                            preferences.floatingSubtitleStyle = preferences.floatingSubtitleStyle.copy(backgroundColorArgb = color)
                        }
                    },
                    onBackgroundOpacityChanged = { percent ->
                        val current = preferences.floatingSubtitleStyle
                        if (current.backgroundOpacityPercent != percent) {
                            preferences.floatingSubtitleStyle = current.withBackgroundOpacity(percent)
                        }
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!canOpenSettings()) finish()
    }

    private fun canOpenSettings(): Boolean = preferences.floatingSubtitleEnabled && Settings.canDrawOverlays(this)
}

internal fun showFloatingSubtitleColorPicker(
    context: Context,
    color: Int,
    windowType: Int? = null,
    background: Boolean = false,
    onConfirm: (Int) -> Unit,
): AlertDialog = EditorColorPickerDialog(context).show(
    initialColor = color,
    recentColors = listOf(Color.WHITE, Color.YELLOW, Color.CYAN, Color.LTGRAY),
    windowType = windowType,
    title = if (background) context.getText(R.string.player_floating_subtitle_background_color) else null,
    onConfirm = onConfirm,
)

@Composable
private fun PlayerFloatingSubtitleSettings(
    style: PlayerFloatingSubtitleStyle,
    onBack: () -> Unit,
    onSizeChanged: (Int) -> Unit,
    onLeftMarginChanged: (Int) -> Unit,
    onWeightChanged: (PlayerFloatingSubtitleWeight) -> Unit,
    onPickColor: () -> Unit,
    onPickBackgroundColor: () -> Unit,
    onBackgroundOpacityChanged: (Int) -> Unit,
) {
    ModelSettingsScaffold(stringResource(R.string.player_floating_subtitle_adjustments), onBack) {
        PlayerFloatingSubtitle(
            text = stringResource(R.string.player_floating_subtitle_preview),
            style = style,
            modifier = Modifier.fillMaxWidth(),
        )
        SettingsTileGroup {
            Box(Modifier.testTag("floating-subtitle-size")) {
                val minSize = PlayerPreferences.MIN_SUBTITLE_FONT_SIZE
                val maxSize = PlayerPreferences.MAX_SUBTITLE_FONT_SIZE
                ModelSettingsSliderTile(
                    title = stringResource(R.string.vtt_style_font_size),
                    value = style.fontSize.toFloat(),
                    valueLabel = stringResource(R.string.player_floating_subtitle_font_size_value, style.fontSize),
                    onValueChange = { onSizeChanged(it.roundToInt()) },
                    valueRange = minSize.toFloat()..maxSize.toFloat(),
                )
            }
            Box(Modifier.testTag("floating-subtitle-left-margin")) {
                ModelSettingsSliderTile(
                    title = stringResource(R.string.player_floating_subtitle_left_margin),
                    value = style.leftMarginPercent.toFloat(),
                    valueLabel = stringResource(R.string.player_floating_subtitle_percent, style.leftMarginPercent),
                    onValueChange = { onLeftMarginChanged(it.roundToInt()) },
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
                onSelected = onWeightChanged,
            )
            SettingsTileRow(
                title = stringResource(R.string.player_floating_subtitle_font_color),
                value = "#${SubtitleColorOps.toArgbHex(style.colorArgb)}",
                onClick = onPickColor,
            )
        }
        SettingsTileGroup {
            SettingsTileRow(
                title = stringResource(R.string.player_floating_subtitle_background_color),
                value = SubtitleColorOps.toRgbHex(style.backgroundColorArgb),
                onClick = onPickBackgroundColor,
            )
            Box(Modifier.testTag("floating-subtitle-background-opacity")) {
                ModelSettingsSliderTile(
                    title = stringResource(R.string.player_floating_subtitle_background_opacity),
                    value = style.backgroundOpacityPercent.toFloat(),
                    valueLabel = stringResource(R.string.player_floating_subtitle_percent, style.backgroundOpacityPercent),
                    onValueChange = { onBackgroundOpacityChanged(it.roundToInt()) },
                    valueRange = 0f..100f,
                )
            }
        }
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
