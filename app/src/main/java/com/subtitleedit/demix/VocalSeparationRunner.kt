package com.subtitleedit.demix

import java.io.File

internal interface VocalSeparationRunner {
    fun separate(
        pcmFile: File,
        outputDir: File,
        outputBaseName: String,
        stems: Set<VocalSeparationEngine.Stem>,
        isCancelled: () -> Boolean,
        onProgress: (completedChunks: Int, totalChunks: Int) -> Unit
    ): VocalSeparationEngine.Result
}
