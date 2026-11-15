package com.subtitleedit.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.subtitleedit.library.AlbumSortField
import com.subtitleedit.library.AlbumTrackSortField
import com.subtitleedit.library.AlbumTrackSortOptions
import com.subtitleedit.library.MediaCreditRole
import com.subtitleedit.library.SingerSortField
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaSingerPreferencesTest {
    @Test fun categoryPreferencesRemainIndependentAndSingerKeepsItsExistingStore() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val stores = MediaCreditRole.entries.associateWith { MediaSingerPreferences(context, it) }
        val previous = stores.mapValues { it.value.load() }
        val albumOptions = MediaAlbumPreferences(context).load()
        val changed = mapOf(
            MediaCreditRole.SINGER to MediaSingerViewOptions(sortField = SingerSortField.TRACK_COUNT, ascending = false),
            MediaCreditRole.ARTIST to MediaSingerViewOptions(moreInfo = SingerMoreInfo.DURATION, groupTracks = false,
                quickSearch = false, sortField = SingerSortField.DURATION, ascending = false,
                albumSortField = AlbumSortField.UPDATED_AT, albumAscending = false,
                trackSort = AlbumTrackSortOptions(AlbumTrackSortField.TITLE, false)),
            MediaCreditRole.COMPOSER to MediaSingerViewOptions(moreInfo = SingerMoreInfo.YEAR,
                sortField = SingerSortField.UPDATED_AT, albumSortField = AlbumSortField.YEAR,
                trackSort = AlbumTrackSortOptions(AlbumTrackSortField.LAST_PLAYED, false)),
            MediaCreditRole.GENRE to MediaSingerViewOptions(moreInfo = SingerMoreInfo.TRACK_COUNT,
                groupTracks = false, quickSearch = false, sortField = SingerSortField.TRACK_COUNT,
                albumSortField = AlbumSortField.DURATION, albumAscending = false,
                trackSort = AlbumTrackSortOptions(AlbumTrackSortField.DURATION, false))
        )
        try {
            changed.forEach { (role, options) -> stores.getValue(role).save(options) }
            for ((role, options) in changed) assertEquals(options, MediaSingerPreferences(context, role).load())
            assertEquals(changed.getValue(MediaCreditRole.SINGER), MediaSingerPreferences(context).load())
            assertEquals(albumOptions, MediaAlbumPreferences(context).load())

            stores.getValue(MediaCreditRole.COMPOSER).save(MediaSingerViewOptions())
            assertEquals(changed.getValue(MediaCreditRole.ARTIST), MediaSingerPreferences(context, MediaCreditRole.ARTIST).load())
            assertEquals(changed.getValue(MediaCreditRole.SINGER), MediaSingerPreferences(context).load())
            assertEquals(changed.getValue(MediaCreditRole.GENRE), MediaSingerPreferences(context, MediaCreditRole.GENRE).load())
            stores.getValue(MediaCreditRole.GENRE).save(MediaSingerViewOptions())
            assertEquals(changed.getValue(MediaCreditRole.ARTIST), MediaSingerPreferences(context, MediaCreditRole.ARTIST).load())
            assertEquals(changed.getValue(MediaCreditRole.SINGER), MediaSingerPreferences(context).load())
            assertEquals(MediaSingerViewOptions(), MediaSingerPreferences(context, MediaCreditRole.COMPOSER).load())
        } finally {
            previous.forEach { (role, options) -> stores.getValue(role).save(options) }
        }
    }

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
