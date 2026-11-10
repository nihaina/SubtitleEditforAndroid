package com.subtitleedit

import java.util.Locale

/** The tag editor uses TagLib's format-independent property names for every audio container. */
internal object MediaTagAudioMetadata {
    private data class Rule(val key: String, val aliases: List<String> = emptyList())

    private fun rule(field: MediaConvertMetadataField): Rule? = when (field) {
        MediaConvertMetadataField.TITLE -> Rule("TITLE", listOf("TIT2", "TIT1"))
        MediaConvertMetadataField.ARTIST -> Rule("ARTIST", listOf("TPE1", "AUTHOR"))
        MediaConvertMetadataField.ALBUM -> Rule("ALBUM", listOf("TALB"))
        MediaConvertMetadataField.GENRE -> Rule("GENRE", listOf("TCON", "STYLE", "SUBGENRE", "MOOD"))
        MediaConvertMetadataField.YEAR -> Rule("DATE", listOf("YEAR", "TYER", "TDAT", "TIME"))
        MediaConvertMetadataField.COMMENT -> Rule("COMMENT", listOf("COMM", "DESCRIPTION"))
        MediaConvertMetadataField.ALBUM_ARTIST -> Rule("ALBUMARTIST", listOf("TPE2", "ALBUM ARTIST", "AART"))
        MediaConvertMetadataField.COMPOSER -> Rule("COMPOSER", listOf("TCOM", "©WRT"))
        MediaConvertMetadataField.TRACK_NUMBER -> Rule("TRACKNUMBER", listOf("TRACK", "TRCK"))
        MediaConvertMetadataField.DISC_NUMBER -> Rule("DISCNUMBER", listOf("DISC", "TPOS", "DISKNUMBER"))
        MediaConvertMetadataField.LYRICIST -> Rule("LYRICIST", listOf("TEXT", "WRITER", "LYRICS BY"))
        MediaConvertMetadataField.COPYRIGHT -> Rule("COPYRIGHT", listOf("TCOP", "CPRO", "©CPY"))
        MediaConvertMetadataField.LANGUAGE -> Rule("LANGUAGE", listOf("TLAN"))
        MediaConvertMetadataField.AUTHOR -> null
    }

