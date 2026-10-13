package com.subtitleedit.editor

import android.app.AlertDialog
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnPreDraw
import com.subtitleedit.R
import com.subtitleedit.util.SettingsManager
import com.subtitleedit.util.subtitle.WebVttStyleDefinition
import com.subtitleedit.util.subtitle.WebVttStyleSettings
import java.math.BigDecimal

/** Optional CSS properties, live preview and before/after CSS, as in Subtitle Edit. */
internal class EditorWebVttStyleDialog(private val activity: AppCompatActivity) {
    fun show(
        existing: WebVttStyleDefinition?,
        initialName: String,
        onSave: (String, Map<String, String>) -> Boolean,
        onClosed: () -> Unit,
        onChooseFont: ((currentName: String, onSelected: (String) -> Unit) -> Unit)? = null
    ): AlertDialog {
        val initial = WebVttStyleSettings.fromDefinition(existing)
        var color = initial.color
        var backgroundColor = initial.backgroundColor
        var shadowColor = initial.shadowColor
        val name = input(initialName).apply { contentDescription = text(R.string.vtt_style_name) }
        val fontName = EditText(activity).apply {
            setSingleLine(true)
            textSize = 15f
            hint = text(R.string.vtt_style_font_hint)
            contentDescription = text(R.string.vtt_style_font_name)
            setText(initial.fontName)
            setSelectAllOnFocus(true)
        }
        val fontSize = input(formatNumber(initial.fontSize ?: 0.0), numeric = true)
        val shadowWidth = input(formatNumber(initial.shadowWidth), numeric = true)
        val bold = check(R.string.vtt_style_bold, initial.bold)
        val italic = check(R.string.vtt_style_italic, initial.italic)
        val underline = check(R.string.vtt_style_underline, initial.underline)
        val strikeout = check(R.string.vtt_style_strikeout, initial.strikeout)
        val useColor = check(R.string.vtt_style_color, initial.useColor)
        val useBackground = check(R.string.vtt_style_background, initial.useBackgroundColor)
        val useShadow = check(R.string.vtt_style_shadow, initial.useShadow)
        val preview = EditorWebVttStylePreviewView(activity)
        val beforeCss = cssLabel(existing?.rawDeclarations.orEmpty())
        val afterCss = cssLabel("")
        var fontSizeEdited = false
        var shadowWidthEdited = false
        lateinit var dialog: AlertDialog
        var shown = false

        fun settings() = initial.copy(
            fontName = fontName.text.toString().trim(),
            fontSize = number(fontSize)?.takeIf { it > 0 },
            bold = bold.isChecked,
            italic = italic.isChecked,
            underline = underline.isChecked,
            strikeout = strikeout.isChecked,
            useColor = useColor.isChecked,
            color = color,
            useBackgroundColor = useBackground.isChecked,
            backgroundColor = backgroundColor,
            useShadow = useShadow.isChecked,
            shadowColor = shadowColor,
            shadowWidth = number(shadowWidth) ?: initial.shadowWidth
        )

        fun numericValid(input: EditText, maximum: Int): Boolean {
            val value = number(input)
            val oneDecimal = Regex("\\d*(?:[.,]\\d?)?").matches(input.text.toString().trim())
            val importedPrecision = when (input) {
                fontSize -> !fontSizeEdited && initial.fontSize != null && value == initial.fontSize
                shadowWidth -> !shadowWidthEdited && value == initial.shadowWidth
                else -> false
            }
            return value != null && value in 0.0..maximum.toDouble() && (oneDecimal || importedPrecision)
        }

        fun refresh() {
            val current = settings()
            preview.settings = current
            afterCss.text = current.toDeclarations(existing, initial).entries
                .joinToString("\n") { "${it.key}:${it.value};" }
            val validName = Regex("[A-Za-z0-9_#-]+").matches(name.text.toString().trim())
            val validSize = numericValid(fontSize, 500)
            val validShadow = !useShadow.isChecked || numericValid(shadowWidth, 100)
            name.error = if (validName) null else text(R.string.vtt_style_invalid_name)
            fontSize.error = if (validSize) null else activity.getString(R.string.vtt_style_number_range, 500)
            shadowWidth.error = if (validShadow) null else activity.getString(R.string.vtt_style_number_range, 100)
            if (shown) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = validName && validSize && validShadow
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = validName && validSize && validShadow
            }
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(2), dp(16), dp(8))
            addView(fieldRow(R.string.vtt_style_name, name))
            addView(fieldRow(R.string.vtt_style_font_name, LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(fontName, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(Button(activity).apply {
                    text = "+"
                    contentDescription = text(R.string.vtt_style_choose_font)
                    minWidth = 0
                    minimumWidth = 0
                    setPadding(0, 0, 0, 0)
                    setOnClickListener {
                        onChooseFont?.invoke(fontName.text.toString().trim()) { selected ->
                            fontName.setText(selected)
                            fontName.setSelection(fontName.text.length)
                            refresh()
                        }
                    }
                }, LinearLayout.LayoutParams(dp(40), dp(48)))
            }))
            addView(fieldRow(R.string.vtt_style_font_size, numberControl(fontSize, 500, R.string.vtt_style_font_size)))
            addView(pair(bold, italic))
            addView(pair(underline, strikeout))
            addView(pair(
                colorControl(useColor, color) { chosen -> color = chosen; refresh() },
                colorControl(useBackground, backgroundColor) { chosen -> backgroundColor = chosen; refresh() }
            ))
            addView(colorControl(useShadow, shadowColor) { chosen -> shadowColor = chosen; refresh() })
            addView(fieldRow(R.string.vtt_style_shadow_width, numberControl(shadowWidth, 100, R.string.vtt_style_shadow_width)))
            addView(heading(R.string.vtt_style_preview))
            addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)))
            addView(pair(
                LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(heading(R.string.vtt_style_before))
                    addView(beforeCss)
                },
                LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(heading(R.string.vtt_style_after))
                    addView(afterCss)
                }
            ))
        }
        listOf(name, fontName, fontSize, shadowWidth).forEach { input ->
            input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (input === fontSize) fontSizeEdited = true
                    if (input === shadowWidth) shadowWidthEdited = true
                    refresh()
                }
            })
        }
        listOf(bold, italic, underline, strikeout, useColor, useBackground, useShadow).forEach { checkbox ->
            checkbox.setOnCheckedChangeListener { _, _ -> refresh() }
        }

        val scroll = BoundedScrollView().apply { addView(root) }
        dialog = AlertDialog.Builder(activity)
            .setTitle(if (existing == null) R.string.vtt_style_new else R.string.vtt_style_edit)
            .setView(scroll)
            .setPositiveButton(R.string.confirm, null)
            .setNeutralButton(R.string.vtt_style_apply, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.window?.decorView?.alpha = 0f
        dialog.setOnShowListener {
            shown = true
            dialog.window?.apply {
                setLayout(minOf(activity.resources.displayMetrics.widthPixels - dp(24), dp(600)), ViewGroup.LayoutParams.WRAP_CONTENT)
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            }
            fun save(close: Boolean) {
                if (onSave(name.text.toString().trim(), settings().toDeclarations(existing, initial)) && close) {
                    dialog.dismiss()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { save(true) }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { save(false) }
            refresh()
            scroll.doOnPreDraw { dialog.window?.decorView?.alpha = 1f }
        }
        val decor = dialog.window?.decorView
        val boundsListener = ViewTreeObserver.OnGlobalLayoutListener {
            if (decor != null) {
                val frame = Rect()
                decor.getWindowVisibleDisplayFrame(frame)
                if (frame.height() > 0 && scroll.height > 0) {
                    scroll.maximumHeight = (frame.height() - (decor.height - scroll.height) - dp(24)).coerceAtLeast(dp(48))
                }
            }
        }
        decor?.viewTreeObserver?.addOnGlobalLayoutListener(boundsListener)
        dialog.setOnDismissListener {
            if (decor?.viewTreeObserver?.isAlive == true) decor.viewTreeObserver.removeOnGlobalLayoutListener(boundsListener)
            onClosed()
        }
        dialog.show()
        return dialog
    }

    private fun colorControl(checkbox: CheckBox, initialColor: Int, changed: (Int) -> Unit): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(checkbox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val swatch = ColorPreviewView(activity).apply {
                color = initialColor
                contentDescription = activity.getString(R.string.vtt_style_choose_color, checkbox.text)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    val settings = SettingsManager.getInstance(activity)
                    EditorColorPickerDialog(activity).show(color, settings.getRecentSubtitleColors()) { chosen ->
                        color = chosen
                        settings.rememberSubtitleColor(chosen)
                        changed(chosen)
                    }
                }
            }
            addView(swatch, LinearLayout.LayoutParams(dp(48), dp(48)))
        }

    private fun input(value: String, numeric: Boolean = false): EditText = EditText(activity).apply {
        setSingleLine(true)
        textSize = 15f
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        if (numeric) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        setText(value)
        setSelectAllOnFocus(true)
    }

    private fun check(label: Int, checked: Boolean) = CheckBox(activity).apply {
        text = text(label)
        textSize = 14f
        isChecked = checked
        minimumHeight = dp(48)
    }

    private fun heading(label: Int) = TextView(activity).apply {
        text = text(label)
        textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(8), 0, dp(4))
    }

    private fun cssLabel(css: String) = TextView(activity).apply {
        text = css.replace(Regex(";\\s*"), ";\n").trimEnd()
        textSize = 12f
        typeface = Typeface.MONOSPACE
        setTextIsSelectable(true)
        setPadding(0, 0, dp(4), dp(4))
    }

    private fun fieldRow(label: Int, control: View) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(48)
        addView(TextView(activity).apply { text = text(label); textSize = 14f },
            LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(control, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun pair(left: View, right: View) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
    }

    private fun numberControl(input: EditText, maximum: Int, label: Int) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        input.contentDescription = text(label)
        addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        listOf(-1 to R.string.vtt_style_decrease, 1 to R.string.vtt_style_increase).forEach { (step, description) ->
            addView(Button(activity).apply {
                text = if (step < 0) "−" else "+"
                contentDescription = activity.getString(description, text(label))
                minWidth = 0
                minimumWidth = 0
                setPadding(0, 0, 0, 0)
                setOnClickListener { input.setText(formatNumber(((number(input) ?: 0.0) + step).coerceIn(0.0, maximum.toDouble()))) }
            }, LinearLayout.LayoutParams(dp(40), dp(48)))
        }
    }

    private fun number(input: EditText): Double? = input.text.toString().trim().replace(',', '.').let {
        if (it.isEmpty()) 0.0 else it.toDoubleOrNull()?.takeIf(Double::isFinite)
    }

    private fun formatNumber(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    private fun text(id: Int) = activity.getString(id)
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density + 0.5f).toInt()

    private inner class BoundedScrollView : ScrollView(activity) {
        // Keep the style editor tall enough to show the live preview when it opens.
        // The global-layout listener still reduces this when the IME is visible, so
        // the dialog remains usable on smaller screens and while editing a field.
        var maximumHeight = (activity.resources.displayMetrics.heightPixels * 0.9f).toInt()
            set(value) { if (field != value) { field = value; requestLayout() } }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val parentHeight = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) maximumHeight
                else MeasureSpec.getSize(heightMeasureSpec)
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(minOf(parentHeight, maximumHeight), MeasureSpec.AT_MOST))
        }
    }
}
