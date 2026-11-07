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
    advancedContent: @Composable () -> Unit
) {
    val pagerState = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()
    AppSection {
        PrimaryTabRow(selectedTabIndex = pagerState.currentPage) {
            listOf(R.string.media_convert_more_settings, R.string.media_convert_advanced_options).forEachIndexed { index, title ->
                Tab(
                    selected = pagerState.currentPage == index,
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
                if (page == 0) moreContent() else advancedContent()
            }
        }
    }
}
