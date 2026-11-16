package com.subtitleedit.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsBackupDirectorySourcesTest {
    @get:Rule val temporary = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val preferences by lazy { context.getSharedPreferences("favorite_directories", Context.MODE_PRIVATE) }
    private val store by lazy { FavoriteDirectoryStore(context) }
    private var originalPaths: Set<String>? = null
    private var originalLibraryPaths: Set<String>? = null

    @Before fun savePreferences() {
        originalPaths = preferences.getStringSet("paths", null)?.toSet()
        originalLibraryPaths = preferences.getStringSet("media_library_paths", null)?.toSet()
    }

    @After fun restorePreferences() {
        preferences.edit().apply {
            if (originalPaths == null) remove("paths") else putStringSet("paths", originalPaths)
            if (originalLibraryPaths == null) remove("media_library_paths")
            else putStringSet("media_library_paths", originalLibraryPaths)
        }.commit()
    }

    @Test fun backupRoundTripPreservesCommonAndMediaLibrarySources() {
        val common = temporary.newFolder("Common")
        val library = temporary.newFolder("Library")
        store.replace(setOf(common.path, library.path), setOf(library.path))

        val favorites = JSONObject(SettingsBackupManager.export(context)).getJSONObject("favorites")
        assertEquals(setOf(common.path, library.path), favorites.getJSONArray("directories").paths())
        assertEquals(setOf(library.path), favorites.getJSONArray("mediaLibraryDirectories").paths())

        store.replace(emptySet())
        restoreFavorites(favorites)

        val restored = store.load()
        assertEquals(listOf(common), restored.commonDirectories)
        assertEquals(listOf(library), restored.mediaLibraryDirectories)
    }

    @Test fun backupWithoutSourceMetadataClassifiesEveryDirectoryAsCommon() {
        val first = temporary.newFolder("First")
        val second = temporary.newFolder("Second")
        store.replace(setOf(first.path), setOf(first.path))

        restoreFavorites(JSONObject().put("directories", JSONArray(listOf(first.path, second.path))))

        val restored = store.load()
        assertEquals(setOf(first.path, second.path), restored.paths)
        assertEquals(emptySet<String>(), restored.mediaLibraryPaths)
        assertEquals(listOf(first, second), restored.commonDirectories)
    }

    @Test fun inaccessibleAndUnlistedDirectoriesCannotRetainMediaLibraryMarkers() {
        val common = temporary.newFolder("Common")
        val library = temporary.newFolder("Library")
        val unlisted = temporary.newFolder("Unlisted")
        val missing = File(temporary.root, "Missing")

        val result = restoreFavorites(JSONObject()
            .put("directories", JSONArray(listOf(common.path, library.path, missing.path)))
            .put("mediaLibraryDirectories", JSONArray(listOf(library.path, missing.path, unlisted.path))))

        val restored = store.load()
        assertEquals(1, result.skippedFavorites)
        assertEquals(setOf(common.path, library.path), restored.paths)
        assertEquals(setOf(library.path), restored.mediaLibraryPaths)
    }

    private fun restoreFavorites(favorites: JSONObject) = SettingsBackupManager.restore(context,
        JSONObject().put("schemaVersion", 1).put("favorites", favorites).toString())

    private fun JSONArray.paths(): Set<String> = (0 until length()).map { getString(it) }.toSet()
}
