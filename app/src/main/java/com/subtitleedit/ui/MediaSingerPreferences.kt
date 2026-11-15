package com.subtitleedit.ui

import android.content.Context
import com.subtitleedit.library.AlbumSortField
import com.subtitleedit.library.AlbumTrackSortField
import com.subtitleedit.library.AlbumTrackSortOptions
import com.subtitleedit.library.MediaCreditRole
import com.subtitleedit.library.SingerSortField

internal enum class SingerMoreInfo { AUTO, NONE, TRACK_COUNT, DURATION, UPDATED_AT, YEAR }

internal data class MediaSingerViewOptions(
    val moreInfo: SingerMoreInfo = SingerMoreInfo.AUTO,
    val groupTracks: Boolean = true,
    val quickSearch: Boolean = true,
    val sortField: SingerSortField = SingerSortField.NAME,
    val ascending: Boolean = true,
    val albumSortField: AlbumSortField = AlbumSortField.NAME,
    val albumAscending: Boolean = true,
    val trackSort: AlbumTrackSortOptions = AlbumTrackSortOptions()
)

/** Each credit category owns its presentation; the existing singer store remains compatible. */
internal class MediaSingerPreferences(context: Context, role: MediaCreditRole = MediaCreditRole.SINGER) {
    private val preferences = context.applicationContext.getSharedPreferences(
        when (role) {
            MediaCreditRole.SINGER -> "media_singer_view"
            MediaCreditRole.ARTIST -> "media_artist_view"
            MediaCreditRole.COMPOSER -> "media_composer_view"
        },
        Context.MODE_PRIVATE
    )

    fun load(): MediaSingerViewOptions = MediaSingerViewOptions(
        moreInfo = enumPreference("more_info", SingerMoreInfo.AUTO),
        groupTracks = preferences.getBoolean("group_tracks", true),
        quickSearch = preferences.getBoolean("quick_search", true),
        sortField = enumPreference("sort_field", SingerSortField.NAME),
        ascending = preferences.getBoolean("ascending", true),
        albumSortField = enumPreference("album_sort_field", AlbumSortField.NAME),
        albumAscending = preferences.getBoolean("album_ascending", true),
        trackSort = AlbumTrackSortOptions(
            field = enumPreference("track_sort_field", AlbumTrackSortField.TRACK_NUMBER),
            ascending = preferences.getBoolean("track_sort_ascending", true)
        )
    )

    fun save(options: MediaSingerViewOptions) {
        preferences.edit()
            .putString("more_info", options.moreInfo.name)
            .putBoolean("group_tracks", options.groupTracks)
            .putBoolean("quick_search", options.quickSearch)
            .putString("sort_field", options.sortField.name)
            .putBoolean("ascending", options.ascending)
            .putString("album_sort_field", options.albumSortField.name)
            .putBoolean("album_ascending", options.albumAscending)
            .putString("track_sort_field", options.trackSort.field.name)
            .putBoolean("track_sort_ascending", options.trackSort.ascending)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumPreference(key: String, fallback: T): T =
        preferences.getString(key, null)?.let { stored ->
            enumValues<T>().firstOrNull { it.name == stored }
        } ?: fallback
}
