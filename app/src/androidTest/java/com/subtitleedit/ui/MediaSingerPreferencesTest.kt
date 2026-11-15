package com.subtitleedit.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.subtitleedit.library.AlbumSortField
import com.subtitleedit.library.AlbumTrackSortField
import com.subtitleedit.library.AlbumTrackSortOptions
import com.subtitleedit.library.SingerSortField
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaSingerPreferencesTest {
    @Test fun allSingerOptionsPersistWithoutChangingAlbumOptions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = MediaSingerPreferences(context)
        val previous = preferences.load()
        val albumOptions = MediaAlbumPreferences(context).load()
        val changed = MediaSingerViewOptions(
            moreInfo = SingerMoreInfo.YEAR,
            groupTracks = false,
            quickSearch = false,
            sortField = SingerSortField.UPDATED_AT,
            ascending = false,
            albumSortField = AlbumSortField.YEAR,
            albumAscending = false,
            trackSort = AlbumTrackSortOptions(AlbumTrackSortField.LAST_PLAYED, false)
        )
        try {
            preferences.save(changed)
            assertEquals(changed, MediaSingerPreferences(context).load())
            assertEquals(albumOptions, MediaAlbumPreferences(context).load())
        } finally {
            preferences.save(previous)
        }
    }
}
