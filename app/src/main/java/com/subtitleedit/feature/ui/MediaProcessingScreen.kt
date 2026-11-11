package com.subtitleedit.feature.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.subtitleedit.MediaProcessingPage
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppBackButton
import com.subtitleedit.ui.components.AppTopBar
import kotlinx.coroutines.launch

/** The existing conversion page and the mux queue keep independent state within one tool. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaProcessingScreen(
    initialPage: MediaProcessingPage,
    enabled: Boolean,
    onBack: () -> Unit,
    convertContent: @Composable () -> Unit,
    muxContent: @Composable () -> Unit
) {
    val pager = rememberPagerState(initialPage = initialPage.ordinal) { MediaProcessingPage.entries.size }
    val scope = rememberCoroutineScope()
    Scaffold(topBar = {
        Column {
            AppTopBar(title = stringResource(R.string.media_convert_title), navigationIcon = { AppBackButton(onBack) })
            PrimaryTabRow(selectedTabIndex = pager.currentPage) {
                MediaProcessingPage.entries.forEach { page ->
                    Tab(selected = pager.currentPage == page.ordinal, enabled = enabled,
                        onClick = { scope.launch { pager.animateScrollToPage(page.ordinal) } },
                        text = { Text(stringResource(if (page == MediaProcessingPage.CONVERT)
                            R.string.media_processing_convert_tab else R.string.media_processing_mux_tab)) },
                        modifier = Modifier.testTag("media-processing-tab-${page.name.lowercase()}"))
                }
            }
        }
    }) { insets ->
        HorizontalPager(state = pager, userScrollEnabled = enabled,
            modifier = Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)
                .testTag("media-processing-pager")) { index ->
            Box(Modifier.fillMaxSize()) {
                if (index == MediaProcessingPage.CONVERT.ordinal) convertContent() else muxContent()
            }
        }
    }
}