    fun decode(properties: Map<String, List<String>>): MediaConvertMetadata {
        val normalized = properties.mapKeys { it.key.uppercase(Locale.ROOT) }
        fun value(field: MediaConvertMetadataField): String {
            val rule = rule(field) ?: return ""
            return (listOf(rule.key) + rule.aliases).firstNotNullOfOrNull { key ->
                normalized[key]?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }?.joinToString(" / ")
            }.orEmpty()
        }
        return MediaConvertMetadata.audioFields.fold(MediaConvertMetadata()) { metadata, field ->
            metadata.withValue(field, value(field))
        }.copy(editedFields = emptySet())
    }

    /** Only edited fields are changed; unmentioned lyrics, pictures and custom tags stay native. */
    fun updates(metadata: MediaConvertMetadata, existing: Map<String, List<String>>): Map<String, String> {
        val existingKeys = existing.keys.map { it.uppercase(Locale.ROOT) }.toSet()
        return buildMap {
            for (field in MediaConvertMetadata.audioFields.filter { it in metadata.editedFields }) {
                val rule = requireNotNull(rule(field))
                put(rule.key, metadata.value(field))
                // A raw frame identifier is not always a TagLib property key. Clear only aliases
                // the library actually returned, avoiding an invented TXXX frame on ID3 files.
                rule.aliases.filter { it in existingKeys }.forEach { put(it, "") }
            }
        }
    }

    fun verifies(requested: MediaConvertMetadata, actual: MediaConvertMetadata): Boolean =
        MediaConvertMetadata.audioFields.filter { it in requested.editedFields }.all { field ->
            matches(field, requested.value(field), actual.value(field))
        }

    /** Compare the values that each container can represent, consistently at both readback stages. */
    fun matches(field: MediaConvertMetadataField, requested: String, actual: String): Boolean {
        val expected = requested.trim()
        val saved = actual.trim()
        if (expected == saved) return true
        return when (field) {
            MediaConvertMetadataField.GENRE ->
                expected.toIntOrNull()?.let { id3Genres.getOrNull(it) } == saved && saved.isNotEmpty()
            MediaConvertMetadataField.YEAR -> expected.replace('T', ' ') == saved.replace('T', ' ')
            MediaConvertMetadataField.TRACK_NUMBER, MediaConvertMetadataField.DISC_NUMBER -> {
                val expectedPair = numberPair(expected)
                expectedPair != null && expectedPair == numberPair(saved)
            }
            else -> false
        }
    }

    private fun numberPair(value: String): Pair<Int, Int>? {
        val parts = value.split('/')
        if (parts.size !in 1..2 || parts.any { part -> part.isEmpty() || part.any { it !in '0'..'9' } }) return null
        val number = parts[0].toIntOrNull() ?: return null
        val total = if (parts.size == 2) parts[1].toIntOrNull() ?: return null else 0
        return number to total
    }

    // Standard ID3 indices, matching the pinned TagLib id3v1genres.cpp table.
    private val id3Genres = """
        Blues|Classic Rock|Country|Dance|Disco|Funk|Grunge|Hip-Hop
        Jazz|Metal|New Age|Oldies|Other|Pop|R&B|Rap
        Reggae|Rock|Techno|Industrial|Alternative|Ska|Death Metal|Pranks
        Soundtrack|Euro-Techno|Ambient|Trip-Hop|Vocal|Jazz-Funk|Fusion|Trance
        Classical|Instrumental|Acid|House|Game|Sound Clip|Gospel|Noise
        Alternative Rock|Bass|Soul|Punk|Space|Meditative|Instrumental Pop|Instrumental Rock
        Ethnic|Gothic|Darkwave|Techno-Industrial|Electronic|Pop-Folk|Eurodance|Dream
        Southern Rock|Comedy|Cult|Gangsta|Top 40|Christian Rap|Pop/Funk|Jungle
        Native American|Cabaret|New Wave|Psychedelic|Rave|Showtunes|Trailer|Lo-Fi
        Tribal|Acid Punk|Acid Jazz|Polka|Retro|Musical|Rock & Roll|Hard Rock
        Folk|Folk Rock|National Folk|Swing|Fast Fusion|Bebop|Latin|Revival
        Celtic|Bluegrass|Avant-garde|Gothic Rock|Progressive Rock|Psychedelic Rock|Symphonic Rock|Slow Rock
        Big Band|Chorus|Easy Listening|Acoustic|Humour|Speech|Chanson|Opera
        Chamber Music|Sonata|Symphony|Booty Bass|Primus|Porn Groove|Satire|Slow Jam
        Club|Tango|Samba|Folklore|Ballad|Power Ballad|Rhythmic Soul|Freestyle
        Duet|Punk Rock|Drum Solo|A Cappella|Euro-House|Dancehall|Goa|Drum & Bass
        Club-House|Hardcore Techno|Terror|Indie|Britpop|Worldbeat|Polsk Punk|Beat
        Christian Gangsta Rap|Heavy Metal|Black Metal|Crossover|Contemporary Christian|Christian Rock|Merengue|Salsa
        Thrash Metal|Anime|Jpop|Synthpop|Abstract|Art Rock|Baroque|Bhangra
        Big Beat|Breakbeat|Chillout|Downtempo|Dub|EBM|Eclectic|Electro
        Electroclash|Emo|Experimental|Garage|Global|IDM|Illbient|Industro-Goth
        Jam Band|Krautrock|Leftfield|Lounge|Math Rock|New Romantic|Nu-Breakz|Post-Punk
        Post-Rock|Psytrance|Shoegaze|Space Rock|Trop Rock|World Music|Neoclassical|Audiobook
        Audio Theatre|Neue Deutsche Welle|Podcast|Indie Rock|G-Funk|Dubstep|Garage Rock|Psybient
    """.trimIndent().lines().flatMap { it.split('|') }
}
