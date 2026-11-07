package com.subtitleedit.feature.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppSection
import com.subtitleedit.ui.theme.AppMotion
import kotlinx.coroutines.launch

/** Only the settings area pages horizontally; vertical scrolling stays with the tool page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaConvertSettingsPager(
    enabled: Boolean,
    moreContent: @Composable () -> Unit,
    advancedContent: @Composable () -> Unit,
    showSubtitlePage: Boolean = false,
    subtitleContent: @Composable () -> Unit = {}
) {
    val pageCount = if (showSubtitlePage) 3 else 2
    val pagerState = rememberPagerState { pageCount }
    val selectedTab = pagerState.currentPage.coerceIn(0, pageCount - 1)
    val scope = rememberCoroutineScope()
    LaunchedEffect(pageCount) {
        if (pagerState.currentPage >= pageCount) pagerState.scrollToPage(pageCount - 1)
    }
    AppSection {
        PrimaryTabRow(selectedTabIndex = selectedTab) {
            listOf(R.string.media_convert_more_settings, R.string.media_convert_advanced_options, R.string.media_convert_subtitles)
                .take(pageCount).forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        enabled = enabled,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                        modifier = Modifier.testTag("convert-settings-tab-$index"),
                        text = { Text(stringResource(title)) }
                    )
                }
        }
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = enabled,
            verticalAlignment = Alignment.Top,
            modifier = Modifier.fillMaxWidth().animateContentSize(AppMotion.state()).testTag("convert-settings-pager")
        ) { page ->
            Column(Modifier.fillMaxWidth()) {
                when (page) {
                    0 -> moreContent()
                    1 -> advancedContent()
                    else -> subtitleContent()
                }
            }
        }
    }
}
