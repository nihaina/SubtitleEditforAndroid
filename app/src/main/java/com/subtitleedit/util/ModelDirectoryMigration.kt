package com.subtitleedit.util

import java.io.File
import java.io.IOException

/** Finds and migrates complete, managed model entries between software directories. */
object ModelDirectoryMigration {
    data class MigrationResult(
        val migrated: List<File>,
        val skipped: List<File>
    )

    /** Returns only top-level entries recognized by the model management validators. */
    fun findRecognizedModels(source: File): List<File> {
        if (!source.isDirectory) return emptyList()
        return source.listFiles().orEmpty()
            .filter { it.name.isNotBlank() && !it.name.startsWith(".") }
            .filter(::isRecognizedModel)
    }

    /** Moves recognized model entries with the app's standard file-move service. */
    fun migrate(source: File, destination: File): MigrationResult {
        val entries = findRecognizedModels(source)
        if (entries.isEmpty()) return MigrationResult(emptyList(), emptyList())
        if (source.canonicalFile == destination.canonicalFile) {
            return MigrationResult(emptyList(), entries)
        }
        if (!destination.exists() && !destination.mkdirs()) {
            throw IOException("无法创建模型目录：${destination.absolutePath}")
        }
        if (!destination.isDirectory) throw IOException("模型目录不是文件夹：${destination.absolutePath}")

        val migrated = mutableListOf<File>()
        val skipped = mutableListOf<File>()
        entries.forEach { sourceEntry ->
            val destinationEntry = File(destination, sourceEntry.name)
            val destinationExisted = destinationEntry.exists()
            try {
                if (destinationExisted) throw IOException("目标模型已存在：${destinationEntry.name}")
                if (sourceEntry.isDirectory && sourceEntry.name == ModelDownloader.SEPARATION_DIRECTORY_NAME) {
                    migrateSeparationModel(sourceEntry, destinationEntry)
                } else {
                    FileTransferManager.move(sourceEntry, destination)
                }
                migrated += destinationEntry
            } catch (_: Exception) {
                if (!destinationExisted && destinationEntry.exists()) destinationEntry.deleteRecursively()
                skipped += sourceEntry
            }
        }
        return MigrationResult(migrated, skipped)
    }

    private fun migrateSeparationModel(source: File, destination: File) {
        val models = source.listFiles().orEmpty().filter {
            it.isFile && it.name.equals("htdemucs_fp16weights.onnx", ignoreCase = true) &&
                it.length() >= 1024L * 1024L
        }
        if (models.isEmpty()) throw IOException("未找到人声分离模型文件")
        if (!destination.exists() && !destination.mkdirs()) {
            throw IOException("无法创建模型目录：${destination.absolutePath}")
        }
        models.forEach { model ->
            FileTransferManager.move(model, destination)
        }
    }

    private fun isRecognizedModel(entry: File): Boolean {
        if (!entry.isDirectory) {
            return entry.name.equals("htdemucs_fp16weights.onnx", ignoreCase = true) &&
                entry.length() >= 1024L * 1024L
        }
        val senseVoice = ModelDownloader.SENSEVOICE_CPU_MODEL
        if (entry.name == senseVoice.directoryName &&
            ModelDownloader.hasSenseVoiceModel(entry, ModelDownloader.SenseVoiceArchitecture.ONNX)
        ) return true
        if (ModelDownloader.SENSEVOICE_NPU_MODELS.any { it.directoryName == entry.name } &&
            ModelDownloader.SENSEVOICE_NPU_MODELS.any {
                it.directoryName == entry.name &&
                    ModelDownloader.hasSenseVoiceModel(entry, ModelDownloader.SenseVoiceArchitecture.QNN)
            }
        ) return true
        if (ModelDownloader.WHISPER_MODELS.any { it.directoryName == entry.name &&
                ModelDownloader.hasWhisperModel(entry, it.id) }) return true
        if (ModelDownloader.PARAKEET_MODELS.any { it.directoryName == entry.name &&
                ModelDownloader.hasParakeetModel(entry, it.architecture) }) return true
        if (ModelDownloader.QWEN3_ASR_MODELS.any { it.directoryName == entry.name &&
                ModelDownloader.hasQwen3AsrModel(entry) }) return true
        if (entry.name == Qwen3ForcedAlignerModelFiles.DIRECTORY_NAME &&
            Qwen3ForcedAlignerModelFiles.findCompleteGraph(entry) != null) return true
        if (InternalModelExport.Kind.entries.any { it.directoryName == entry.name &&
                InternalModelExport.completeSenseVoiceFiles(entry) != null }) return true
        if (entry.name == ModelDownloader.SEPARATION_DIRECTORY_NAME) {
            return entry.listFiles().orEmpty().any {
                it.isFile && it.name.equals("htdemucs_fp16weights.onnx", ignoreCase = true) &&
                    it.length() >= 1024L * 1024L
            }
        }
        return false
    }

}
