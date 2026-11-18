package com.subtitleedit.ui

import android.content.Context
import androidx.core.content.edit
import com.subtitleedit.library.MediaFolderOrder
import com.subtitleedit.model.FileSortDirection
import com.subtitleedit.model.FileSortField
import com.subtitleedit.util.SettingsManager

internal class MediaFolderPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("media_folder_view", Context.MODE_PRIVATE)
    private val settings = SettingsManager.getInstance(context)

    fun loadViewOptions(): MediaCollectionViewOptions = MediaCollectionViewOptions(
        viewMode = enumPreference("view_mode", AlbumViewMode.LIST_ART),
        gridSize = enumPreference("grid_size", AlbumGridSize.MEDIUM),
        showMoreInfo = preferences.getBoolean("show_more_info", true),
        quickSearch = preferences.getBoolean("quick_search", true)
    )

    fun saveViewOptions(options: MediaCollectionViewOptions) = preferences.edit {
        putString("view_mode", options.viewMode.name)
        putString("grid_size", options.gridSize.name)
        putBoolean("show_more_info", options.showMoreInfo)
        putBoolean("quick_search", options.quickSearch)
    }

    fun loadOrder(): MediaFolderOrder = MediaFolderOrder(
        enumPreference("field", settings.getFileSortField()),
        enumPreference("direction", settings.getFileSortDirection())
    )

    fun saveOrder(order: MediaFolderOrder) = preferences.edit {
        putString("field", order.field.name)
        putString("direction", order.direction.name)
    }

    private inline fun <reified Value : Enum<Value>> enumPreference(key: String, fallback: Value): Value =
        preferences.getString(key, null)?.let { stored -> enumValues<Value>().firstOrNull { it.name == stored } } ?: fallback
}
