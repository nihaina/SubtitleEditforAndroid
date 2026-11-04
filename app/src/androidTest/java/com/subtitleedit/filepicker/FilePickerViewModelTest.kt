package com.subtitleedit.filepicker

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.subtitleedit.model.FileSortDirection
import com.subtitleedit.model.FileSortField
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real local filesystem and result events without launching system pickers. */
@RunWith(AndroidJUnit4::class)
class FilePickerViewModelTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    @Test fun singleSelectionReturnsTheOriginalLocalFile() = withPicker(FilePickerMode.OPEN_FILE,
        setup = { File(it, "字幕.srt").writeText("original") }) { fixture ->
        val source = File(fixture.directory, "字幕.srt")
        fixture.awaitLoaded()
        onMain { fixture.model.select(source) }
        assertEquals(listOf(source), fixture.nextSelection())
        assertEquals("original", source.readText())
    }

    @Test fun multipleSelectionSurvivesDirectoryNavigationAndConfirmsBothFiles() =
        withPicker(FilePickerMode.OPEN_FILES, setup = {
            File(it, "first.srt").writeText("one")
            File(it, "nested").mkdir()
            File(it, "nested/second.srt").writeText("two")
        }) { fixture ->
            val first = File(fixture.directory, "first.srt")
            val nested = File(fixture.directory, "nested")
            val second = File(nested, "second.srt")
            fixture.awaitLoaded()
            onMain {
                fixture.model.select(first)
                assertTrue(fixture.model.navigate(nested))
            }
            fixture.awaitLoaded()
            onMain {
                fixture.model.select(second)
                fixture.model.confirm()
            }
            assertEquals(listOf(first, second), fixture.nextSelection())
            assertEquals(setOf(first.path, second.path), fixture.model.state.value.selectedPaths)
        }

    @Test fun directoryModeListsDirectoriesAndReturnsTheCurrentFolder() =
        withPicker(FilePickerMode.DIRECTORY, setup = {
            File(it, "child").mkdir()
            File(it, "not-a-directory.txt").writeText("text")
        }) { fixture ->
            fixture.awaitLoaded()
            assertEquals(listOf("child"), fixture.model.state.value.files.map { it.name })
            onMain { fixture.model.confirm() }
            assertEquals(listOf(fixture.directory), fixture.nextSelection())
        }

    @Test fun selectAllOnlyAddsVisibleFilesAndTogglesTheSelectionOff() =
        withPicker(FilePickerMode.OPEN_FILES, mimeTypes = listOf("text/*"), setup = {
            File(it, "child").mkdir()
            File(it, "first.srt").writeText("one")
            File(it, "second.srt").writeText("two")
            File(it, "audio.mp3").writeText("media")
            File(it, ".hidden.srt").writeText("hidden")
        }) { fixture ->
            onMain { fixture.model.setShowHidden(false) }
            fixture.awaitLoaded()
            onMain { fixture.model.selectAll() }
            assertEquals(setOf(File(fixture.directory, "first.srt").path,
                File(fixture.directory, "second.srt").path), fixture.model.state.value.selectedPaths)
            assertEquals(fixture.model.state.value.selectedPaths,
                fixture.recreate().state.value.selectedPaths)
            onMain { fixture.model.selectAll() }
            assertTrue(fixture.model.state.value.selectedPaths.isEmpty())
        }

    @Test fun rangeUsesTheCurrentVisibleOrderAndKeepsSelectionsFromAnotherDirectory() =
        withPicker(FilePickerMode.OPEN_FILES, setup = {
            File(it, "outside.srt").writeText("outside")
            val child = File(it, "child").apply { mkdir() }
            listOf("a.srt", "b.srt", "c.srt", "d.srt").forEach { name -> File(child, name).writeText(name) }
            File(child, "folder").mkdir()
        }) { fixture ->
            val child = File(fixture.directory, "child")
            val outside = File(fixture.directory, "outside.srt")
            onMain {
                fixture.model.select(outside)
                fixture.model.navigate(child)
                fixture.model.sort(FileSortField.NAME, FileSortDirection.DESCENDING)
            }
            fixture.awaitLoaded()
            onMain {
                fixture.model.select(File(child, "d.srt"))
                fixture.model.select(File(child, "b.srt"))
                fixture.model.selectRange()
            }
            assertEquals(setOf(outside.path, File(child, "d.srt").path,
                File(child, "c.srt").path, File(child, "b.srt").path), fixture.model.state.value.selectedPaths)
            assertFalse(File(child, "a.srt").path in fixture.model.state.value.selectedPaths)
            assertFalse(File(child, "folder").path in fixture.model.state.value.selectedPaths)
            onMain { fixture.model.selectAll() }
            assertTrue(outside.path in fixture.model.state.value.selectedPaths)
            assertEquals(5, fixture.model.state.value.selectedPaths.size)
            onMain { fixture.model.selectAll() }
            assertTrue(fixture.model.state.value.selectedPaths.isEmpty())
        }

    @Test fun rangeNeedsTwoSelectedFilesInTheCurrentDirectory() =
        withPicker(FilePickerMode.OPEN_FILES, setup = {
            File(it, "outside.srt").writeText("outside")
            val child = File(it, "child").apply { mkdir() }
            File(child, "first.srt").writeText("first")
            File(child, "second.srt").writeText("second")
        }) { fixture ->
            val outside = File(fixture.directory, "outside.srt")
            val child = File(fixture.directory, "child")
            val first = File(child, "first.srt")
            onMain { fixture.model.select(outside); fixture.model.navigate(child) }
            fixture.awaitLoaded()
            onMain { fixture.model.select(first); fixture.model.selectRange() }
            assertEquals(setOf(outside.path, first.path), fixture.model.state.value.selectedPaths)
        }

    @Test fun backLeavesSelectionModeWithoutChangingTheDirectoryOrSearch() =
        withPicker(FilePickerMode.OPEN_FILES, setup = {
            File(it, "first.srt").writeText("one")
        }) { fixture ->
            onMain {
                fixture.model.select(File(fixture.directory, "first.srt"))
                fixture.model.search("first")
                fixture.model.back()
            }
            assertTrue(fixture.model.state.value.selectedPaths.isEmpty())
            assertEquals(fixture.directory, fixture.model.state.value.directory)
            assertEquals("first", fixture.model.state.value.query)
            assertTrue(fixture.recreate().state.value.selectedPaths.isEmpty())
        }

    @Test fun selectionToolbarActionsDoNothingForSingleFileOrDirectoryModes() {
        for (mode in listOf(FilePickerMode.OPEN_FILE, FilePickerMode.DIRECTORY, FilePickerMode.SAVE_FILE)) {
            withPicker(mode, setup = { File(it, "first.srt").writeText("one") }) { fixture ->
                fixture.awaitLoaded()
                onMain { fixture.model.selectAll(); fixture.model.selectRange() }
                assertTrue(fixture.model.state.value.selectedPaths.isEmpty())
            }
        }
    }

    @Test fun unsupportedFilesAreVisibleAndDisabledUntilFilteringIsEnabled() =
        withPicker(FilePickerMode.OPEN_FILE, mimeTypes = listOf("audio/*"), setup = {
            File(it, "folder").mkdir()
            File(it, "music.mp3").writeText("audio")
            File(it, ".secret.mp3").writeText("audio")
            File(it, "subtitles.srt").writeText("text")
        }) { fixture ->
            onMain { fixture.model.setShowHidden(false) }
            fixture.awaitLoaded()
            assertFalse(fixture.model.state.value.filterUnsupported)
            assertEquals(setOf("folder", "music.mp3", "subtitles.srt"),
                fixture.model.state.value.files.map { it.name }.toSet())
            assertEquals(setOf(File(fixture.directory, "subtitles.srt").path),
                fixture.model.state.value.unsupportedPaths)
            onMain { fixture.model.setShowHidden(true) }
            fixture.awaitLoaded()
            assertEquals(setOf("folder", "music.mp3", ".secret.mp3", "subtitles.srt"),
                fixture.model.state.value.files.map { it.name }.toSet())
            onMain { fixture.model.setFilterUnsupported(true) }
            fixture.awaitLoaded()
            assertEquals(setOf("folder", "music.mp3", ".secret.mp3"),
                fixture.model.state.value.files.map { it.name }.toSet())
            assertTrue(fixture.recreate().state.value.filterUnsupported)
            onMain { fixture.model.setFilterUnsupported(false) }
            fixture.awaitLoaded()
            assertTrue(File(fixture.directory, "subtitles.srt") in fixture.model.state.value.files)
        }

    @Test fun subtitleWhitelistPreventsClickAndSelectAllBypassesEvenWithBroadMimeTypes() =
        withPicker(FilePickerMode.OPEN_FILES, mimeTypes = listOf("text/*", "application/*"),
            allowedExtensions = listOf("srt", "lrc", "vtt", "txt"), setup = {
                listOf("one.SRT", "two.lrc", "three.vtt", "four.txt", "unsupported.ass",
                    "unsupported.ssa", "backup.json", "audio.mp3").forEach { name -> File(it, name).writeText(name) }
                File(it, "folder").mkdir()
            }) { fixture ->
            fixture.awaitLoaded()
            val validPaths = listOf("one.SRT", "two.lrc", "three.vtt", "four.txt")
                .map { File(fixture.directory, it).path }.toSet()
            val invalidPaths = listOf("unsupported.ass", "unsupported.ssa", "backup.json", "audio.mp3")
                .map { File(fixture.directory, it).path }.toSet()
            assertEquals(invalidPaths, fixture.model.state.value.unsupportedPaths)
            assertEquals(9, fixture.model.state.value.files.size)
            onMain { fixture.model.select(File(fixture.directory, "unsupported.ass")) }
            assertTrue(fixture.model.state.value.selectedPaths.isEmpty())
            onMain { fixture.model.selectAll() }
            assertEquals(validPaths, fixture.model.state.value.selectedPaths)
            onMain { fixture.model.setFilterUnsupported(true) }
            fixture.awaitLoaded()
            assertEquals(validPaths, fixture.model.state.value.selectedPaths)
            assertEquals(validPaths + File(fixture.directory, "folder").path,
                fixture.model.state.value.files.map { it.path }.toSet())
            onMain { fixture.model.confirm() }
            assertEquals(validPaths, fixture.nextSelection().map { it.path }.toSet())
        }

    @Test fun unsupportedSingleFileClickCannotEmitAResultBeforeAValidFile() =
        withPicker(FilePickerMode.OPEN_FILE, allowedExtensions = listOf("srt", "lrc", "vtt", "txt"), setup = {
            File(it, "unsupported.ass").writeText("not supported here")
            File(it, "valid.srt").writeText("supported")
        }) { fixture ->
            fixture.awaitLoaded()
            val valid = File(fixture.directory, "valid.srt")
            onMain {
                fixture.model.select(File(fixture.directory, "unsupported.ass"))
                fixture.model.select(valid)
            }
            assertEquals(listOf(valid), fixture.nextSelection())
        }

    @Test fun rangeSkipsUnsupportedFilesBetweenValidEndpoints() =
        withPicker(FilePickerMode.OPEN_FILES, allowedExtensions = listOf("srt", "lrc", "vtt", "txt"), setup = {
            listOf("a.srt", "b.json", "c.lrc", "d.ass", "e.vtt").forEach { name -> File(it, name).writeText(name) }
        }) { fixture ->
            onMain { fixture.model.sort(FileSortField.NAME, FileSortDirection.ASCENDING) }
            fixture.awaitLoaded()
            onMain {
                fixture.model.select(File(fixture.directory, "a.srt"))
                fixture.model.select(File(fixture.directory, "e.vtt"))
                fixture.model.selectRange()
            }
            assertEquals(setOf("a.srt", "c.lrc", "e.vtt").map { File(fixture.directory, it).path }.toSet(),
                fixture.model.state.value.selectedPaths)
        }

    @Test fun mediaPickerDisablesTextAndBinaryWhileTranscriptAudioAlsoDisablesVideo() {
        for ((types, expected) in listOf(
            listOf("audio/*", "video/*") to setOf("sound.mp3", "movie.mp4"),
            listOf("audio/*") to setOf("sound.mp3")
        )) {
            withPicker(FilePickerMode.OPEN_FILES, mimeTypes = types, setup = {
                listOf("sound.mp3", "movie.mp4", "subtitles.srt", "model.bin").forEach { name -> File(it, name).writeText(name) }
            }) { fixture ->
                fixture.awaitLoaded()
                onMain { fixture.model.selectAll() }
                assertEquals(expected.map { File(fixture.directory, it).path }.toSet(),
                    fixture.model.state.value.selectedPaths)
            }
        }
    }

    @Test fun restoredSelectionsCannotReintroduceUnsupportedFiles() =
        withPicker(FilePickerMode.OPEN_FILES, allowedExtensions = listOf("srt", "lrc", "vtt", "txt"), setup = {
            File(it, "valid.srt").writeText("subtitle")
            File(it, "unsupported.json").writeText("config")
        }) { fixture ->
            val valid = File(fixture.directory, "valid.srt")
            val unsupported = File(fixture.directory, "unsupported.json")
            onMain { fixture.saved["selected"] = arrayListOf(valid.path, unsupported.path) }
            val restored = fixture.recreate()
            assertEquals(setOf(valid.path), restored.state.value.selectedPaths)
            onMain { restored.confirm() }
            val result = runBlocking { withTimeout(5_000) { restored.events.first() } }
            assertEquals(listOf(valid), (result as FilePickerEvent.Selected).files)
        }

    @Test fun savingANewHiddenFileOnlyReturnsAPathWithoutCreatingIt() =
        withPicker(FilePickerMode.SAVE_FILE, suggestedName = ".srt") { fixture ->
            val target = File(fixture.directory, ".srt")
            onMain { fixture.model.confirm() }
            assertEquals(listOf(target), fixture.nextSelection())
            assertFalse("Choosing a target must not create/truncate it", target.exists())
        }

    @Test fun saveConflictRequiresAChoiceAndUniqueNameLeavesExistingFilesUntouched() =
        withPicker(FilePickerMode.SAVE_FILE, suggestedName = "export.srt", setup = {
            File(it, "export.srt").writeText("keep original")
            File(it, "export (1).srt").writeText("keep numbered")
        }) { fixture ->
            val original = File(fixture.directory, "export.srt")
            onMain { fixture.model.confirm() }
            runBlocking { withTimeout(5_000) { fixture.model.state.first { it.pendingOverwrite != null } } }
            assertEquals(original, fixture.model.state.value.pendingOverwrite)
            assertEquals("keep original", original.readText())
            onMain { fixture.model.useUniqueName() }
            val target = File(fixture.directory, "export (2).srt")
            assertEquals(listOf(target), fixture.nextSelection())
            assertFalse(target.exists())
            assertEquals("keep original", original.readText())
            assertEquals("keep numbered", File(fixture.directory, "export (1).srt").readText())
        }

    @Test fun explicitOverwriteReturnsExistingPathWithoutTruncatingItsContent() =
        withPicker(FilePickerMode.SAVE_FILE, suggestedName = "existing.json", setup = {
            File(it, "existing.json").writeText("existing backup")
        }) { fixture ->
            onMain { fixture.model.confirm() }
            runBlocking { withTimeout(5_000) { fixture.model.state.first { it.pendingOverwrite != null } } }
            onMain { fixture.model.overwrite() }
            assertEquals(listOf(File(fixture.directory, "existing.json")), fixture.nextSelection())
            assertEquals("existing backup", File(fixture.directory, "existing.json").readText())
        }

    @Test fun invalidSaveNameCannotEscapeTheSelectedDirectory() =
        withPicker(FilePickerMode.SAVE_FILE, suggestedName = "../escaped.srt") { fixture ->
            onMain { fixture.model.confirm() }
            runBlocking { withTimeout(5_000) { fixture.model.state.first { it.error != null } } }
            assertNotNull(fixture.model.state.value.error)
            onMain { fixture.model.setName("valid.srt"); fixture.model.confirm() }
            assertEquals(listOf(File(fixture.directory, "valid.srt")), fixture.nextSelection())
        }

    @Test fun savedStateRestoresDirectorySelectionQueryAndSaveName() =
        withPicker(FilePickerMode.OPEN_FILES, setup = {
            File(it, "sub").mkdir()
            File(it, "selected.srt").writeText("text")
        }) { fixture ->
            val source = File(fixture.directory, "selected.srt")
            val child = File(fixture.directory, "sub")
            onMain {
                fixture.model.select(source)
                fixture.model.navigate(child)
                fixture.model.search("needle")
                fixture.model.setName("remembered.srt")
                fixture.model.setFilterUnsupported(true)
            }
            val restored = fixture.recreate()
            assertEquals(child, restored.state.value.directory)
            assertEquals(setOf(source.path), restored.state.value.selectedPaths)
            assertEquals("needle", restored.state.value.query)
            assertEquals("remembered.srt", restored.state.value.suggestedName)
            assertTrue(restored.state.value.filterUnsupported)
        }

    @Test fun newDirectoryCreationUsesTheSameBrowserAndKeepsExistingEntries() =
        withPicker(FilePickerMode.DIRECTORY) { fixture ->
            val created = CompletableDeferred<String?>()
            onMain { fixture.model.createDirectory("new folder") { created.complete(it) } }
            assertNull(runBlocking { withTimeout(5_000) { created.await() } })
            assertTrue(File(fixture.directory, "new folder").isDirectory)
            val duplicate = CompletableDeferred<String?>()
            onMain { fixture.model.createDirectory("new folder") { duplicate.complete(it) } }
            assertNotNull(runBlocking { withTimeout(5_000) { duplicate.await() } })
            assertTrue(File(fixture.directory, "new folder").isDirectory)
        }

    private fun withPicker(
        mode: FilePickerMode,
        mimeTypes: List<String> = listOf("*/*"),
        suggestedName: String? = null,
        allowedExtensions: List<String> = emptyList(),
        setup: (File) -> Unit = {},
        block: (Fixture) -> Unit
    ) {
        val directory = File(app.cacheDir, "picker_test_${System.nanoTime()}").apply { mkdirs() }
        val saved = SavedStateHandle()
        val request = FilePickerRequest(mode, mimeTypes, directory.path, suggestedName, allowedExtensions)
        val store = ViewModelStore()
        try {
            setup(directory)
            val model = onMain {
                FilePickerViewModel(app, saved).also {
                    store.put("picker", it)
                    it.initialize(request)
                    it.updateStoragePermission(true)
                }
            }
            block(Fixture(directory, model, saved, request, store))
        } finally {
            onMain { store.clear() }
            directory.deleteRecursively()
        }
    }

    private inner class Fixture(
        val directory: File,
        val model: FilePickerViewModel,
        val saved: SavedStateHandle,
        val request: FilePickerRequest,
        val store: ViewModelStore
    ) {
        fun awaitLoaded() = runBlocking {
            withTimeout(5_000) { model.state.first { !it.loading } }
        }

        fun nextSelection(): List<File> = runBlocking {
            val event = withTimeout(5_000) { model.events.first() }
            assertTrue("Expected local file selection, got $event", event is FilePickerEvent.Selected)
            (event as FilePickerEvent.Selected).files
        }

        fun recreate(): FilePickerViewModel = onMain {
            FilePickerViewModel(app, saved).also {
                store.put("restored", it)
                it.initialize(request)
            }
        }
    }

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<T>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result.set(block()) }
        return result.get()
    }
}
