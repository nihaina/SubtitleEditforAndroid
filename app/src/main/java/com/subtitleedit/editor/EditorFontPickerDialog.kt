package com.subtitleedit.editor

import android.app.AlertDialog
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.subtitleedit.util.SubtitleFontMetadata
import com.subtitleedit.util.SubtitleFontManager
import java.io.File

/**
 * Font chooser used by the editor's formatting menu.
 *
 * Android does not expose a public API which returns every installed font family. The
 * system font configuration and OpenType metadata are therefore combined for the
 * installed-font column. Files selected by the user are kept alongside their display
 * name so the caller can make them available to its renderer.
 */
internal class EditorFontPickerDialog(private val activity: AppCompatActivity) {

    data class FontChoice(val name: String, val file: File? = null, val collectionIndex: Int = 0)

    private data class LocalFont(val displayName: String, val file: File, val collectionIndex: Int = 0)

    /**
     * Shows the font picker.
     *
     * [onChooseFile] is called when the file button is pressed.  The caller may open an
     * OpenDocument picker, copy the selected font into the application's shared fonts
     * directory, and show this dialog again with the resulting directory.
     * The dialog stays open until the user confirms or cancels.
     */
    fun show(
        initialName: String,
        localDir: File?,
        onChooseFile: () -> Unit,
        onConfirm: (FontChoice) -> Unit
    ): AlertDialog {
        val systemFonts = systemFontChoices(initialName)
        val localFonts = scanLocalFonts(localDir)
        var selected = systemFonts.firstOrNull { it.name.equals(initialName.trim(), ignoreCase = true) }
            ?.let { FontChoice(it.name, it.file, it.collectionIndex) }
            ?: SubtitleFontManager.resolveSystemFont(activity, initialName)
                ?.let { FontChoice(it.name, it.file, it.collectionIndex) }
            ?: localFonts.firstOrNull {
                it.displayName.equals(initialName.trim(), ignoreCase = true) ||
                    it.file.name.equals(initialName.trim(), ignoreCase = true)
            }?.let { FontChoice(it.displayName, it.file, it.collectionIndex) }
            ?: FontChoice(initialName.trim().ifBlank { "sans-serif" })

        val preview = TextView(activity).apply {
            text = SAMPLE_TEXT
            textSize = 18f
            gravity = Gravity.CENTER
            minHeight = dp(56)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setBackgroundColor(0x12000000)
        }
        val selectedLabel = TextView(activity).apply {
            textSize = 13f
            setPadding(dp(2), dp(4), dp(2), dp(4))
        }
        val search = EditText(activity).apply {
            hint = "搜索字体"
            contentDescription = "搜索字体"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
            setSelectAllOnFocus(false)
            minHeight = dp(48)
        }
        val systemList = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        val localList = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        val systemScroll = ScrollView(activity).apply {
            isFillViewport = true
            addView(systemList, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        val localScroll = ScrollView(activity).apply {
            isFillViewport = true
            addView(localList, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        lateinit var dialog: AlertDialog

        fun updatePreview() {
            preview.text = "$SAMPLE_TEXT\n${selected.name}"
            preview.typeface = runCatching {
                selected.file?.let { file ->
                    Typeface.Builder(file).setTtcIndex(selected.collectionIndex).build()
                }
                    ?: Typeface.create(selected.name, Typeface.NORMAL)
            }.getOrDefault(Typeface.DEFAULT)
            selectedLabel.text = "当前字体：${selected.name}"
        }

        fun row(text: String, choice: FontChoice): TextView = TextView(activity).apply {
            this.text = text
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(48)
            setPadding(dp(8), 0, dp(8), 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            isClickable = true
            isFocusable = true
            setBackgroundResource(selectableBackground())
            setOnClickListener {
                selected = choice
                updatePreview()
            }
        }

        fun heading(title: String): TextView = TextView(activity).apply {
            text = title
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }

        fun render(filter: String) {
            val query = filter.trim()
            systemList.removeAllViews()
            localList.removeAllViews()
            systemList.addView(heading("系统字体"))
            systemFonts.asSequence()
                .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
                .forEach { choice ->
                    systemList.addView(row(choice.name, FontChoice(choice.name, choice.file, choice.collectionIndex)))
                }
            localList.addView(heading("本地字体"))
            if (localFonts.isEmpty()) {
                localList.addView(TextView(activity).apply {
                    text = "未导入字体文件"
                    textSize = 14f
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                })
            } else {
                localFonts.asSequence()
                    .filter {
                        query.isBlank() || it.displayName.contains(query, ignoreCase = true) ||
                            it.file.name.contains(query, ignoreCase = true)
                    }
                    .forEach { font ->
                        localList.addView(row(font.displayName, FontChoice(font.displayName, font.file, font.collectionIndex)))
                    }
            }
        }

        val chooseFile = Button(activity).apply {
            text = "选择字体文件"
            minHeight = dp(48)
            setOnClickListener { onChooseFile() }
        }
        val columns = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(systemScroll, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(240)
                ))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(localScroll, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(240)
                ))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
            })
        }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(2), dp(12), dp(4))
            addView(search, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(selectedLabel, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(preview, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(chooseFile, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(columns, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        val scroll = BoundedScrollView().apply {
            addView(root, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = render(s?.toString().orEmpty())
            override fun afterTextChanged(s: Editable?) = Unit
        })

        render("")
        updatePreview()
        dialog = AlertDialog.Builder(activity)
            .setTitle("选择字体")
            .setView(scroll)
            .setPositiveButton("确定", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.apply {
                setLayout(minOf(activity.resources.displayMetrics.widthPixels - dp(24), dp(680)),
                    ViewGroup.LayoutParams.WRAP_CONTENT)
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                onConfirm(selected)
                dialog.dismiss()
            }
        }
        dialog.show()
        return dialog
    }

    private fun systemFontChoices(existingName: String): List<com.subtitleedit.util.SubtitleFontChoice> {
        val choices = SubtitleFontManager.systemFontChoices(activity).toMutableList()
        if (existingName.isNotBlank() && choices.none { it.name.equals(existingName.trim(), true) }) {
            choices += com.subtitleedit.util.SubtitleFontChoice(existingName.trim())
        }
        return choices.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    private fun scanLocalFonts(directory: File?): List<LocalFont> {
        val files = directory?.takeIf { it.isDirectory }?.listFiles().orEmpty()
        return files.asSequence()
            .filter { it.isFile && it.canRead() && it.extension.lowercase() in FONT_EXTENSIONS }
            .flatMap { file ->
                val families = SubtitleFontMetadata.families(file)
                if (families.isEmpty()) sequenceOf(LocalFont(file.nameWithoutExtension, file))
                else families.asSequence().map { LocalFont(it.name, file, it.collectionIndex) }
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
            .toList()
    }

    private fun selectableBackground(): Int {
        val value = android.util.TypedValue()
        return if (activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)) {
            value.resourceId
        } else {
            android.R.color.transparent
        }
    }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density + 0.5f).toInt()

    private inner class BoundedScrollView : ScrollView(activity) {
        var maximumHeight = (activity.resources.displayMetrics.heightPixels * 0.76f).toInt()
            set(value) {
                if (field != value) {
                    field = value
                    requestLayout()
                }
            }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val parentHeight = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
                maximumHeight
            } else {
                MeasureSpec.getSize(heightMeasureSpec)
            }
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(
                minOf(parentHeight, maximumHeight), MeasureSpec.AT_MOST
            ))
        }
    }

    private companion object {
        const val SAMPLE_TEXT = "AaBbCc 0123 你好 字幕"
        val FONT_EXTENSIONS = setOf("ttf", "otf", "ttc", "otc")
    }
}
