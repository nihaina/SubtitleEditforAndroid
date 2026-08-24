package com.subtitleedit.editor

import android.app.Activity
import android.app.AlertDialog
import android.widget.Toast
import com.subtitleedit.adapter.TranslationPreviewItem
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.AiProviderConfig
import com.subtitleedit.util.AiTranslator
import com.subtitleedit.util.OverwritingToast
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.util.SubtitleParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 负责 AI 翻译的确认、进度与结果预览，不持有字幕文档本身。 */
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
    private var activeAiTranslator: AiTranslator? = null

    private data class TranslationSession(
        val selectedEntries: List<Pair<SubtitleEntry, Int>>,
        val translator: AiTranslator,
        val translatedTexts: MutableList<String> = mutableListOf(),
        var conversationState: AiTranslator.ConversationState = AiTranslator.ConversationState()
    )

    /** 显示 AI 翻译对话框 */
    fun start(selectedEntries: List<Pair<SubtitleEntry, Int>>) {
        // 检查 API 设置
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
        val contextWindowTokens = settingsManager.getAiContextWindowTokens()
        if (baseUrl.isBlank()) {
            showTranslationError("请先在设置中填写自定义 API 请求地址")
            return
        }

        // 显示翻译确认对话框
        AlertDialog.Builder(activity)
            .setTitle("AI 翻译")
            .setMessage(
                "将使用 $providerName / $model 翻译选中的 ${selectedEntries.size} 条字幕\n" +
                    "目标语言：$targetLanguage\n\n每 300 条字幕以原时间轴格式流式翻译，点击「开始翻译」继续"
            )
            .setPositiveButton("开始翻译") { _, _ ->
                startTranslation(
                    selectedEntries,
                    provider,
                    apiKey,
                    model,
                    targetLanguage,
                    baseUrl,
                    contextWindowTokens
                )
            }
            .setNegativeButton("取消", null)
            .show()
    }

    fun release() {
        if (isTranslating) {
            userCancelledTranslation = false
            translateCancelled = true
            activeAiTranslator?.cancel()
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
        contextWindowTokens: Int
    ) {
        val aiTranslator = AiTranslator(
            provider = provider,
            apiKey = apiKey,
            model = model,
            targetLanguage = targetLanguage,
            baseUrl = baseUrl,
            contextWindowTokens = contextWindowTokens,
            subtitleFormat = subtitleFormatProvider()
        )
        continueTranslation(TranslationSession(selectedEntries, aiTranslator))
    }

    private fun continueTranslation(session: TranslationSession) {
        val completedBeforeRun = session.translatedTexts.size
        val totalCount = session.selectedEntries.size
        // 显示翻译进度对话框
        val progressDialog = AlertDialog.Builder(activity)
            .setTitle("正在翻译")
            .setMessage("正在翻译第 $completedBeforeRun/$totalCount 条...")
            .setNegativeButton("取消") { _, _ ->
                userCancelledTranslation = true
                translateCancelled = true
                activeAiTranslator?.cancel()
            }
            .setCancelable(false)
            .create()
        progressDialog.show()

        translateCancelled = false
        userCancelledTranslation = false
        isTranslating = true
        activeAiTranslator = session.translator
        val subtitlesToTranslate = session.selectedEntries
            .drop(completedBeforeRun)
            .map { it.first }

        translateJob = scope.launch(Dispatchers.Main) {
            try {
                val result = session.translator.translateSubtitles(
                    subtitles = subtitlesToTranslate,
                    startPosition = completedBeforeRun + 1,
                    progressCallback = { current, _ ->
                        activity.runOnUiThread {
                            progressDialog.setMessage(
                                "正在翻译第 ${completedBeforeRun + current}/$totalCount 条..."
                            )
                        }
                    },
                    isCancelled = { translateCancelled },
                    conversationState = session.conversationState
                )

                session.translatedTexts.addAll(result.translations)
                session.conversationState = result.conversationState
                if (translateCancelled) {
                    handleTranslationCancellation(session, progressDialog)
                    return@launch
                }
                finishTranslation(progressDialog)

                if (result.isComplete) {
                    showTranslationResult(session.selectedEntries, session.translatedTexts)
                } else {
                    showTranslationInterrupted(
                        session,
                        result.error?.message ?: "未知错误"
                    )
                }
            } catch (e: AiTranslator.TranslationCancelledException) {
                session.translatedTexts.addAll(e.translations)
                session.conversationState = e.conversationState
                handleTranslationCancellation(session, progressDialog)
            } catch (_: CancellationException) {
                handleTranslationCancellation(session, progressDialog)
            } catch (e: Exception) {
                finishTranslation(progressDialog)
                showTranslationError(e.message ?: "未知错误")
            }
        }
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

    /** 显示翻译结果预览 */
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
        if (progressDialog.isShowing) {
            progressDialog.dismiss()
        }
        isTranslating = false
        translateJob = null
        activeAiTranslator = null
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
