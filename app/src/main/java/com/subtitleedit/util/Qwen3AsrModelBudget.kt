package com.subtitleedit.util

import java.io.File
import java.io.RandomAccessFile

/** Reads the same fifth decoder input / second dimension used by sherpa-onnx v1.13.8.
 * Seek over graph nodes and weights: never create a second ORT session or load model bytes.
 * Schema: ONNX ModelProto.graph -> GraphProto.input -> ValueInfoProto.type -> tensor shape.
 */
internal object Qwen3AsrModelBudget {
    /** sherpa-onnx uses this runtime default when the exported KV dimension is symbolic. */
    const val DEFAULT_DYNAMIC_CACHE_LENGTH = 512

    fun readCacheLength(decoder: File): Int = RandomAccessFile(decoder, "r").use { file ->
        val reader = ProtoReader(file)
        val graph = reader.message(file.length(), 7) ?: error("Qwen decoder 缺少 ONNX graph")
        val input = reader.message(graph, 11, occurrence = 4)
            ?: error("Qwen decoder 缺少第五个 KV cache 输入")
        val type = reader.message(input, 2) ?: error("Qwen decoder KV cache 缺少类型")
        val tensor = reader.message(type, 1) ?: error("Qwen decoder KV cache 不是 tensor")
        val shape = reader.message(tensor, 2) ?: error("Qwen decoder KV cache 缺少 shape")
        val dimension = reader.message(shape, 1, occurrence = 1)
            ?: error("Qwen decoder KV cache 缺少序列维度")
        var value: Long? = null
        while (file.filePointer < dimension) {
            val tag = reader.tag(dimension)
            when (tag) {
                8 -> value = reader.varint(dimension) // Dimension.dim_value
                18 -> { reader.skip(tag, dimension); value = null } // symbolic dimension
                else -> reader.skip(tag, dimension)
            }
        }
        // Dynamic/non-positive dimensions use the configured length, just as sherpa does.
        value?.takeIf { it > 0 }?.let {
            require(it <= Int.MAX_VALUE) { "Qwen decoder KV cache 长度超出支持范围" }
            it.toInt()
        } ?: DEFAULT_DYNAMIC_CACHE_LENGTH
    }

    private class ProtoReader(private val file: RandomAccessFile) {
        fun tag(end: Long): Int {
            val tag = varint(end)
            require(tag in 8..Int.MAX_VALUE.toLong()) { "Qwen decoder ONNX 字段无效" }
            return tag.toInt()
        }

        fun varint(end: Long): Long {
            var result = 0L
            for (shift in 0..63 step 7) {
                require(file.filePointer < end) { "Qwen decoder ONNX 文件不完整" }
                val byte = file.readUnsignedByte()
                require(shift < 63 || byte <= 1) { "Qwen decoder ONNX varint 溢出" }
                result = result or ((byte and 127).toLong() shl shift)
                if (byte and 128 == 0) return result
            }
            error("Qwen decoder ONNX varint 无效")
        }

        private fun limit(length: Long, end: Long): Long {
            require(length >= 0 && length <= end - file.filePointer) {
                "Qwen decoder ONNX 字段越界或文件不完整"
            }
            return file.filePointer + length
        }

        fun message(end: Long, field: Int, occurrence: Int = 0): Long? {
            var found = 0
            while (file.filePointer < end) {
                val tag = tag(end)
                if (tag ushr 3 == field) {
                    require(tag and 7 == 2) { "Qwen decoder ONNX 字段类型错误" }
                    val childEnd = limit(varint(end), end)
                    if (found++ == occurrence) return childEnd
                    file.seek(childEnd)
                } else skip(tag, end)
            }
            return null
        }

        fun skip(tag: Int, end: Long) {
            when (tag and 7) {
                0 -> varint(end)
                1 -> file.seek(limit(8, end))
                2 -> file.seek(limit(varint(end), end))
                5 -> file.seek(limit(4, end))
                else -> error("Qwen decoder ONNX wire type 不支持")
            }
        }
    }
}
