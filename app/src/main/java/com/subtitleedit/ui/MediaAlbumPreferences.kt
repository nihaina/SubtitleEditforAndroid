package com.subtitleedit.ui

import android.content.Context
import com.subtitleedit.library.AlbumSortField
import com.subtitleedit.library.AlbumTrackSortField
import com.subtitleedit.library.AlbumTrackSortOptions

internal enum class AlbumViewMode { LIST_NO_ART, LIST_ART, GRID }

internal enum class AlbumGridSize(val compactColumns: Int) {
    SMALL(4),
    MEDIUM(3),
    LARGE(2)
}

internal data class MediaAlbumViewOptions(
    val viewMode: AlbumViewMode = AlbumViewMode.GRID,
    val gridSize: AlbumGridSize = AlbumGridSize.MEDIUM,
    val showMoreInfo: Boolean = false,
    val groupTracks: Boolean = true,
    val quickSearch: Boolean = true,
    val sortField: AlbumSortField = AlbumSortField.NAME,
    val ascending: Boolean = true,
    val trackSort: AlbumTrackSortOptions = AlbumTrackSortOptions()
)

/** Album presentation is independent of directory-browser and editor preferences. */
internal class MediaAlbumPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("media_album_view", Context.MODE_PRIVATE)

    fun load(): MediaAlbumViewOptions = MediaAlbumViewOptions(
        viewMode = enumPreference("view_mode", AlbumViewMode.GRID),
        gridSize = enumPreference("grid_size", AlbumGridSize.MEDIUM),
        showMoreInfo = preferences.getBoolean("show_more_info", false),
        groupTracks = preferences.getBoolean("group_tracks", true),
        quickSearch = preferences.getBoolean("quick_search", true),
        sortField = enumPreference("sort_field", AlbumSortField.NAME),
        ascending = preferences.getBoolean("ascending", true),
        trackSort = AlbumTrackSortOptions(
            field = enumPreference("track_sort_field", AlbumTrackSortField.TRACK_NUMBER),
            ascending = preferences.getBoolean("track_sort_ascending", true)
        )
    )

    fun save(options: MediaAlbumViewOptions) {
        preferences.edit()
            .putString("view_mode", options.viewMode.name)
            .putString("grid_size", options.gridSize.name)
            .putBoolean("show_more_info", options.showMoreInfo)
            .putBoolean("group_tracks", options.groupTracks)
            .putBoolean("quick_search", options.quickSearch)
            .putString("sort_field", options.sortField.name)
            .putBoolean("ascending", options.ascending)
            .putString("track_sort_field", options.trackSort.field.name)
            .putBoolean("track_sort_ascending", options.trackSort.ascending)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumPreference(key: String, fallback: T): T =
        preferences.getString(key, null)?.let { stored ->
            enumValues<T>().firstOrNull { it.name == stored }
        } ?: fallback
}
