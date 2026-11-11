package com.subtitleedit

import java.util.UUID

/** Pure grid operations preserve blank cells and move results together with their video owner. */
internal object MediaMuxQueue {
    fun add(rows: List<MediaMuxRow>, files: List<MediaMuxFile>, newId: () -> String = { UUID.randomUUID().toString() }): List<MediaMuxRow> {
        val result = rows.toMutableList()
        for (file in files) {
            var index = result.indexOfFirst { if (file.kind == MediaMuxColumn.VIDEO) it.video == null else it.audio == null }
            if (index < 0) {
                result += MediaMuxRow(newId())
                index = result.lastIndex
            }
            result[index] = if (file.kind == MediaMuxColumn.VIDEO) result[index].copy(video = file, output = null)
                else result[index].copy(audio = file)
        }
        return result
    }

    fun remove(rows: List<MediaMuxRow>, rowId: String, column: MediaMuxColumn): List<MediaMuxRow> = rows.map {
        if (it.id != rowId) it else if (column == MediaMuxColumn.VIDEO) it.copy(video = null, output = null) else it.copy(audio = null)
    }

    fun removeOutput(rows: List<MediaMuxRow>, rowId: String): List<MediaMuxRow> = rows.map {
        if (it.id == rowId) it.copy(output = null) else it
    }

    fun move(rows: List<MediaMuxRow>, column: MediaMuxColumn, from: Int, to: Int): List<MediaMuxRow> {
        if (from !in rows.indices || to !in rows.indices || from == to) return rows
        return if (column == MediaMuxColumn.VIDEO) {
            val values = rows.map { it.video to it.output }.toMutableList()
            values.add(to, values.removeAt(from))
            rows.mapIndexed { index, row -> row.copy(video = values[index].first, output = values[index].second) }
        } else {
            val values = rows.map { it.audio }.toMutableList()
            values.add(to, values.removeAt(from))
            rows.mapIndexed { index, row -> row.copy(audio = values[index]) }
        }
    }
}
