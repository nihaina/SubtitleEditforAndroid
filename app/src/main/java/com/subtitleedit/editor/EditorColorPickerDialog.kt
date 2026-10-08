package com.subtitleedit.editor

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Rect
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.subtitleedit.util.SubtitleColorOps
import kotlin.math.roundToInt

internal class EditorColorPickerDialog(private val activity: AppCompatActivity) {
    fun show(initialColor: Int, recentColors: List<Int>, onConfirm: (Int) -> Unit): AlertDialog {
        var selectedColor = initialColor
        val hsv = FloatArray(3)
        Color.colorToHSV(initialColor, hsv)
        var updatingControls = false
        var dialogShown = false
        lateinit var dialog: AlertDialog
        val wheel = ColorWheelView(activity)
        val preview = ColorPreviewView(activity).apply { contentDescription = "当前颜色" }
        val valueSlider = ColorGradientSliderView(activity).apply { contentDescription = "明度" }
        val channelSliders = List(4) { index ->
            ColorGradientSliderView(activity).apply { contentDescription = CHANNEL_NAMES[index] }
        }
        val channelInputs = List(4) { index ->
            colorInput(CHANNEL_NAMES[index]).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                filters = arrayOf(InputFilter.LengthFilter(3))
            }
        }
        val hexInput = colorInput("HEX").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(9))
        }

        fun inputIsValid(): Boolean = channelInputs.all { parseChannel(it.text.toString()) != null } &&
            SubtitleColorOps.parseArgb(hexInput.text.toString()) != null

        fun refreshValidity() {
            channelInputs.forEach { input ->
                input.error = if (parseChannel(input.text.toString()) == null) "0-255" else null
            }
            hexInput.error = if (SubtitleColorOps.parseArgb(hexInput.text.toString()) == null) "HEX" else null
            if (dialogShown) dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = inputIsValid()
        }

        fun refreshControls(source: EditText? = null) {
            updatingControls = true
            try {
                val channels = channelsOf(selectedColor)
                channels.forEachIndexed { index, channel ->
                    if (channelInputs[index] !== source) setInputText(channelInputs[index], channel.toString())
                    channelSliders[index].setGradient(
                        replaceChannel(selectedColor, index, 0),
                        replaceChannel(selectedColor, index, 255),
                        channel / 255f,
                        selectedColor
                    )
                }
                if (hexInput !== source) setInputText(hexInput, "#${SubtitleColorOps.toArgbHex(selectedColor)}")
                wheel.setHsv(hsv[0], hsv[1], hsv[2])
                valueSlider.setGradient(
                    Color.BLACK,
                    Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], 1f)),
                    hsv[2],
                    Color.HSVToColor(hsv)
                )
                preview.color = selectedColor
            } finally {
                updatingControls = false
            }
            refreshValidity()
        }

        fun selectColor(color: Int, source: EditText? = null, deriveHsv: Boolean = true) {
            selectedColor = color
            if (deriveHsv) {
                val next = FloatArray(3)
                Color.colorToHSV(color, next)
                // Black has no hue or saturation; keep the wheel's last chosen position.
                if (next[2] > 0f && next[1] > 0f) {
                    hsv[0] = next[0]
                }
                if (next[2] > 0f) {
                    hsv[1] = next[1]
                }
                hsv[2] = next[2]
            }
            refreshControls(source)
        }

        wheel.onColorSelected = { hue, saturation ->
            hsv[0] = hue
            hsv[1] = saturation
            if (hsv[2] == 0f) hsv[2] = 1f
            selectColor(Color.HSVToColor(Color.alpha(selectedColor), hsv), deriveHsv = false)
        }
        valueSlider.onValueChanged = { value ->
            hsv[2] = value
            selectColor(Color.HSVToColor(Color.alpha(selectedColor), hsv), deriveHsv = false)
        }
        channelSliders.forEachIndexed { index, slider ->
            slider.onValueChanged = { value ->
                selectColor(replaceChannel(selectedColor, index, (value * 255f).roundToInt()))
            }
        }
        channelInputs.forEachIndexed { index, input ->
            input.addTextChangedListener(afterTextChanged {
                if (!updatingControls) {
                    val value = parseChannel(input.text.toString())
                    if (value == null) refreshValidity()
                    else selectColor(replaceChannel(selectedColor, index, value), source = input)
                }
            })
        }
        hexInput.addTextChangedListener(afterTextChanged {
            if (!updatingControls) {
                val color = SubtitleColorOps.parseArgb(hexInput.text.toString())
                if (color == null) refreshValidity() else selectColor(color, source = hexInput)
            }
        })

        val visibleFrame = Rect()
        activity.window.decorView.getWindowVisibleDisplayFrame(visibleFrame)
        val availableWidth = visibleFrame.width().takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
        val dialogWidth = minOf((availableWidth - dp(16)).coerceAtLeast(dp(120)), dp(640))
        val wide = dialogWidth - dp(48) >= dp(500)
        val wheelSize = minOf(dp(208), dialogWidth - dp(64)).coerceAtLeast(dp(48))
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        val main = LinearLayout(activity).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.TOP
        }
        val wheelColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(wheel, LinearLayout.LayoutParams(wheelSize, wheelSize))
            addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
            addView(sliderRow("V", valueSlider), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        val inputsColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            channelSliders.forEachIndexed { index, slider ->
                addView(sliderRow(CHANNEL_NAMES[index], slider, channelInputs[index]), LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ))
            }
            val hexRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(label("HEX"), LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(hexInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            addView(hexRow)
        }
        main.addView(wheelColumn, if (wide) {
            LinearLayout.LayoutParams(dp(224), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(12) }
        } else {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        main.addView(inputsColumn, if (wide) {
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        } else {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        root.addView(main)
        val swatchesPerRow = if (wide) 8 else 4
        recentColors.take(8).chunked(swatchesPerRow).forEach { rowColors ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                rowColors.forEach { color ->
                    addView(ColorPreviewView(activity).apply {
                        this.color = color
                        contentDescription = "#${SubtitleColorOps.toArgbHex(color)}"
                        isClickable = true
                        isFocusable = true
                        setOnClickListener { selectColor(color) }
                    }, LinearLayout.LayoutParams(0, dp(48), 1f))
                }
                repeat(swatchesPerRow - rowColors.size) {
                    addView(View(activity), LinearLayout.LayoutParams(0, dp(48), 1f))
                }
            }
            root.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        val scroll = BoundedScrollView(activity).apply {
            isFillViewport = false
            addView(root, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        dialog = AlertDialog.Builder(activity)
            .setTitle("选择颜色")
            .setView(scroll)
            .setPositiveButton("确定", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialogShown = true
            dialog.window?.apply {
                setLayout(dialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            }
            refreshControls()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (inputIsValid()) {
                    onConfirm(selectedColor)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
        val decor = dialog.window?.decorView
        if (decor != null) {
            val boundsListener = ViewTreeObserver.OnGlobalLayoutListener {
                val frame = Rect()
                decor.getWindowVisibleDisplayFrame(frame)
                if (frame.height() > 0 && scroll.height > 0) {
                    val chromeHeight = decor.height - scroll.height
                    scroll.maximumHeight = (frame.height() - chromeHeight - dp(16)).coerceAtLeast(dp(48))
                }
            }
            decor.viewTreeObserver.addOnGlobalLayoutListener(boundsListener)
            dialog.setOnDismissListener {
                if (decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnGlobalLayoutListener(boundsListener)
            }
        }
        return dialog
    }

    private fun sliderRow(labelText: String, slider: ColorGradientSliderView, input: EditText? = null): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            addView(label(labelText), LinearLayout.LayoutParams(dp(24), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(slider, LinearLayout.LayoutParams(0, dp(48), 1f))
            if (input != null) addView(input, LinearLayout.LayoutParams(
                maxOf(dp(56), input.paint.measureText("255").roundToInt() + input.paddingLeft + input.paddingRight + dp(8)),
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

    private fun label(text: String): TextView = TextView(activity).apply {
        this.text = text
        textSize = 15f
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun colorInput(description: String): EditText = EditText(activity).apply {
        contentDescription = description
        textSize = 15f
        isSingleLine = true
        minimumHeight = dp(48)
        setPadding(dp(4), 0, dp(4), 0)
        imeOptions = EditorInfo.IME_ACTION_DONE
        setSelectAllOnFocus(true)
    }

    private fun setInputText(input: EditText, text: String) {
        if (input.text.toString() == text) return
        val selection = input.selectionStart.coerceAtLeast(0)
        input.setText(text)
        if (input.hasFocus()) input.setSelection(selection.coerceAtMost(text.length))
    }

    private fun afterTextChanged(action: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = action()
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density + 0.5f).toInt()

    private class BoundedScrollView(activity: AppCompatActivity) : ScrollView(activity) {
        var maximumHeight = activity.resources.displayMetrics.heightPixels
            set(value) {
                if (field != value) {
                    field = value
                    requestLayout()
                }
            }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val mode = MeasureSpec.getMode(heightMeasureSpec)
            val parentHeight = if (mode == MeasureSpec.UNSPECIFIED) maximumHeight
                else MeasureSpec.getSize(heightMeasureSpec)
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(
                minOf(parentHeight, maximumHeight), MeasureSpec.AT_MOST
            ))
        }
    }

    private companion object {
        val CHANNEL_NAMES = listOf("R", "G", "B", "A")

        fun channelsOf(color: Int) = intArrayOf(Color.red(color), Color.green(color), Color.blue(color), Color.alpha(color))

        fun replaceChannel(color: Int, index: Int, value: Int): Int {
            val channels = channelsOf(color)
            channels[index] = value.coerceIn(0, 255)
            return Color.argb(channels[3], channels[0], channels[1], channels[2])
        }

        fun parseChannel(text: String): Int? = text.toIntOrNull()?.takeIf { it in 0..255 }

    }
}
