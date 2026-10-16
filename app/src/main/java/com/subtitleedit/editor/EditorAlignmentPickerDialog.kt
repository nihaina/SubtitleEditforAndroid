package com.subtitleedit.editor

import android.app.AlertDialog
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.subtitleedit.R
import com.subtitleedit.util.SubtitleCoordinate
import com.subtitleedit.util.SubtitleCoordinateOps
import com.subtitleedit.util.SubtitleAlignmentOps

/**
 * Subtitle Edit compatible nine position picker.
 *
 * Subtitle Edit calls the positions an1..an9.  The visual order is deliberately
 * top-to-bottom (an7..an9, an4..an6, an1..an3), matching its desktop picker.
 */
internal class EditorAlignmentPickerDialog(private val activity: AppCompatActivity) {

    /**
     * Compatibility overload for callers that only need the nine-position
     * selection.  Coordinate-aware callers should use the overload below and
     * receive the value currently shown in the coordinate fields.
     */
    fun show(
        initialAlignment: Int? = null,
        onSelected: (Int) -> Unit
    ): AlertDialog = show(
        initialAlignment = initialAlignment,
        initialCoordinate = null,
        onSelected = { alignment, _ -> onSelected(alignment ?: 2) }
    )

    fun show(
        initialAlignment: Int? = null,
        initialCoordinate: SubtitleCoordinate? = null,
        initialCoordinateEnabled: Boolean = false,
        onSelected: (Int?, SubtitleCoordinate?) -> Unit,
        onCoordinatePick: (Int?, SubtitleCoordinate, Boolean) -> Unit = { _, _, _ -> }
    ): AlertDialog {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density + 0.5f).toInt()

        val grid = GridLayout(activity).apply {
            columnCount = 3
            rowCount = 3
            alignmentMode = GridLayout.ALIGN_BOUNDS
            useDefaultMargins = false
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val buttons = mutableListOf<TextView>()
        // Alignment and coordinate override are mutually exclusive modes.  When
        // the coordinate mode is initially active, leave the grid unselected
        // until the user explicitly switches back to an alignment.
        var selectedAlignment: Int? = if (initialCoordinateEnabled) null else initialAlignment ?: 2
        var alignmentBeforeCoordinate = initialAlignment ?: 2
        fun refreshAlignmentSelection() {
            buttons.forEachIndexed { index, button ->
                button.background = alignmentBackground(
                    SubtitleAlignmentOps.alignments[index].number == selectedAlignment
                )
            }
        }
        SubtitleAlignmentOps.alignments.forEach { alignment ->
            val button = TextView(activity).apply {
                text = alignment.label
                textSize = 16f
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                minHeight = dp(72)
                setPadding(dp(4), dp(4), dp(4), dp(4))
                contentDescription = "${alignment.label}（${alignment.tag}）"
                background = alignmentBackground(alignment.number == selectedAlignment)
                val selectable = android.util.TypedValue()
                if (activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, selectable, true)) {
                    // Keep the card background while still providing keyboard/touch feedback.
                    foreground = ContextCompat.getDrawable(activity, selectable.resourceId)
                }
            }
            buttons += button
            val row = (buttons.size - 1) / 3
            val column = (buttons.size - 1) % 3
            val params = GridLayout.LayoutParams(
                GridLayout.spec(row, 1f),
                GridLayout.spec(column, 1f)
            ).apply {
                width = 0
                height = dp(76)
                setMargins(dp(5), dp(5), dp(5), dp(5))
            }
            grid.addView(button, params)
        }

