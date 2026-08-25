package com.subtitleedit.editor

import android.app.Activity
import android.app.AlertDialog
import android.widget.Toast
import com.subtitleedit.adapter.TranslationPreviewItem
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.AiProviderConfig
import com.subtitleedit.util.AiTranslationConversation
import com.subtitleedit.util.OverwritingToast
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.util.SubtitleParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

/** Manages AI translation confirmation, progress and result preview. */
internal class EditorTranslationController(
    private val activity: Activity,
    private val scope: CoroutineScope,
    private val previewDialog: EditorTextPreviewDialog,
    private val applyTexts: (List<TranslationPreviewItem>) -> Unit,
    private val saveDraft: (List<TranslationPreviewItem>) -> Unit,
    private val showMessage: (String) -> Unit,
    private val subtitleFormatProvider: () -> SubtitleParser.SubtitleFormat = {
        SubtitleParser.SubtitleFormat.SRT
    }
) {
    private var translateJob: Job? = null
    private var isTranslating = false
    private var translateCancelled = false
    private var userCancelledTranslation = false
    private var activeTranslationConversation: AiTranslationConversation? = null
    private var activeTranslationDialog: AlertDialog? = null

    private data class TranslationSession(
        val selectedEntries: List<Pair<SubtitleEntry, Int>>,
        val translator: AiTranslationConversation,
        val translatedTexts: MutableList<String> = mutableListOf(),
        var completedCount: Int = 0
    )

    fun start(selectedEntries: List<Pair<SubtitleEntry, Int>>) {
        val settingsManager = SettingsManager.getInstance(activity)
        val provider = settingsManager.getAiProvider()
        val providerName = AiProviderConfig.getProvider(provider).displayName
        val apiKey = settingsManager.getAiApiKey()
        if (apiKey.isEmpty()) {
            OverwritingToast.makeText(
                activity,
                "请先在设置中配置 $providerName API Key",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val model = settingsManager.getAiModel()
        val targetLanguage = settingsManager.getAiTargetLanguage()
        if (targetLanguage.isBlank()) {
            showTranslationError("请先设置目标语言")
            return
        }
        val baseUrl = settingsManager.getAiBaseUrl(provider)
        val contextWindowTokens = settingsManager.getAiContextWindowTokens(provider)
        val reasoningLevel = settingsManager.getAiReasoningLevel(provider)
        if (baseUrl.isBlank()) {
            showTranslationError("请先在设置中填写自定义 API 请求地址")
            return
        }

        AlertDialog.Builder(activity)
            .setTitle("AI 翻译")
            .setMessage(
                "将使用 $providerName / $model 翻译选中的 ${selectedEntries.size} 条字幕\n" +
                    "目标语言：$targetLanguage\n\n每 300 条字幕会作为一条对话消息发送，并按原时间轴格式处理。"
            )
            .setPositiveButton("开始翻译") { _, _ ->
                startTranslation(
                    selectedEntries,
                    provider,
                    apiKey,
                    model,
                    targetLanguage,
                    baseUrl,
                    contextWindowTokens,
                    reasoningLevel
                )
            }
            .setNegativeButton("取消", null)
            .show()
    }

    fun release() {
        if (isTranslating) {
            userCancelledTranslation = false
            translateCancelled = true
            activeTranslationConversation?.cancel()
            translateJob?.cancel()
        }
    }

    private fun startTranslation(
        selectedEntries: List<Pair<SubtitleEntry, Int>>,
        provider: String,
        apiKey: String,
        model: String,
        targetLanguage: String,
        baseUrl: String,
        contextWindowTokens: Int,
        reasoningLevel: AiProviderConfig.ReasoningLevel
    ) {
        // One editor action owns one history record; all subtitle batches and retries share it.
        val historySessionId = UUID.randomUUID().toString()
        val translator = AiTranslationConversation(
            context = activity,
            provider = provider,
            apiKey = apiKey,
            model = model,
            targetLanguage = targetLanguage,
            baseUrl = baseUrl,
            contextWindowTokens = contextWindowTokens,
            subtitleFormat = subtitleFormatProvider(),
            reasoningLevel = reasoningLevel,
            historySessionId = historySessionId
        )
        continueTranslation(TranslationSession(selectedEntries, translator))
    }

    private fun continueTranslation(session: TranslationSession) {
        // Keep the retry cursor independent from the dialog lifecycle. A failed request
        // must resume at the first subtitle of the failed batch, not recreate the whole run.
        val completedBeforeRun = session.completedCount
            .coerceIn(0, session.selectedEntries.size)
        val totalCount = session.selectedEntries.size
        val dialog = createTranslationDialog(totalCount, completedBeforeRun)
        translateCancelled = false
        userCancelledTranslation = false
        isTranslating = true
        activeTranslationConversation = session.translator
        val subtitlesToTranslate = session.selectedEntries
            .drop(completedBeforeRun)
            .map { it.first }

        translateJob = scope.launch(Dispatchers.Main) {
            try {
                val result = session.translator.translateSubtitles(
                    subtitles = subtitlesToTranslate,
                    startPosition = completedBeforeRun + 1,
                    progressCallback = { current, _ ->
                        if (activeTranslationDialog === dialog && dialog.isShowing) {
                            dialog.setMessage("正在翻译 ${completedBeforeRun + current}/$totalCount 条")
                        }
                    },
                    isCancelled = { translateCancelled }
                )

                session.translatedTexts.addAll(result.translations)
                session.completedCount = session.translatedTexts.size
                if (translateCancelled) {
                    handleTranslationCancellation(session, dialog)
                    return@launch
                }
                finishTranslation(dialog)

                if (result.isComplete) {
                    showTranslationResult(session.selectedEntries, session.translatedTexts)
                } else {
                    showTranslationInterrupted(
                        session,
                        result.error?.message ?: "未知错误"
                    )
                }
            } catch (error: AiTranslationConversation.TranslationCancelledException) {
                session.translatedTexts.addAll(error.translations)
                session.completedCount = session.translatedTexts.size
                handleTranslationCancellation(session, dialog)
            } catch (_: CancellationException) {
                handleTranslationCancellation(session, dialog)
            } catch (error: Exception) {
                finishTranslation(dialog)
                showTranslationError(error.message ?: "未知错误")
            }
        }
    }

    private fun createTranslationDialog(totalCount: Int, completedCount: Int): AlertDialog =
        AlertDialog.Builder(activity)
            .setTitle("AI 翻译")
            .setMessage("正在翻译 $completedCount/$totalCount 条")
            .setNegativeButton("取消") { _, _ ->
                userCancelledTranslation = true
                translateCancelled = true
                activeTranslationConversation?.cancel()
            }
            .setCancelable(false)
            .create()
            .also { dialog ->
                activeTranslationDialog = dialog
                dialog.show()
            }

    private fun handleTranslationCancellation(
        session: TranslationSession,
        progressDialog: AlertDialog
    ) {
        val shouldShowPartialResult =
            userCancelledTranslation && session.translatedTexts.isNotEmpty()
        finishTranslation(progressDialog)
        userCancelledTranslation = false
        if (shouldShowPartialResult) {
            showTranslationResult(session.selectedEntries, session.translatedTexts)
        }
    }

    private fun showTranslationInterrupted(session: TranslationSession, errorMessage: String) {
        val completedCount = session.translatedTexts.size
        val totalCount = session.selectedEntries.size
        AlertDialog.Builder(activity)
            .setTitle("翻译中断")
            .setMessage(
                "已完成 $completedCount/$totalCount 条字幕。\n\n" +
                    "失败原因：$errorMessage\n\n" +
                    "可重试未完成部分，或点击「确定」预览并保留已完成结果。"
            )
            .setPositiveButton("确定") { _, _ ->
                showTranslationResult(session.selectedEntries, session.translatedTexts)
            }
            .setNegativeButton("重试") { _, _ -> continueTranslation(session) }
            .setCancelable(false)
            .show()
    }

    private fun showTranslationResult(
        selectedEntries: List<Pair<SubtitleEntry, Int>>,
        translatedTexts: List<String>
    ) {
        if (translatedTexts.size > selectedEntries.size) {
            showMessage("翻译结果数量不匹配")
            return
        }

        val previewItems = buildTranslationPreviewItems(selectedEntries, translatedTexts)
        previewDialog.show(
            title = "翻译结果预览",
            editTitle = "编辑翻译文本",
            previewItems = previewItems,
            onApply = applyTexts,
            neutralButtonText = "保存草稿",
            onNeutral = saveDraft,
            suspectedProblem = { it.suspectedProblem }
        )
    }

    private fun finishTranslation(progressDialog: AlertDialog) {
        if (progressDialog.isShowing) progressDialog.dismiss()
        if (activeTranslationDialog === progressDialog) activeTranslationDialog = null
        isTranslating = false
        translateJob = null
        activeTranslationConversation = null
    }

    private fun showTranslationError(message: String) {
        OverwritingToast.makeText(activity, "翻译失败：$message", Toast.LENGTH_LONG).show()
    }
}

internal fun buildTranslationPreviewItems(
    selectedEntries: List<Pair<SubtitleEntry, Int>>,
    translatedTexts: List<String>
): List<TranslationPreviewItem> = selectedEntries.mapIndexed { index, (entry, position) ->
    val translatedText = translatedTexts.getOrNull(index)
    TranslationPreviewItem(
        entryPosition = position,
        originalText = entry.text,
        translatedText = translatedText.orEmpty(),
        apply = translatedText != null,
        suspectedProblem = translatedText != null && translatedText.isBlank()
    )
}
