package com.subtitleedit.feature.ui

import android.widget.ImageView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppToolScaffold

@Composable
fun AboutScreen(
    versionName: String,
    isCheckingForUpdates: Boolean,
    onNavigateBack: () -> Unit,
    onCheckForUpdates: () -> Unit,
    onOpenGithub: () -> Unit
) {
    AppToolScaffold(
        title = stringResource(R.string.about),
        onBack = onNavigateBack,
        scrollable = false
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                val appName = stringResource(R.string.app_name)
                AndroidView(
                    factory = { context ->
                        ImageView(context).apply {
                            setImageResource(R.mipmap.ic_launcher)
                            contentDescription = appName
                        }
                    },
                    modifier = Modifier.size(132.dp)
                )
                Text(
                    text = stringResource(R.string.app_name),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.displaySmall,
                    modifier = Modifier.padding(top = 24.dp)
                )
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.padding(top = 12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.version_format, versionName),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                    )
                }
                FilledTonalButton(
                    onClick = onCheckForUpdates,
                    enabled = !isCheckingForUpdates,
                    modifier = Modifier.padding(top = 12.dp)
                ) {
                    Text(
                        text = if (isCheckingForUpdates) stringResource(R.string.checking_for_updates) else stringResource(R.string.check_for_updates)
                    )
                }
                IconButton(
                    onClick = onOpenGithub,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .size(56.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_github),
                        contentDescription = stringResource(R.string.github_project),
                        // The legacy ImageButton had 12dp padding inside a 56dp box;
                        // ic_github therefore rendered at its 24dp intrinsic size.
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
}
}
