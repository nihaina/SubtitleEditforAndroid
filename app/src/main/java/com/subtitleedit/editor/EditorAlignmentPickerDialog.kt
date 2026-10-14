package com.subtitleedit.editor

import android.app.AlertDialog
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.subtitleedit.R
import com.subtitleedit.util.SubtitleAlignmentOps

/**
 * Subtitle Edit compatible nine position picker.
 *
 * Subtitle Edit calls the positions an1..an9.  The visual order is deliberately
 * top-to-bottom (an7..an9, an4..an6, an1..an3), matching its desktop picker.
 */
internal class EditorAlignmentPickerDialog(private val activity: AppCompatActivity) {

    fun show(
        initialAlignment: Int? = null,
        onSelected: (Int) -> Unit
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
                background = alignmentBackground(alignment.number == initialAlignment)
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

        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            addView(grid, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("选择对齐方式")
            .setView(scroll)
            .create()
        buttons.forEach { button ->
            button.setOnClickListener {
                val index = buttons.indexOf(button)
                onSelected(SubtitleAlignmentOps.alignments[index].number)
                dialog.dismiss()
            }
        }
        dialog.setOnShowListener {
            val width = minOf(
                activity.resources.displayMetrics.widthPixels - dp(24),
                dp(520)
            ).coerceAtLeast(dp(240))
            dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
        return dialog
    }

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
