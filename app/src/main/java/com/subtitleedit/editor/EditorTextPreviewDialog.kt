package com.subtitleedit.editor

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import com.subtitleedit.R
import com.subtitleedit.adapter.TranslationPreviewAdapter
import com.subtitleedit.adapter.TranslationPreviewItem
import com.subtitleedit.view.DraggableRecyclerView

/** AI 翻译与快速转录共用的结果预览对话框。 */
internal class EditorTextPreviewDialog(private val activity: Activity) {

    /**
     * @param onApply 只接收勾选了应用的条目。
     * @param onNeutral 接收全部条目；为空时不显示中间按钮。
     */
    fun show(
        title: String,
        editTitle: String,
        previewItems: List<TranslationPreviewItem>,
        onApply: (List<TranslationPreviewItem>) -> Unit,
        neutralButtonText: String? = null,
        onNeutral: ((List<TranslationPreviewItem>) -> Unit)? = null,
        suspectedProblem: ((TranslationPreviewItem) -> Boolean)? = null
    ) {
        val warningText = TextView(activity).apply {
            text = activity.getString(R.string.translation_preview_suspect_warning)
            setTextColor(activity.getColor(R.color.error))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            maxLines = 2
            maxWidth = dp(160)
            setPadding(dp(8), dp(8), 0, dp(8))
            isClickable = true
            isFocusable = true
            setSelectableBackground(this)
        }
        val titleView = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(
                TextView(activity).apply {
                    text = title
                    setTextColor(activity.getColor(R.color.on_surface))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                    setTypeface(typeface, Typeface.BOLD)
                    maxLines = 2
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(
                warningText,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        fun currentProblemIndices(): List<Int> = previewItems.indices.filter { index ->
            suspectedProblem?.invoke(previewItems[index]) == true
        }

        fun refreshWarning() {
            warningText.visibility = if (currentProblemIndices().isEmpty()) View.GONE else View.VISIBLE
        }

        lateinit var recyclerView: DraggableRecyclerView
        val previewAdapter = TranslationPreviewAdapter(previewItems) { item, onUpdated ->
            showTextEditDialog(
                previewItem = item,
                onUpdated = {
                    if (suspectedProblem != null) {
                        item.suspectedProblem = item.translatedText.isBlank()
                    }
                    onUpdated()
                    refreshWarning()
                },
                title = editTitle
            )
        }
        recyclerView = DraggableRecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = previewAdapter
            setPadding(16, 8, 16, 8)
            clipToPadding = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            post { showDragThumb() }
        }
        warningText.setOnClickListener {
            showSuspectedProblemDialog(recyclerView, currentProblemIndices())
        }
        refreshWarning()

        val builder = AlertDialog.Builder(activity)
            .setCustomTitle(titleView)
            .setView(recyclerView)
            .setPositiveButton("应用") { _, _ ->
                onApply(previewItems.filter { it.apply })
            }
            .setNegativeButton("取消", null)
        if (neutralButtonText != null && onNeutral != null) {
            builder.setNeutralButton(neutralButtonText) { _, _ -> onNeutral(previewItems) }
        }

        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.window?.apply {
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                setLayout(
                    (activity.resources.displayMetrics.widthPixels * 0.96f).toInt(),
                    (activity.resources.displayMetrics.heightPixels * 0.82f).toInt()
                )
            }
        }
        dialog.show()
    }

    private fun showSuspectedProblemDialog(
        previewList: DraggableRecyclerView,
        problemIndices: List<Int>
    ) {
        if (problemIndices.isEmpty()) return
        val rows = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scrollView = ScrollView(activity).apply {
            addView(
                rows,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.translation_preview_suspect_dialog_title)
            .setView(scrollView)
            .setNegativeButton("关闭", null)
            .create()

        problemIndices.forEach { previewIndex ->
            rows.addView(
                TextView(activity).apply {
                    text = activity.getString(
                        R.string.translation_preview_suspect_row,
                        previewIndex + 1
                    )
                    setTextColor(activity.getColor(R.color.primary))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(24), dp(12), dp(24), dp(12))
                    isClickable = true
                    isFocusable = true
                    setSelectableBackground(this)
                    setOnClickListener {
                        dialog.dismiss()
                        previewList.post {
                            (previewList.layoutManager as? LinearLayoutManager)
                                ?.scrollToPositionWithOffset(previewIndex, 0)
                        }
                    }
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        dialog.show()
    }

    private fun showTextEditDialog(
        previewItem: TranslationPreviewItem,
        onUpdated: () -> Unit,
        title: String
    ) {
        val editText = EditText(activity).apply {
            setText(previewItem.translatedText)
            setSelection(text.length)
            setLines(3)
            inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(editText)
            .setPositiveButton("确定") { _, _ ->
                previewItem.translatedText = editText.text?.toString().orEmpty()
                onUpdated()
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            editText.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            editText.post {
                val inputMethodManager =
                    activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
                inputMethodManager.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        dialog.show()
    }

    private fun setSelectableBackground(view: View) {
        val value = TypedValue()
        if (activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)) {
            view.setBackgroundResource(value.resourceId)
        }
    }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
