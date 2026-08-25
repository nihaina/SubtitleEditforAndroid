package com.subtitleedit.editor

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.graphics.Typeface
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
    private var activeTranslationConversation: AiTranslationConversation? = null
    private var activeTranslationDialog: AlertDialog? = null
    private var translationConversation: RecyclerView? = null
    private var translationStatus: TextView? = null
    private val transcriptBlocks = mutableListOf<TranscriptBlock>()
    private var transcriptAdapter: TranslationTranscriptAdapter? = null
    private var transcriptPinnedToBottom = true
    private var transcriptAdapterUpdatePending = false
    private var transcriptNotifiedSize = 0
    private var pendingTranscriptScroll: Runnable? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private val pendingUiEvents = ArrayDeque<AiTranslationConversation.TranslationUiEvent>()
    private val pendingUiLock = Any()
    private var uiFlushScheduled = false
    private var pendingStatusText: String? = null

    private data class TranscriptBlock(
        val role: String,
        val text: StringBuilder = StringBuilder(),
        var expanded: Boolean = role != "AI 思考"
    )

    private companion object {
        private const val STREAM_UI_DEBOUNCE_MS = 50L
        private const val MAX_VISIBLE_BLOCK_CHARS = 8_000
        private const val MAX_VISIBLE_REASONING_CHARS = 2_000
    }

    private data class TranslationSession(
        val selectedEntries: List<Pair<SubtitleEntry, Int>>,
        val translator: AiTranslationConversation,
        val translatedTexts: MutableList<String> = mutableListOf()
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
        val contextWindowTokens = settingsManager.getAiContextWindowTokens(provider)
        val reasoningLevel = settingsManager.getAiReasoningLevel(provider)
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
        val aiTranslator = AiTranslationConversation(
            context = activity,
            provider = provider,
            apiKey = apiKey,
            model = model,
            targetLanguage = targetLanguage,
            baseUrl = baseUrl,
            contextWindowTokens = contextWindowTokens,
            subtitleFormat = subtitleFormatProvider(),
            reasoningLevel = reasoningLevel
        )
        continueTranslation(TranslationSession(selectedEntries, aiTranslator))
    }

    private fun continueTranslation(session: TranslationSession) {
        val completedBeforeRun = session.translatedTexts.size
        val totalCount = session.selectedEntries.size
        val progressDialog = createTranslationDialog(totalCount)
        progressDialog.setOnShowListener {
            updateTranslationTranscript()
        }
        progressDialog.setCancelable(false)
        progressDialog.show()

        /* The dialog is intentionally kept separate from the result preview. It mirrors a
         * normal chat: the request is shown first, the response is appended while streaming,
         * and parsing gets its own short-lived status. */
        val dialog = progressDialog
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
                        enqueueTranslationStatus(
                            dialog,
                            "正在接收 AI 回复 · 已识别 $current/$totalCount 条"
                        )
                    },
                    conversationCallback = { event ->
                        enqueueTranslationUiEvent(dialog, event)
                    },
                    isCancelled = { translateCancelled }
                )

                session.translatedTexts.addAll(result.translations)
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
            } catch (e: AiTranslationConversation.TranslationCancelledException) {
                session.translatedTexts.addAll(e.translations)
                handleTranslationCancellation(session, dialog)
            } catch (_: CancellationException) {
                handleTranslationCancellation(session, dialog)
            } catch (e: Exception) {
                finishTranslation(dialog)
                showTranslationError(e.message ?: "未知错误")
            }
        }
    }

    private fun createTranslationDialog(totalCount: Int): AlertDialog {
        transcriptBlocks.clear()
        transcriptAdapter = TranslationTranscriptAdapter(transcriptBlocks)
        translationStatus = TextView(activity).apply {
            setTextSize(13f)
            setPadding(20, 12, 20, 4)
            text = "正在翻译 0/$totalCount 条"
        }
        val conversation = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = transcriptAdapter
            itemAnimator = null
            setHasFixedSize(false)
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                        transcriptPinnedToBottom = false
                    } else if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        val wasAtBottom = !recyclerView.canScrollVertically(1)
                        if (transcriptAdapterUpdatePending) {
                            notifyTranscriptChanges()
                            transcriptAdapterUpdatePending = false
                        }
                        if (wasAtBottom) {
                            transcriptPinnedToBottom = true
                            maintainTranscriptScroll(keepAtBottom = true)
                        } else if (!recyclerView.canScrollVertically(1)) {
                            transcriptPinnedToBottom = true
                        }
                    }
                }
            })
            setOnTouchListener { view, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    pendingTranscriptScroll?.let(view::removeCallbacks)
                    pendingTranscriptScroll = null
                    // Any explicit touch means the user is taking over the viewport,
                    // including a drag that starts while the list is at the bottom.
                    transcriptPinnedToBottom = false
                } else if ((event.actionMasked == MotionEvent.ACTION_UP ||
                        event.actionMasked == MotionEvent.ACTION_CANCEL) &&
                    !view.canScrollVertically(1)
                ) {
                    // Re-enable follow mode only after the user's gesture actually ends
                    // at the bottom of the transcript.
                    transcriptPinnedToBottom = true
                }
                false
            }
        }
        translationConversation = conversation
        transcriptPinnedToBottom = true
        transcriptAdapterUpdatePending = false
        transcriptNotifiedSize = 0
        val transcriptHeight = (activity.resources.displayMetrics.heightPixels * 0.58f).toInt()
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                translationStatus,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                conversation,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    transcriptHeight
                )
            )
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("AI 翻译")
            .setView(content)
            .setNegativeButton("取消") { _, _ ->
                userCancelledTranslation = true
                translateCancelled = true
                activeTranslationConversation?.cancel()
            }
            .setCancelable(false)
            .create()
        activeTranslationDialog = dialog
        return dialog
    }

    private fun handleTranslationUiEvent(event: AiTranslationConversation.TranslationUiEvent) {
        when (event) {
            is AiTranslationConversation.TranslationUiEvent.Request -> {
                val block = TranscriptBlock("程序")
                transcriptBlocks += block
                appendVisibleText(block, limitVisibleText(event.content))
                translationStatus?.text = "正在等待 AI 回复（${event.startPosition}-${event.endPosition}）"
            }
            is AiTranslationConversation.TranslationUiEvent.ReasoningDelta -> {
                val block = currentReasoningBlock()
                appendVisibleText(block, event.text, MAX_VISIBLE_REASONING_CHARS)
                translationStatus?.text = "AI 正在思考"
            }
            is AiTranslationConversation.TranslationUiEvent.AssistantDelta -> {
                appendVisibleText(currentAssistantBlock(), event.text)
                translationStatus?.text = "正在接收 AI 回复"
            }
            AiTranslationConversation.TranslationUiEvent.ProcessingResponse -> {
                translationStatus?.text = "正在处理AI回复文本"
            }
        }
    }

    private fun enqueueTranslationStatus(dialog: AlertDialog, status: String) {
        synchronized(pendingUiLock) {
            if (activeTranslationDialog !== dialog) return
            pendingStatusText = status
            scheduleUiFlushLocked(dialog)
        }
    }

    private fun enqueueTranslationUiEvent(
        dialog: AlertDialog,
        event: AiTranslationConversation.TranslationUiEvent
    ) {
        synchronized(pendingUiLock) {
            if (activeTranslationDialog !== dialog) return
            // Preserve each chunk and append it to the UI-side StringBuilder during the
            // debounced flush. Concatenating growing immutable strings here would be
            // quadratic for a high-rate stream.
            pendingUiEvents.addLast(event)
            scheduleUiFlushLocked(dialog)
        }
    }

    private fun scheduleUiFlushLocked(dialog: AlertDialog) {
        if (uiFlushScheduled) return
        uiFlushScheduled = true
        uiHandler.postDelayed({ flushTranslationUi(dialog) }, STREAM_UI_DEBOUNCE_MS)
    }

    private fun flushTranslationUi(dialog: AlertDialog) {
        val events: List<AiTranslationConversation.TranslationUiEvent>
        val status: String?
        synchronized(pendingUiLock) {
            // Keep ProcessingResponse as a visible frame before a following Request event.
            // This mirrors the chat flow and prevents a fast parser from hiding the state
            // transition in the same 50 ms batch.
            val processingIndex = pendingUiEvents.indexOfFirst {
                it === AiTranslationConversation.TranslationUiEvent.ProcessingResponse
            }
            val takeCount = if (processingIndex >= 0) processingIndex + 1 else pendingUiEvents.size
            events = pendingUiEvents.take(takeCount)
            repeat(takeCount) { pendingUiEvents.removeFirst() }
            status = pendingStatusText
            pendingStatusText = null
            uiFlushScheduled = false
        }
        if (activeTranslationDialog !== dialog) return
        val conversation = translationConversation ?: return
        val keepAtBottom = isConversationAtBottom()
        status?.let { translationStatus?.text = it }
        // Apply the coalesced progress text first. A ProcessingResponse event must
        // remain visible instead of being overwritten by the final stream progress tick.
        val previousSize = transcriptBlocks.size
        events.forEach(::handleTranslationUiEvent)
        if (events.isNotEmpty()) {
            if (conversation.scrollState != RecyclerView.SCROLL_STATE_IDLE) {
                // Updating the adapter during a gesture causes RecyclerView to rebuild its
                // layout and cancels the user's scroll position. Apply the accumulated text
                // as soon as the gesture reaches idle instead.
                transcriptAdapterUpdatePending = true
            } else {
                notifyTranscriptChanges()
                maintainTranscriptScroll(keepAtBottom)
            }
        }

        synchronized(pendingUiLock) {
            if (activeTranslationDialog === dialog &&
                (pendingUiEvents.isNotEmpty() || pendingStatusText != null)
            ) {
                scheduleUiFlushLocked(dialog)
            }
        }
    }

    private fun currentAssistantBlock(): TranscriptBlock {
        val last = transcriptBlocks.lastOrNull()
        if (last?.role == "AI") return last
        return TranscriptBlock("AI").also {
            transcriptBlocks += it
        }
    }

    private fun currentReasoningBlock(): TranscriptBlock {
        val last = transcriptBlocks.lastOrNull()
        if (last?.role == "AI 思考") return last
        return TranscriptBlock("AI 思考").also {
            transcriptBlocks += it
        }
    }

    private fun appendVisibleText(
        block: TranscriptBlock,
        text: String,
        maxChars: Int = MAX_VISIBLE_BLOCK_CHARS
    ) {
        if (text.isEmpty() || block.text.length >= maxChars) return
        val remaining = maxChars - block.text.length
        val appended = text.take(remaining)
        block.text.append(appended)
    }

    private fun limitVisibleText(text: String): String {
        if (text.length <= MAX_VISIBLE_BLOCK_CHARS) return text
        val half = MAX_VISIBLE_BLOCK_CHARS / 2
        return text.take(half) + "\n…（内容过长，窗口仅显示首尾片段）…\n" + text.takeLast(half)
    }

    private fun updateTranslationTranscript() {
        val keepAtBottom = isConversationAtBottom()
        transcriptAdapter?.notifyDataSetChanged()
        transcriptNotifiedSize = transcriptBlocks.size
        maintainTranscriptScroll(keepAtBottom)
    }

    private fun notifyTranscriptChanges() {
        val adapter = transcriptAdapter ?: return
        val previousSize = transcriptNotifiedSize.coerceAtMost(transcriptBlocks.size)
        if (previousSize > 0) adapter.notifyItemChanged(previousSize - 1)
        if (transcriptBlocks.size > previousSize) {
            adapter.notifyItemRangeInserted(previousSize, transcriptBlocks.size - previousSize)
        }
        transcriptNotifiedSize = transcriptBlocks.size
    }

    private fun maintainTranscriptScroll(keepAtBottom: Boolean) {
        val conversation = translationConversation ?: return
        pendingTranscriptScroll?.let(conversation::removeCallbacks)
        pendingTranscriptScroll = null
        if (!keepAtBottom) return
        lateinit var scrollRunnable: Runnable
        scrollRunnable = Runnable {
            if (keepAtBottom &&
                transcriptPinnedToBottom &&
                conversation.scrollState != RecyclerView.SCROLL_STATE_DRAGGING
            ) {
                transcriptAdapter?.itemCount?.minus(1)?.takeIf { it >= 0 }?.let {
                    conversation.scrollToPosition(it)
                }
            }
            if (pendingTranscriptScroll === scrollRunnable) {
                pendingTranscriptScroll = null
            }
        }
        pendingTranscriptScroll = scrollRunnable
        conversation.post(scrollRunnable)
    }

    private fun isConversationAtBottom(): Boolean {
        val conversation = translationConversation ?: return true
        return transcriptPinnedToBottom &&
            conversation.scrollState != RecyclerView.SCROLL_STATE_DRAGGING &&
            !conversation.canScrollVertically(1)
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
        synchronized(pendingUiLock) {
            pendingUiEvents.clear()
            pendingStatusText = null
            uiFlushScheduled = false
        }
        uiHandler.removeCallbacksAndMessages(null)
        translationConversation?.let { conversation ->
            pendingTranscriptScroll?.let(conversation::removeCallbacks)
        }
        pendingTranscriptScroll = null
        if (progressDialog.isShowing) {
            progressDialog.dismiss()
        }
        if (activeTranslationDialog === progressDialog) {
            activeTranslationDialog = null
            translationConversation = null
            translationStatus = null
            transcriptAdapter = null
            transcriptBlocks.clear()
            transcriptPinnedToBottom = true
            transcriptAdapterUpdatePending = false
            transcriptNotifiedSize = 0
        }
        isTranslating = false
        translateJob = null
        activeTranslationConversation = null
    }

    private fun showTranslationError(message: String) {
        OverwritingToast.makeText(activity, "翻译失败：$message", Toast.LENGTH_LONG).show()
    }

    private class TranslationTranscriptAdapter(
        private val blocks: List<TranscriptBlock>
    ) : RecyclerView.Adapter<TranslationTranscriptAdapter.ViewHolder>() {

        class ViewHolder(
            val root: LinearLayout,
            val header: TextView,
            val body: TextView
        ) : RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val context = parent.context
            val header = TextView(context).apply {
                setTextSize(14f)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(20, 12, 20, 8)
            }
            val body = TextView(context).apply {
                setTextSize(14f)
                setPadding(20, 0, 20, 16)
                setLineSpacing(0f, 1.08f)
                setTextIsSelectable(true)
            }
            val root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(header, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ))
                addView(body, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ))
            }
            return ViewHolder(root, header, body)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val block = blocks[position]
            val collapsible = block.role == "AI 思考"
            holder.header.text = if (collapsible) {
                if (block.expanded) "AI 思考 · 收起" else "AI 思考 · 展开"
            } else {
                block.role
            }
            holder.body.text = block.text
            holder.body.visibility = if (!collapsible || block.expanded) View.VISIBLE else View.GONE
            holder.header.setOnClickListener(if (collapsible) View.OnClickListener {
                block.expanded = !block.expanded
                notifyItemChanged(holder.bindingAdapterPosition)
            } else null)
        }

        override fun getItemCount(): Int = blocks.size
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
