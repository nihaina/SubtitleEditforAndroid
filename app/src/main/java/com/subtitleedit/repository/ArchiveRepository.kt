package com.subtitleedit.repository

import com.subtitleedit.util.ArchiveManager
import java.io.File

internal interface ArchiveRepository {
    fun compressionMethods(format: ArchiveManager.CreateFormat): List<ArchiveManager.CompressionMethod>
    fun encryptionMethods(format: ArchiveManager.CreateFormat): List<ArchiveManager.EncryptionMethod>
    fun defaultEncryptionMethod(format: ArchiveManager.CreateFormat): ArchiveManager.EncryptionMethod?
    fun outputExtension(
        format: ArchiveManager.CreateFormat,
        method: ArchiveManager.CompressionMethod
    ): String

    fun isRecognizedArchive(file: File): Boolean
    fun isSupportedArchive(file: File): Boolean

    fun createArchive(
        sources: List<File>,
        destination: File,
        format: ArchiveManager.CreateFormat,
        method: ArchiveManager.CompressionMethod,
        password: CharArray? = null,
        encryptionMethod: ArchiveManager.EncryptionMethod? = null,
        splitSizeBytes: Long? = null,
        checkCancelled: () -> Unit = {},
        onProgress: (generatedBytes: Long, sourceBytes: Long) -> Unit = { _, _ -> },
        onDetailedProgress: ((ArchiveManager.CompressionProgress) -> Unit)? = null,
        onCommitted: () -> Unit = {}
    )

    fun listEntries(
        archive: File,
        password: CharArray? = null,
        limits: ArchiveManager.ExtractionLimits = ArchiveManager.ExtractionLimits()
    ): List<ArchiveManager.EntryInfo>

    fun testArchive(
        archive: File,
        password: CharArray? = null,
        limits: ArchiveManager.ExtractionLimits = ArchiveManager.ExtractionLimits(),
        checkCancelled: () -> Unit = {},
        onProgress: (completed: Long, total: Long) -> Unit = { _, _ -> }
    ): ArchiveManager.TestResult

    fun extractArchive(
        archive: File,
        destination: File,
        password: CharArray? = null,
        conflictPolicy: ArchiveManager.ConflictPolicy = ArchiveManager.ConflictPolicy.FAIL,
        conflictPolicies: Map<String, ArchiveManager.ConflictPolicy> = emptyMap(),
        limits: ArchiveManager.ExtractionLimits = ArchiveManager.ExtractionLimits(),
        onProgress: ((ArchiveManager.ProgressPhase, Long, Long) -> Unit)? = null,
        onConflict: ((ArchiveManager.DestinationConflict) -> ArchiveManager.ConflictResolution)? = null,
        onCurrentFile: (File) -> Unit = {},
        checkCancelled: () -> Unit = {}
    ): ArchiveManager.ExtractResult
}
