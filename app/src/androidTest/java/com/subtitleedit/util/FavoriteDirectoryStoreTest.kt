package com.subtitleedit.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FavoriteDirectoryStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences = context.getSharedPreferences("directory-source-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)
    private val store = FavoriteDirectoryStore(preferences)
    private val common = File(context.cacheDir, "Common")
    private val library = File(context.cacheDir, "Library")

    @After fun cleanup() {
        assertTrue(preferences.edit().clear().commit())
    }

    @Test fun directoriesSavedByOlderVersionsAreAllCommonShortcuts() {
        assertTrue(preferences.edit().putStringSet("paths", setOf(library.path, common.path)).commit())

        val snapshot = store.load()

        assertEquals(listOf(common, library), snapshot.directories)
        assertEquals(snapshot.directories, snapshot.commonDirectories)
        assertTrue(snapshot.mediaLibraryDirectories.isEmpty())
    }

    @Test fun commonAdditionRejectsDirectoriesAlreadyAddedFromEitherEntry() {
        assertTrue(store.addCommon(common))
        assertFalse(store.addCommon(common))
        store.addMediaLibrary(library)

        assertFalse(store.addCommon(library))
        assertEquals(setOf(common.path, library.path), store.load().paths)
        assertEquals(listOf(library), store.load().mediaLibraryDirectories)
    }

    @Test fun addingAnExistingCommonDirectoryThroughLibraryMovesItToLibrarySection() {
        assertTrue(store.addCommon(common))
        store.addMediaLibrary(common)
        store.addMediaLibrary(common)

        val snapshot = FavoriteDirectoryStore(preferences).load()

        assertEquals(setOf(common.path), snapshot.paths)
        assertTrue(snapshot.commonDirectories.isEmpty())
        assertEquals(listOf(common), snapshot.mediaLibraryDirectories)
        assertFalse(store.addCommon(common))
    }

    @Test fun removalClearsTheSourceSoACommonReadditionStaysCommon() {
        store.addMediaLibrary(library)
        store.remove(library)

        assertTrue(store.load().paths.isEmpty())
        assertTrue(store.load().mediaLibraryPaths.isEmpty())
        assertTrue(store.addCommon(library))
        assertEquals(listOf(library), store.load().commonDirectories)
        assertTrue(store.load().mediaLibraryDirectories.isEmpty())
    }

    @Test fun replacementDropsSourceMarkersForDirectoriesOutsideTheSharedList() {
        store.replace(setOf(common.path), setOf(common.path, library.path))

        assertEquals(setOf(common.path), store.load().mediaLibraryPaths)
        assertEquals(setOf(common.path), preferences.getStringSet("media_library_paths", emptySet()))

        store.replace(setOf(library.path))
        assertEquals(listOf(library), store.load().commonDirectories)
        assertTrue(store.load().mediaLibraryPaths.isEmpty())
    }
}
