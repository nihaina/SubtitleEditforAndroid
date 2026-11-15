package com.subtitleedit.ui

import androidx.annotation.StringRes
import com.subtitleedit.R
import com.subtitleedit.library.MediaCreditRole

internal val MediaCreditRole.tagPrefix: String
    get() = when (this) {
        MediaCreditRole.SINGER -> "singer"
        MediaCreditRole.ARTIST -> "artist"
        MediaCreditRole.COMPOSER -> "composer"
        MediaCreditRole.GENRE -> "genre"
    }

internal val MediaCreditRole.labelRes: Int
    @StringRes get() = when (this) {
        MediaCreditRole.SINGER -> R.string.media_category_singers
        MediaCreditRole.ARTIST -> R.string.media_category_artists
        MediaCreditRole.COMPOSER -> R.string.media_category_composers
        MediaCreditRole.GENRE -> R.string.media_category_genres
    }

internal val MediaCreditRole.unknownRes: Int
    @StringRes get() = when (this) {
        MediaCreditRole.SINGER -> R.string.media_album_unknown_artist
        MediaCreditRole.ARTIST -> R.string.media_album_unknown_album_artist
        MediaCreditRole.COMPOSER -> R.string.media_album_unknown_composer
        MediaCreditRole.GENRE -> R.string.media_genre_unknown
    }

internal data class MediaCreditLabels(
    @StringRes val search: Int,
    @StringRes val settings: Int,
    @StringRes val back: Int,
    @StringRes val empty: Int,
    @StringRes val noResults: Int,
    @StringRes val sort: Int,
    @StringRes val moreInfo: Int,
    @StringRes val groupTracks: Int,
    @StringRes val albumSort: Int
)

internal val MediaCreditRole.labels: MediaCreditLabels
    get() = when (this) {
        MediaCreditRole.SINGER -> singerLabels
        MediaCreditRole.ARTIST -> artistLabels
        MediaCreditRole.COMPOSER -> composerLabels
        MediaCreditRole.GENRE -> genreLabels
    }

private val singerLabels = MediaCreditLabels(
    R.string.singer_browse_search, R.string.singer_browse_settings, R.string.singer_detail_back,
    R.string.singer_browse_empty, R.string.singer_browse_no_results, R.string.singer_sort_title,
    R.string.singer_more_info, R.string.singer_group_tracks, R.string.singer_album_sort
)
private val artistLabels = MediaCreditLabels(
    R.string.artist_browse_search, R.string.artist_browse_settings, R.string.artist_detail_back,
    R.string.artist_browse_empty, R.string.artist_browse_no_results, R.string.artist_sort_title,
    R.string.artist_more_info, R.string.artist_group_tracks, R.string.artist_album_sort
)
private val composerLabels = MediaCreditLabels(
    R.string.composer_browse_search, R.string.composer_browse_settings, R.string.composer_detail_back,
    R.string.composer_browse_empty, R.string.composer_browse_no_results, R.string.composer_sort_title,
    R.string.composer_more_info, R.string.composer_group_tracks, R.string.composer_album_sort
)
private val genreLabels = MediaCreditLabels(
    R.string.genre_browse_search, R.string.genre_browse_settings, R.string.genre_detail_back,
    R.string.genre_browse_empty, R.string.genre_browse_no_results, R.string.genre_sort_title,
    R.string.genre_more_info, R.string.genre_group_tracks, R.string.genre_album_sort
)
