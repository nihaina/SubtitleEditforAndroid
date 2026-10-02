package com.subtitleedit.feature.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subtitleedit.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubtitleFormatSelectScreen(
    selectedFileName: String?,
    onSelectFile: () -> Unit,
    onConfirm: () -> Unit,
    onNavigateBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.height(56.dp),
                title = { Text("格式化工具") },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(20.dp)
        ) {
            Text(
                text = stringResource(R.string.activity_subtitle_format_select_text_01),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.activity_subtitle_format_select_text_02),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = onSelectFile,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp)
            ) {
                Text(stringResource(R.string.activity_subtitle_format_select_text_03))
            }
            Text(
                text = selectedFileName
                    ?: stringResource(R.string.activity_subtitle_format_select_text_04),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis
            )
            Button(
                onClick = onConfirm,
                enabled = selectedFileName != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp)
            ) {
                Text(stringResource(R.string.activity_subtitle_format_select_text_05))
            }
        }
    }
}
