package com.subtitleedit.usecase

import com.subtitleedit.repository.ModelRepository
import com.subtitleedit.util.ModelDownloader
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class DownloadGeneralModelUseCase(
    private val repository: ModelRepository,
    private val selectModel: (File) -> Unit
) {
    suspend operator fun invoke(onProgress: (ModelDownloader.Progress) -> Unit): File {
        val file = repository.downloadDemixGeneralModel(onProgress)
        currentCoroutineContext().ensureActive()
        check(file.isFile) { "下载完成但未找到模型文件" }
        selectModel(file)
        return file
    }
}
