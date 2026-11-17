package com.subtitleedit.library

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale

/** Reads an embedded Matroska cover without decoding or scanning the movie's packets. */
internal object MatroskaCoverReader {
    private const val MAX_COVER_BYTES = 16 * 1024 * 1024
    private const val MAX_ELEMENTS = 16_384
    private const val MAX_TEXT_BYTES = 4_096
    private const val EBML = 0x1A45DFA3L
    private const val SEGMENT = 0x18538067L
    private const val SEEK_HEAD = 0x114D9B74L
    private const val SEEK = 0x4DBBL
    private const val SEEK_ID = 0x53ABL
    private const val SEEK_POSITION = 0x53ACL
    private const val ATTACHMENTS = 0x1941A469L
    private const val ATTACHED_FILE = 0x61A7L
    private const val FILE_NAME = 0x466EL
    private const val FILE_MIME_TYPE = 0x4660L
    private const val FILE_DATA = 0x465CL

    fun read(file: File): ByteArray? = try {
        RandomAccessFile(file, "r").use { Parser(it).read() }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private data class Element(val id: Long, val data: Long, val end: Long?)
    private data class SeekReference(val id: Long, val position: Long)
    private data class Cover(val position: Long, val length: Int, val rank: Int)

    private class Parser(private val input: RandomAccessFile) {
        private val fileEnd = input.length()
        private var elements = 0
        private val visitedSeekHeads = hashSetOf<Long>()

        fun read(): ByteArray? {
            if (fileEnd == 0L) return null
            val header = element(fileEnd)
            if (header.id != EBML) return null
            var position = knownEnd(header)
            while (position < fileEnd) {
                input.seek(position)
                val item = element(fileEnd)
                if (item.id == SEGMENT) return segment(item.data, item.end ?: fileEnd)
                position = knownEnd(item)
            }
            return null
        }

        private fun segment(start: Long, end: Long): ByteArray? {
            var position = start
            while (position < end) {
                input.seek(position)
                val item = element(end)
                when (item.id) {
                    ATTACHMENTS -> attachments(item)?.let { return bytes(it) }
                    SEEK_HEAD -> indexedAttachments(item, start, end)?.let { return it }
                }
                // An unknown-size Cluster cannot be skipped safely. Its SeekHead, when present,
                // was already checked above; never search for attachment IDs inside movie data.
                position = item.end ?: return null
            }
            return null
        }

        private fun indexedAttachments(head: Element, segmentStart: Long, segmentEnd: Long): ByteArray? {
            val pending = ArrayDeque<Element>()
            pending.add(head)
            while (pending.isNotEmpty()) {
                val current = pending.removeFirst()
                if (!visitedSeekHeads.add(current.data)) continue
                for (reference in seekReferences(current)) {
                    if (reference.id != ATTACHMENTS && reference.id != SEEK_HEAD) continue
                    if (reference.position >= segmentEnd - segmentStart) continue
                    input.seek(segmentStart + reference.position)
                    val target = element(segmentEnd)
                    if (target.id != reference.id) continue
                    if (target.id == SEEK_HEAD) pending.add(target)
                    else attachments(target)?.let { return bytes(it) }
                }
            }
            return null
        }

        private fun seekReferences(head: Element): List<SeekReference> {
            val result = mutableListOf<SeekReference>()
            children(head) { seek ->
                if (seek.id == SEEK) {
                    var id: Long? = null
                    var position: Long? = null
                    children(seek) { field ->
                        when (field.id) {
                            SEEK_ID -> id = unsigned(field, 4)
                            SEEK_POSITION -> position = unsigned(field, 8)
                        }
                    }
                    if (id != null && position != null) result += SeekReference(id!!, position!!)
                }
            }
            return result
        }

        private fun attachments(container: Element): Cover? {
            var best: Cover? = null
            children(container) { item ->
                if (item.id == ATTACHED_FILE) {
                    var name: String? = null
                    var mime: String? = null
                    var data: Element? = null
                    children(item) { field ->
                        when (field.id) {
                            FILE_NAME -> name = text(field)
                            FILE_MIME_TYPE -> mime = text(field)
                            FILE_DATA -> data = field
                        }
                    }
                    val rank = coverRank(name)
                    val picture = data
                    if (rank != null && mime?.startsWith("image/", ignoreCase = true) == true && picture != null) {
                        val length = knownEnd(picture) - picture.data
                        if (length in 1L..MAX_COVER_BYTES.toLong() && (best == null || rank < best!!.rank)) {
                            best = Cover(picture.data, length.toInt(), rank)
                        }
                    }
                }
            }
            return best
        }

        private fun coverRank(name: String?): Int? = when (name?.lowercase(Locale.ROOT)?.substringBeforeLast('.')) {
            "cover" -> 0
            "cover_land" -> 1
            "small_cover" -> 2
            "small_cover_land" -> 3
            else -> null
        }

        private fun bytes(cover: Cover): ByteArray {
            input.seek(cover.position)
            return ByteArray(cover.length).also(input::readFully)
        }

        private fun text(item: Element): String? {
            val length = knownEnd(item) - item.data
            if (length !in 1L..MAX_TEXT_BYTES.toLong()) return null
            input.seek(item.data)
            return ByteArray(length.toInt()).also(input::readFully).toString(Charsets.UTF_8).trimEnd('\u0000')
        }

        private fun unsigned(item: Element, maximumBytes: Int): Long {
            val length = knownEnd(item) - item.data
            if (length !in 1L..maximumBytes.toLong()) malformed()
            input.seek(item.data)
            var value = 0L
            repeat(length.toInt()) {
                val next = input.readUnsignedByte()
                if (value > (Long.MAX_VALUE - next) / 256L) malformed()
                value = value * 256L + next
            }
            return value
        }

        private inline fun children(parent: Element, visit: (Element) -> Unit) {
            val end = knownEnd(parent)
            var position = parent.data
            while (position < end) {
                input.seek(position)
                val child = element(end)
                position = knownEnd(child)
                visit(child)
            }
        }

        private fun element(parentEnd: Long): Element {
            if (++elements > MAX_ELEMENTS || input.filePointer >= parentEnd) malformed()
            val firstId = input.readUnsignedByte()
            val idLength = vintLength(firstId, 4)
            if (parentEnd - input.filePointer < idLength - 1L) malformed()
            var id = firstId.toLong()
            repeat(idLength - 1) { id = (id shl 8) or input.readUnsignedByte().toLong() }
            if (input.filePointer >= parentEnd) malformed()
            val firstSize = input.readUnsignedByte()
            val sizeLength = vintLength(firstSize, 8)
            if (parentEnd - input.filePointer < sizeLength - 1L) malformed()
            var size = (firstSize and (0xff ushr sizeLength)).toLong()
            repeat(sizeLength - 1) { size = (size shl 8) or input.readUnsignedByte().toLong() }
            val data = input.filePointer
            val unknown = size == (1L shl (sizeLength * 7)) - 1L
            if (!unknown && size > parentEnd - data) malformed()
            return Element(id, data, if (unknown) null else data + size)
        }

        private fun vintLength(first: Int, maximum: Int): Int {
            var mask = 0x80
            for (length in 1..maximum) {
                if (first and mask != 0) return length
                mask = mask ushr 1
            }
            malformed()
        }

        private fun knownEnd(item: Element): Long = item.end ?: malformed()
        private fun malformed(): Nothing = throw IOException("Invalid or unsupported Matroska element")
    }
}
