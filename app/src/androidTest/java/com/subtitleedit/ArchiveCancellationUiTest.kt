package com.subtitleedit

import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.repository.ArchiveRepository
import com.subtitleedit.repository.DefaultArchiveRepository
import com.subtitleedit.util.ArchiveManager
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArchiveCancellationUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun prepareDialogHost() {
        compose.activityRule.scenario.onActivity { activity ->
            // Keep these controller tests independent of file-browser loading and navigation.
            activity.setContent { Text("操作弹窗测试") }
        }
        compose.waitForIdle()
        compose.onNodeWithText("操作弹窗测试").assertExists()
    }

    @Test
    fun archiveTestCancelButtonStopsTheWorkerWithoutReportingSuccessOrFailure() {
        prepareDialogHost()
        val root = Files.createTempDirectory(compose.activity.cacheDir.toPath(), "archive-cancel-ui-").toFile()
        val archive = File(root, "test.zip").apply { writeText("untouched input") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val started = AtomicBoolean(false)
        val workerExited = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        val completed = AtomicBoolean(false)
        val failed = AtomicBoolean(false)
        val repository = object : ArchiveRepository by DefaultArchiveRepository() {
            override fun testArchive(
                archive: File,
                password: CharArray?,
                limits: ArchiveManager.ExtractionLimits,
                checkCancelled: () -> Unit,
                onProgress: (Long, Long) -> Unit
            ): ArchiveManager.TestResult {
                started.set(true)
                try {
                    onProgress(1L, 100L)
                    while (true) {
                        checkCancelled()
                        Thread.sleep(5)
                    }
                } finally {
                    workerExited.set(true)
                }
            }
        }
        try {
            compose.runOnIdle {
                ArchiveTestRunner(scope, repository, ArchiveProgressDialogController { compose.activity }).run(
                    archive = archive,
                    password = null,
                    onCompleted = { completed.set(true) },
                    onCancelled = { cancelled.set(true) },
                    onFailure = { failed.set(true) }
                )
            }
            compose.waitUntil(timeoutMillis = 5_000) { started.get() }
            compose.onNodeWithText("取消").assertIsEnabled().performClick()
            compose.waitUntil(timeoutMillis = 5_000) { workerExited.get() && cancelled.get() }
            compose.onNodeWithText("正在测试压缩包").assertDoesNotExist()
            assertFalse(completed.get())
            assertFalse(failed.get())
            assertEquals("untouched input", archive.readText())
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun deletionDialogShowsCancellingAndDisablesRepeatCancellationUntilWorkStops() {
        prepareDialogHost()
        lateinit var controller: ArchiveProgressDialogController
        lateinit var progress: ArchiveProgressDialogController.ProgressUi
        var progressToDismiss: ArchiveProgressDialogController.ProgressUi? = null
        val cancelRequests = AtomicInteger(0)
        try {
            compose.runOnIdle {
                controller = ArchiveProgressDialogController { compose.activity }
                progress = controller.show("正在删除", "正在删除测试文件...", showCancel = true)
                progressToDismiss = progress
                progress.setCancelAction {
                    cancelRequests.incrementAndGet()
                    progress.showCancelling()
                }
            }
            compose.onNodeWithText("取消").assertIsEnabled().performClick()
            compose.onNodeWithText("正在取消...").assertExists()
            compose.onNodeWithText("取消").assertIsNotEnabled()
            compose.runOnIdle {
                // A queued worker update must not replace the cancellation state.
                controller.updateFileDeletion(progress, "正在删除另一个文件...", 2L, 10L)
                progress.requestCancel()
                assertTrue(progress.dialog.isShowing)
            }
            compose.onNodeWithText("正在取消...").assertExists()
            compose.onNodeWithText("正在删除另一个文件...").assertDoesNotExist()
            assertEquals(1, cancelRequests.get())
            compose.runOnIdle { progress.dialog.dismiss() }
            compose.onNodeWithText("正在删除").assertDoesNotExist()
        } finally {
            compose.runOnIdle { progressToDismiss?.dialog?.dismiss() }
        }
    }

    @Test
    fun extractionDialogKeepsTheCurrentFileAlongsideProgressUntilCancelled() {
        prepareDialogHost()
        val root = Files.createTempDirectory(compose.activity.cacheDir.toPath(), "extraction-file-ui-").toFile()
        val firstPath = File(root, "字幕/重复🌊（1）.srt").absolutePath
        val nextPath = File(root, "字幕/下一段.srt").absolutePath
        lateinit var controller: ArchiveProgressDialogController
        lateinit var progress: ArchiveProgressDialogController.ProgressUi
        var progressToDismiss: ArchiveProgressDialogController.ProgressUi? = null
        try {
            compose.runOnIdle {
                controller = ArchiveProgressDialogController { compose.activity }
                progress = controller.show("正在解压", "目标：${root.absolutePath}", showCancel = true)
                progressToDismiss = progress
                progress.setCancelAction { progress.showCancelling() }
                controller.updateExtractionFile(progress, firstPath)
                controller.updateArchive(progress, ArchiveManager.ProgressPhase.EXTRACTING, 50L, 100L)
            }
            compose.onNodeWithText("正在解压 $firstPath").assertExists()
            compose.onNodeWithText("50%", substring = true).assertExists()
            compose.runOnIdle {
                controller.updateExtractionFile(progress, nextPath)
                controller.updateArchive(progress, ArchiveManager.ProgressPhase.EXTRACTING, 75L, 100L)
            }
            compose.onNodeWithText("正在解压 $nextPath").assertExists()
            compose.onNodeWithText("正在解压 $firstPath").assertDoesNotExist()
            compose.onNodeWithText("75%", substring = true).assertExists()
            compose.onNodeWithText("取消").performClick()
            compose.runOnIdle {
                controller.updateExtractionFile(progress, firstPath)
                controller.updateArchive(progress, ArchiveManager.ProgressPhase.EXTRACTING, 90L, 100L)
            }
            compose.onNodeWithText("正在取消...").assertExists()
            compose.onNodeWithText("正在解压 $firstPath").assertDoesNotExist()
            compose.onNodeWithText("90%", substring = true).assertDoesNotExist()
            compose.onNodeWithText("取消").assertIsNotEnabled()
        } finally {
            compose.runOnIdle { progressToDismiss?.dialog?.dismiss() }
            root.deleteRecursively()
        }
    }
}
