package com.subtitleedit.util

import java.io.File

/** This export stores external tensors in <graph filename>.data, alongside the graph. */
internal object Qwen3ForcedAlignerModelFiles {
    const val DIRECTORY_NAME = "qwen3-forced-aligner"

    fun findCompleteGraph(directory: File): File? = directory.listFiles()
        ?.firstOrNull { it.name.endsWith(".onnx", ignoreCase = true) && isComplete(it) }

    fun isConfigured(graph: File?, privateFilesDirectory: File): Boolean =
        isComplete(graph) && runCatching {
            !graph!!.canonicalFile.toPath().startsWith(privateFilesDirectory.canonicalFile.toPath())
        }.getOrDefault(false)

    fun configuredGraph(savedGraph: File?, privateFilesDirectory: File): File? =
        savedGraph?.takeIf { isConfigured(it, privateFilesDirectory) }

    fun graphName(names: List<String>): String {
        require(names.size == 2 && names.distinct().size == 2) {
            "请同时选择一个 .onnx 模型和一个配套的 .onnx.data 权重文件（共两个）"
        }
        require(names.all { it.isNotBlank() && it != "." && it != ".." &&
            it.none { ch -> ch == '/' || ch == '\\' || ch == ':' || ch == '\u0000' }
        }) { "模型文件名无效，请保留导出时的原始文件名" }
        val graph = names.singleOrNull { it.endsWith(".onnx", ignoreCase = true) }
        require(graph != null && names.contains("$graph.data")) {
            "请选择同次导出的 .onnx 与同名 .onnx.data 文件，并保留原始文件名"
        }
        return graph
    }

    fun dataFile(graph: File): File = File(graph.parentFile, "${graph.name}.data")

    fun isComplete(graph: File?): Boolean = graph != null &&
        graph.isFile && graph.canRead() && graph.length() > 0L &&
        dataFile(graph).let { it.isFile && it.canRead() && it.length() > 0L }
}
