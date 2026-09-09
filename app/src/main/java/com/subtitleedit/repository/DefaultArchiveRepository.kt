package com.subtitleedit.repository

import com.subtitleedit.util.ArchiveManager
import java.io.File

internal class DefaultArchiveRepository : ArchiveRepository {
    override fun compressionMethods(format: ArchiveManager.CreateFormat) =
        ArchiveManager.compressionMethods(format)

    override fun encryptionMethods(format: ArchiveManager.CreateFormat) =
        ArchiveManager.encryptionMethods(format)

    override fun defaultEncryptionMethod(format: ArchiveManager.CreateFormat) =
        ArchiveManager.defaultEncryptionMethod(format)

    override fun outputExtension(
        format: ArchiveManager.CreateFormat,
        method: ArchiveManager.CompressionMethod
    ) = ArchiveManager.outputExtension(format, method)

    override fun isRecognizedArchive(file: File) = ArchiveManager.isRecognizedArchive(file)
    override fun isSupportedArchive(file: File) = ArchiveManager.isSupportedArchive(file)
    override fun requiresStreamingConflictResolution(file: File) =
        ArchiveManager.requiresStreamingConflictResolution(file)

    override fun createArchive(
        sources: List<File>,
        destination: File,
        format: ArchiveManager.CreateFormat,
        method: ArchiveManager.CompressionMethod,
        password: CharArray?,
        encryptionMethod: ArchiveManager.EncryptionMethod?,
        splitSizeBytes: Long?,
        checkCancelled: () -> Unit,
        onProgress: (Long, Long) -> Unit,
        onDetailedProgress: ((ArchiveManager.CompressionProgress) -> Unit)?,
        onCommitted: () -> Unit
    ) = ArchiveManager.createArchive(
        sources, destination, format, method, password, encryptionMethod, splitSizeBytes,
        checkCancelled, onProgress, onDetailedProgress, onCommitted
    )

    override fun listEntries(
        archive: File,
        password: CharArray?,
        limits: ArchiveManager.ExtractionLimits
    ) = ArchiveManager.listEntries(archive, password, limits)

    override fun testArchive(
        archive: File,
        password: CharArray?,
        limits: ArchiveManager.ExtractionLimits
    ) = ArchiveManager.testArchive(archive, password, limits)

    override fun findDestinationConflicts(
        archive: File,
        destination: File,
        password: CharArray?,
        limits: ArchiveManager.ExtractionLimits,
        onProgress: ((ArchiveManager.ProgressPhase, Long, Long) -> Unit)?
    ) = ArchiveManager.findDestinationConflicts(archive, destination, password, limits, onProgress)

    override fun extractArchive(
        archive: File,
        destination: File,
        password: CharArray?,
        conflictPolicy: ArchiveManager.ConflictPolicy,
        conflictPolicies: Map<String, ArchiveManager.ConflictPolicy>,
        conflictsPrechecked: Boolean,
        limits: ArchiveManager.ExtractionLimits,
        onProgress: ((ArchiveManager.ProgressPhase, Long, Long) -> Unit)?,
        onConflict: ((ArchiveManager.DestinationConflict) -> ArchiveManager.ConflictResolution)?,
        checkCancelled: () -> Unit
    ) = ArchiveManager.extractArchive(
        archive, destination, password, conflictPolicy, conflictPolicies, conflictsPrechecked,
        limits, onProgress, onConflict, checkCancelled
    )
}
