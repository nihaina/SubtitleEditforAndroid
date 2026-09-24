package com.subtitleedit.util

import java.io.File

/**
 * JNI facade for the HuggingFace `tokenizers` Rust library.
 *
 * The native library is optional because the repository does not vendor a Rust toolchain or
 * prebuilt tokenizers binaries. When present, it reads the official Qwen tokenizer directory and
 * applies the official forced-aligner audio/timestamp wrapper before tokenization.
 */
internal class QwenHuggingFaceTokenizer private constructor(
    private val handle: Long,
) : AutoCloseable {
    data class EncodedText(
        val inputIds: LongArray,
        val timestampPositions: IntArray,
        val units: List<String>,
    )

    fun encodeForForcedAlignment(text: String, language: String): EncodedText {
        require(text.isNotBlank()) { "Qwen 对齐文本不能为空" }
        val result = nativeEncode(handle, text, language)
            ?: error("Qwen tokenizer 无法生成 ForcedAligner 输入；请检查文本和 tokenizer 版本")
        require(result.size >= 3) { "Qwen tokenizer 返回数据不完整" }
        val ids = result[0] as? LongArray ?: error("Qwen tokenizer input_ids 类型错误")
        val positions = result[1] as? IntArray ?: error("Qwen tokenizer timestamp positions 类型错误")
        @Suppress("UNCHECKED_CAST")
        val units = result[2] as? Array<String> ?: error("Qwen tokenizer units 类型错误")
        require(positions.size == units.size * 2) {
            "Qwen tokenizer 未为每个文本单元生成两个 timestamp 占位符"
        }
        return EncodedText(ids, positions, units.toList())
    }

    override fun close() {
        nativeDestroy(handle)
    }

    companion object {
        private const val LIBRARY = "qwen_tokenizer"
        private val REQUIRED_FILES = listOf(
            "chat_template.json",
            "config.json",
            "merges.txt",
            "preprocessor_config.json",
            "tokenizer_config.json",
            "vocab.json",
        )

        @Volatile private var loadAttempted = false
        @Volatile private var available = false

        fun open(directory: File): QwenHuggingFaceTokenizer {
            require(directory.isDirectory) { "Qwen tokenizer 目录不存在：${directory.absolutePath}" }
            val missing = REQUIRED_FILES.filterNot { File(directory, it).isFile }
            require(missing.isEmpty()) { "Qwen tokenizer 缺少官方文件：${missing.joinToString()}" }
            ensureLoaded()
            check(available) {
                "未安装 libqwen_tokenizer；需要使用 HuggingFace tokenizers Rust Android/JNI 构建产物"
            }
            val handle = nativeCreate(directory.absolutePath)
            check(handle != 0L) { "无法初始化 Qwen HuggingFace tokenizer" }
            return QwenHuggingFaceTokenizer(handle)
        }

        fun isAvailable(): Boolean {
            ensureLoaded()
            return available
        }

        private fun ensureLoaded() {
            if (loadAttempted) return
            synchronized(this) {
                if (loadAttempted) return
                available = runCatching {
                    System.loadLibrary(LIBRARY)
                    true
                }.getOrDefault(false)
                loadAttempted = true
            }
        }

        @JvmStatic private external fun nativeCreate(directory: String): Long
        @JvmStatic private external fun nativeEncode(handle: Long, text: String, language: String): Array<Any>?
        @JvmStatic private external fun nativeDestroy(handle: Long)
    }
}
