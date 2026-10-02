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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.subtitleedit.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    versionName: String,
    isCheckingForUpdates: Boolean,
    onNavigateBack: () -> Unit,
    onCheckForUpdates: () -> Unit,
    onOpenGithub: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.height(56.dp),
                title = { Text(stringResource(R.string.about)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_back),
                            contentDescription = "返回"
                        )
                    }
                }
            )
        }
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
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
                    fontSize = 35.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 24.dp)
                )
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.padding(top = 12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.version_format, versionName),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                    )
                }
                FilledTonalButton(
                    onClick = onCheckForUpdates,
                    enabled = !isCheckingForUpdates,
                    modifier = Modifier.padding(top = 12.dp)
                ) {
                    Text(
                        text = if (isCheckingForUpdates) "检测中…" else stringResource(R.string.check_for_updates)
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
