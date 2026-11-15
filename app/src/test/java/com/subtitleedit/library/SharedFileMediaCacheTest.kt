package com.subtitleedit.library

import com.subtitleedit.model.BrowserFileMetadata
import com.subtitleedit.util.BrowserDirectoryCache
import com.subtitleedit.util.FileMetadataCache
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Exercises browsing and indexing against the same path/version and field cache. */
class SharedFileMediaCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    private class Fixture(val root: File) {
        var enumerations = 0
        val stats = mutableListOf<String>()
        val sources = linkedMapOf("song.wav" to 10L, "keep.wav" to 20L)
        val reads = mutableListOf<Pair<String, MissingMediaFields>>()
        val attributes = FileMetadataCache(reader = { file, revision ->
            stats += file.name
            sources[file.name]?.let { BrowserFileMetadata(file, false, it, 1000L, revision = revision) }
        })
        val directories = BrowserDirectoryCache(attributes, listFiles = {
            enumerations++
            sources.keys.map { File(root, it) }
        })
        val media = IncrementalMediaCache<String, String>(
            emptyTags = "", artworkWeight = String::length,
            load = { metadata, fields, cover ->
                reads += metadata.file.name to fields
                LoadedMediaFields(
                    durationMs = 125_000L,
                    tags = "title-${metadata.file.name}".takeIf { fields.tags },
                    cover = if (fields.cover) byteArrayOf(1, 2) else cover,
                    artwork = fields.artwork?.let { "cover-${it.size}" }
                )
            }
        )

        fun browse(): BrowserFileMetadata = directories.load(root).entries.first()
        fun preview(metadata: BrowserFileMetadata) = media.get(metadata, needDuration = true,
            artwork = MediaArtworkRequest(120, true))
        fun index(metadata: BrowserFileMetadata) = media.get(metadata, needDuration = true, needTags = true)
    }

    @Test fun `browsing first lets scanning add only missing tags without stat or enumeration`() {
        val fixture = Fixture(temporary.root)
        val browsed = fixture.browse()
        fixture.preview(browsed)
        val indexed = fixture.directories.load(fixture.root).entries.first()
        assertSame(browsed, indexed)
        fixture.index(indexed)
        assertEquals(1, fixture.enumerations)
        assertEquals(listOf("song.wav", "keep.wav"), fixture.stats)
        assertEquals(MissingMediaFields(false, true, false, null), fixture.reads.last().second)
        fixture.preview(indexed)
        fixture.index(browsed)
        assertEquals(2, fixture.reads.size)
    }

    @Test fun `scanning first lets directory preview add only missing artwork`() {
        val fixture = Fixture(temporary.root)
        val indexed = fixture.browse()
        fixture.index(indexed)
        fixture.preview(fixture.attributes.get(indexed.file)!!)
        val extra = fixture.reads.last().second
        assertEquals(false, extra.duration)
        assertEquals(false, extra.tags)
        assertEquals(true, extra.cover)
        assertEquals(MediaArtworkRequest(120, true), extra.artwork)
        assertEquals(listOf("song.wav", "keep.wav"), fixture.stats)
    }

    @Test fun `same-attribute file event invalidates both consumers but retains unrelated media`() {
        val fixture = Fixture(temporary.root)
        val before = fixture.directories.load(fixture.root)
        before.entries.forEach { fixture.index(it); fixture.preview(it) }
        fixture.stats.clear()
        fixture.reads.clear()
        val changed = fixture.directories.update(fixture.root, setOf("song.wav"))
        assertNotEquals(before.entries[0].cacheKey, changed.entries[0].cacheKey)
        assertSame(before.entries[1], changed.entries[1])
        changed.entries.forEach { fixture.index(it); fixture.preview(it) }
        assertEquals(listOf("song.wav"), fixture.stats)
        assertEquals(listOf("song.wav", "song.wav"), fixture.reads.map { it.first })
        assertEquals(1, fixture.enumerations)
    }

    @Test fun `observation gap validates attributes without discarding unchanged media fields`() {
        val fixture = Fixture(temporary.root)
        val before = fixture.browse()
        fixture.preview(before)
        fixture.index(before)
        fixture.directories.invalidate(fixture.root.absolutePath)
        val after = fixture.browse()
        assertSame(before, after)
        fixture.index(after)
        fixture.preview(after)
        assertEquals(2, fixture.reads.size)
        assertEquals(2, fixture.enumerations)
    }
}