        // A nine-grid alignment is not an explicit coordinate override. Keep the
        // fields empty in that case; only use its standard anchor if the user opens
        // the video picker before entering exact values.
        val pickerCoordinate = initialCoordinate
            ?: SubtitleCoordinateOps.defaultForAlignment(initialAlignment ?: 2)
        lateinit var dialog: AlertDialog
        val xInput = EditText(activity).apply {
            hint = "横坐标 %"
            setText(initialCoordinate?.let { formatCoordinate(it.xPercent) }.orEmpty())
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
            setSelectAllOnFocus(true)
        }
        val yInput = EditText(activity).apply {
            hint = "纵坐标 %"
            setText(initialCoordinate?.let { formatCoordinate(it.yPercent) }.orEmpty())
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
            setSelectAllOnFocus(true)
        }
        val coordinateEnabled = CheckBox(activity).apply {
            text = "坐标值"
            textSize = 16f
            isChecked = initialCoordinateEnabled
            minWidth = 0
            setPadding(0, 0, dp(4), 0)
        }
        coordinateEnabled.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                selectedAlignment?.let { alignmentBeforeCoordinate = it }
                selectedAlignment = null
            } else {
                selectedAlignment = alignmentBeforeCoordinate
            }
            refreshAlignmentSelection()
        }
        fun readCoordinate(): SubtitleCoordinate? {
            val x = xInput.text?.toString()?.trim()?.toFloatOrNull()
            val y = yInput.text?.toString()?.trim()?.toFloatOrNull()
            if (x == null || y == null || x !in 0f..100f || y !in 0f..100f) return null
            return SubtitleCoordinate(x, y)
        }
        val coordinateRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(2))
            addView(coordinateEnabled, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(xInput, LinearLayout.LayoutParams(0, dp(50), 1f))
            addView(TextView(activity).apply {
                text = "，"
                textSize = 18f
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(24), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(yInput, LinearLayout.LayoutParams(0, dp(50), 1f))
            addView(ImageButton(activity).apply {
                setImageResource(R.drawable.ic_crosshair)
                contentDescription = "在视频中选择坐标"
                setBackgroundResource(android.R.color.transparent)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setOnClickListener {
                    val value = readCoordinate() ?: if (
                        xInput.text.isNullOrBlank() && yInput.text.isNullOrBlank()
                    ) {
                        pickerCoordinate
                    } else null
                    if (value == null) {
                        // Do not silently replace an invalid value with the default
                        // position.  This keeps the value shown in the dialog and
                        // lets the user correct it before entering the picker.
                        val x = xInput.text?.toString()?.trim()?.toFloatOrNull()
                        val y = yInput.text?.toString()?.trim()?.toFloatOrNull()
                        if (x == null || x !in 0f..100f) {
                            xInput.error = "请输入 0 到 100 之间的数值"
                        }
                        if (y == null || y !in 0f..100f) {
                            yInput.error = "请输入 0 到 100 之间的数值"
                        }
                        return@setOnClickListener
                    }
                    if (dialog.isShowing) {
                        dialog.dismiss()
                    }
                    onCoordinatePick(selectedAlignment, value, coordinateEnabled.isChecked)
                }
            }, LinearLayout.LayoutParams(dp(52), dp(52)))
            addView(TextView(activity).apply {
                text = "?"
                textSize = 24f
                gravity = Gravity.CENTER
                setPadding(dp(8), 0, dp(8), 0)
                setOnClickListener {
                    AlertDialog.Builder(activity)
                        .setMessage("坐标使用百分比表示，横坐标从左到右，纵坐标从上到下。点击准心后可在视频画面中拖动。")
                        .setPositiveButton("知道了", null)
                        .show()
                }
            }, LinearLayout.LayoutParams(dp(44), dp(52)))
        }
        val wrapper = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(grid)
            addView(coordinateRow)
        }
        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            addView(wrapper, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        dialog = AlertDialog.Builder(activity)
            .setTitle("选择对齐方式")
            .setView(scroll)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定", null)
            .create()
        buttons.forEach { button ->
            button.setOnClickListener {
                val index = buttons.indexOf(button)
                selectedAlignment = SubtitleAlignmentOps.alignments[index].number
                alignmentBeforeCoordinate = selectedAlignment ?: alignmentBeforeCoordinate
                if (coordinateEnabled.isChecked) {
                    // Selecting a grid position switches back to alignment mode.
                    coordinateEnabled.isChecked = false
                }
                refreshAlignmentSelection()
            }
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selectedCoordinate = if (coordinateEnabled.isChecked) readCoordinate() else null
                if (coordinateEnabled.isChecked && selectedCoordinate == null) {
                    val x = xInput.text?.toString()?.trim()?.toFloatOrNull()
                    val y = yInput.text?.toString()?.trim()?.toFloatOrNull()
                    if (x == null || x !in 0f..100f) {
                        xInput.error = "请输入 0 到 100 之间的数值"
                    }
                    if (y == null || y !in 0f..100f) {
                        yInput.error = "请输入 0 到 100 之间的数值"
                    }
                    return@setOnClickListener
                }
                val alignment = selectedAlignment
                onSelected(alignment, selectedCoordinate)
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                dialog.dismiss()
            }
            val width = minOf(
                activity.resources.displayMetrics.widthPixels - dp(24),
                dp(520)
            ).coerceAtLeast(dp(240))
            dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
        return dialog
    }

    private fun formatCoordinate(value: Float): String =
        "%.1f".format(java.util.Locale.US, value).trimEnd('0').trimEnd('.')

    private fun alignmentBackground(selected: Boolean): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = activity.resources.displayMetrics.density * 4f
            setColor(
                if (selected) 0x223F51B5 else
                    ContextCompat.getColor(activity, R.color.surface_container_high)
            )
            setStroke(
                (activity.resources.displayMetrics.density * if (selected) 2 else 1).toInt(),
                if (selected) ContextCompat.getColor(activity, R.color.primary)
                else ContextCompat.getColor(activity, R.color.outline_variant)
            )
        }
}
