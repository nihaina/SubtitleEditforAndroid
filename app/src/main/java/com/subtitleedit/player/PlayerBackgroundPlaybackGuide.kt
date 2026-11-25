package com.subtitleedit.player

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppDialog
import com.subtitleedit.ui.components.AppPrimaryButton
import com.subtitleedit.ui.theme.AppSpacing

@Composable
internal fun PlayerBackgroundPlaybackGuide(onDismiss: (Boolean) -> Unit) {
    val context = LocalContext.current
    var showSteps by rememberSaveable { mutableStateOf(false) }
    var suppressFuturePrompts by rememberSaveable { mutableStateOf(false) }
    var settingsUnavailable by rememberSaveable { mutableStateOf(false) }
    val openSettings: () -> Unit = {
        if (openBackgroundPlaybackSettings(context)) onDismiss(suppressFuturePrompts)
        else settingsUnavailable = true
    }

    AppDialog(
        onDismissRequest = {
            if (showSteps) showSteps = false else onDismiss(suppressFuturePrompts)
        },
        title = {
            Text(stringResource(if (showSteps) R.string.player_background_guide_steps_title
                else R.string.player_background_guide_title))
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.CardGap),
            ) {
                if (showSteps) {
                    Text(stringResource(R.string.player_background_guide_app_path))
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(AppSpacing.Inner)) {
                            Text(stringResource(R.string.player_background_guide_allow_usage),
                                style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.player_background_guide_unrestricted),
                                style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    Text(stringResource(R.string.player_background_guide_device_settings))
                } else {
                    Text(stringResource(R.string.player_background_guide_interrupted))
                    AppPrimaryButton(stringResource(R.string.player_background_guide_continue), onClick = openSettings)
                    Text(stringResource(R.string.player_background_guide_explanation))
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                            value = suppressFuturePrompts,
                            role = Role.Checkbox,
                            onValueChange = { suppressFuturePrompts = it },
                        ),
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Inner),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = suppressFuturePrompts, onCheckedChange = null)
                        Text(stringResource(R.string.player_background_guide_suppress),
                            style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (settingsUnavailable) {
                    Text(stringResource(R.string.player_background_guide_settings_unavailable),
                        color = MaterialTheme.colorScheme.error)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { onDismiss(suppressFuturePrompts) }) { Text(stringResource(R.string.cancel)) }
        },
        confirmButton = {
            TextButton(onClick = {
                if (!showSteps) showSteps = true
                else openSettings()
            }) {
                Text(stringResource(if (showSteps) R.string.player_background_guide_open_settings
                    else R.string.player_background_guide_next))
            }
        },
    )
}

private fun openBackgroundPlaybackSettings(context: Context): Boolean {
    val appUri = Uri.fromParts("package", context.packageName, null)
    val actions = listOf(
        Intent(ACTION_VIEW_ADVANCED_POWER_USAGE_DETAIL, appUri),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, appUri),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )
    for (intent in actions) {
        try {
            context.startActivity(intent)
            return true
        } catch (_: ActivityNotFoundException) {
            // Some manufacturers omit individual Android settings activities.
        } catch (_: SecurityException) {
            // Try a more general settings screen if the direct entry is restricted.
        }
    }
    return false
}

// Settings exports this action on devices that provide a direct app battery page.
private const val ACTION_VIEW_ADVANCED_POWER_USAGE_DETAIL = "android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL"
