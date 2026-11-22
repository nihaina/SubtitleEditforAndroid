package com.subtitleedit.work

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.subtitleedit.SubtitleEditApplication
import com.subtitleedit.util.ModelDirectoryManager
import com.subtitleedit.util.SettingsManager
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalModelImportDeviceTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun cachedDemixModelIsSelectedWithoutAWorkManagerNetworkConstraint() = runBlocking(Dispatchers.IO) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SubtitleEditApplication
        assumeTrue(app.packageName != "com.subtitleedit")
        val settings = SettingsManager.getInstance(app)
        val previousDirectory = settings.getSoftwareDirectory()
        val previousUri = settings.getDemixModelUri("general")
        val previousType = settings.getDemixModelType()
        val scheduler = app.dependencies.taskWorkScheduler
        val model = File(folder.root, "models/separation/htdemucs_fp16weights.onnx")
        check(model.parentFile?.mkdirs() == true)
        RandomAccessFile(model, "rw").use { it.setLength(1024L * 1024L) }
        ModelDirectoryManager.setSoftwareDirectory(folder.root)
        try {
            val workId = scheduler.enqueueGeneralModelDownload()
            val manager = WorkManager.getInstance(app)
            val request = manager.getWorkInfoById(workId).get()
            assertEquals(NetworkType.NOT_REQUIRED, request.constraints.requiredNetworkType)
            val completed = withTimeout(30_000L) {
                manager.getWorkInfoByIdFlow(workId).filterNotNull().first { it.state.isFinished }
            }
            assertEquals(completed.outputData.getString(ModelDownloadWorker.KEY_ERROR), WorkInfo.State.SUCCEEDED, completed.state)
            assertEquals(model.absolutePath, completed.outputData.getString(ModelDownloadWorker.KEY_MODEL_FILE))
            assertEquals("本地模型已导入", completed.outputData.getString(ModelDownloadWorker.KEY_MESSAGE))
            assertEquals(Uri.fromFile(model).toString(), settings.getDemixModelUri("general"))
        } finally {
            ModelDirectoryManager.setSoftwareDirectory(previousDirectory)
            settings.setDemixModelUri("general", previousUri)
            settings.setDemixModelType(previousType)
        }
    }
}
