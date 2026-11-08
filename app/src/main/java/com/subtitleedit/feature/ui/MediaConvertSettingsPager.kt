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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.subtitleedit.R
import com.subtitleedit.ui.components.AppSection
import com.subtitleedit.ui.theme.AppMotion
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private enum class MediaConvertSettingsPage(val title: Int) {
    MORE(R.string.media_convert_more_settings),
    ADVANCED(R.string.media_convert_advanced_options),
    SUBTITLES(R.string.media_convert_subtitles),
    INFO(R.string.media_convert_information)
}

/** Only the settings area pages horizontally; vertical scrolling stays with the tool page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaConvertSettingsPager(
    enabled: Boolean,
    moreContent: @Composable () -> Unit,
    advancedContent: @Composable () -> Unit,
    showSubtitlePage: Boolean = false,
    subtitleContent: @Composable () -> Unit = {},
    infoContent: @Composable () -> Unit = {},
    isAudioOutput: Boolean = false
) {
    val pages = when {
        isAudioOutput -> listOf(MediaConvertSettingsPage.ADVANCED, MediaConvertSettingsPage.INFO)
        showSubtitlePage -> MediaConvertSettingsPage.entries.toList()
        else -> listOf(MediaConvertSettingsPage.MORE, MediaConvertSettingsPage.ADVANCED)
    }
    var selectedPageName by rememberSaveable { mutableStateOf(pages.first().name) }
    val initialPage = pages.indexOfFirst { it.name == selectedPageName }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = initialPage) { pages.size }
    val selectedTab = pagerState.currentPage.coerceIn(pages.indices)
    val scope = rememberCoroutineScope()
    LaunchedEffect(pages) {
        // Keep a surviving page selected when changing output type; removed video pages fall
        // back to advanced options instead of accidentally selecting a different page by index.
        val target = pages.indexOfFirst { it.name == selectedPageName }.takeIf { it >= 0 }
            ?: pages.indexOf(MediaConvertSettingsPage.ADVANCED)
        pagerState.scrollToPage(target)
        snapshotFlow { pagerState.currentPage }.distinctUntilChanged().collect { index ->
            pages.getOrNull(index)?.let { selectedPageName = it.name }
        }
    }
    AppSection {
        PrimaryTabRow(selectedTabIndex = selectedTab) {
            pages.forEachIndexed { index, page ->
                Tab(
                    selected = selectedTab == index,
                    enabled = enabled,
                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                    modifier = Modifier.testTag("convert-settings-tab-$index"),
                    text = { Text(stringResource(page.title)) }
                )
            }
        }
        HorizontalPager(
            state = pagerState,
            key = { pages[it].name },
            userScrollEnabled = enabled,
            verticalAlignment = Alignment.Top,
            modifier = Modifier.fillMaxWidth().animateContentSize(AppMotion.state()).testTag("convert-settings-pager")
        ) { index ->
            Column(Modifier.fillMaxWidth()) {
                when (pages[index]) {
                    MediaConvertSettingsPage.MORE -> moreContent()
                    MediaConvertSettingsPage.ADVANCED -> advancedContent()
                    MediaConvertSettingsPage.SUBTITLES -> subtitleContent()
                    MediaConvertSettingsPage.INFO -> infoContent()
                }
            }
        }
    }
}
