package com.subtitleedit.view

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.subtitleedit.R
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

internal class SourceLineBlock(
    val stableId: Long,
    var text: String,
    var lineEnding: String
) {
    val serializedLength: Int
        get() = text.length + lineEnding.length
}

internal data class SourceLineHighlight(val start: Int, val end: Int, val color: Int)

/** RecyclerView adapter whose item lifecycle mirrors the subtitle-list adapter. */
internal class SourceLineAdapter(
    private val context: Context,
    private val lines: List<SourceLineBlock>,
    private val onTextChanged: (Int, String, Int) -> Unit,
    private val onSplitLine: (Int, Int) -> Boolean,
    private val onMergePrevious: (Int) -> Boolean,
    private val onMergeNext: (Int) -> Boolean,
    private val onMoveVertical: (Int, Int, Int) -> Boolean,
    private val highlightsForLine: (Int) -> List<SourceLineHighlight>
) : RecyclerView.Adapter<SourceLineAdapter.LineViewHolder>() {

    private var editorEnabled = true
    private var pendingFocusPosition = RecyclerView.NO_POSITION
    private var pendingFocusColumn = 0
    private var gutterWidthPx = computeGutterWidth(1)

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = lines[position].stableId

    override fun getItemCount(): Int = lines.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LineViewHolder {
        return LineViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_source_line, parent, false)
        ).also { it.bindGutterWidth(gutterWidthPx) }
    }

    override fun onBindViewHolder(holder: LineViewHolder, position: Int) {
        holder.bindGutterWidth(gutterWidthPx)
        holder.bind(lines[position], position, editorEnabled, highlightsForLine(position))
        applyPendingFocus(holder, position)
    }

    override fun onBindViewHolder(
        holder: LineViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position)
            return
        }
        if (payloads.contains(PAYLOAD_INDEX)) holder.bindIndex(position)
        if (payloads.contains(PAYLOAD_GUTTER)) holder.bindGutterWidth(gutterWidthPx)
        if (payloads.contains(PAYLOAD_HIGHLIGHT)) {
            holder.bindHighlights(highlightsForLine(position))
        }
        if (payloads.contains(PAYLOAD_ENABLED)) holder.bindEnabled(editorEnabled)
        if (payloads.contains(PAYLOAD_TEXT)) {
            holder.bind(lines[position], position, editorEnabled, highlightsForLine(position))
        }
        applyPendingFocus(holder, position)
    }

    fun setEditorEnabled(enabled: Boolean) {
        if (editorEnabled == enabled) return
        editorEnabled = enabled
        notifyItemRangeChanged(0, itemCount, PAYLOAD_ENABLED)
    }

    fun refreshHighlights() {
        notifyItemRangeChanged(0, itemCount, PAYLOAD_HIGHLIGHT)
    }

    /**
     * Recalculate the gutter from the largest line number instead of reserving a fixed 56dp.
     * The width is shared by every row so line content remains vertically aligned while the
     * document grows from single- to multi-digit line numbers (and shrinks again after merges).
     */
    fun setLineCount(lineCount: Int, notify: Boolean = true) {
        val width = computeGutterWidth(lineCount)
        if (width == gutterWidthPx) return
        gutterWidthPx = width
        if (notify && itemCount > 0) {
            notifyItemRangeChanged(0, itemCount, PAYLOAD_GUTTER)
        }
    }

    fun notifyLineSplit(position: Int, insertedCount: Int) {
        notifyItemChanged(position, PAYLOAD_TEXT)
        if (insertedCount > 0) notifyItemRangeInserted(position + 1, insertedCount)
        val firstShifted = position + insertedCount + 1
        if (firstShifted < itemCount) {
            notifyItemRangeChanged(firstShifted, itemCount - firstShifted, PAYLOAD_INDEX)
        }
        // The insertion notification above must be dispatched before changing the width of
        // the newly-created last row; otherwise RecyclerView would receive a range-change
        // for a position that did not exist in its previous item count.
        setLineCount(lines.size)
    }

    fun notifyLinesMerged(changedPosition: Int, removedPosition: Int) {
        notifyItemChanged(changedPosition, PAYLOAD_TEXT)
        notifyItemRemoved(removedPosition)
        if (removedPosition < itemCount) {
            notifyItemRangeChanged(removedPosition, itemCount - removedPosition, PAYLOAD_INDEX)
        }
        setLineCount(lines.size)
    }

    fun requestLineFocus(position: Int, column: Int) {
        pendingFocusPosition = position
        pendingFocusColumn = column
        if (position in lines.indices) notifyItemChanged(position, PAYLOAD_FOCUS)
    }

    private fun applyPendingFocus(holder: LineViewHolder, position: Int) {
        if (position != pendingFocusPosition) return
        val column = pendingFocusColumn
        pendingFocusPosition = RecyclerView.NO_POSITION
        pendingFocusColumn = 0
        holder.focus(column)
    }

    inner class LineViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val lineNumber: TextView = itemView.findViewById(R.id.tvSourceLineNumber)
        private val editor: SourceLineEditText = itemView.findViewById(R.id.etSourceLine)
        private var binding = false

        init {
            // Keep one physical source line per holder. Enter is handled by SourceLineEditText
            // and converted into an adapter insertion before TextView can wrap the row.
            editor.setSingleLine(false)
            editor.setHorizontallyScrolling(true)
            editor.imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_ENTER_ACTION
            editor.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

                override fun afterTextChanged(s: Editable?) {
                    if (binding) return
                    val position = bindingAdapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        this@SourceLineAdapter.onTextChanged(
                            position,
                            s?.toString().orEmpty(),
                            editor.selectionStart.coerceAtLeast(0)
                        )
                    }
                }
            })
            editor.mergePrevious = {
                val position = bindingAdapterPosition
                position != RecyclerView.NO_POSITION && onMergePrevious(position)
            }
            editor.splitLine = { column ->
                val position = bindingAdapterPosition
                position != RecyclerView.NO_POSITION && onSplitLine(position, column)
            }
            editor.mergeNext = {
                val position = bindingAdapterPosition
                position != RecyclerView.NO_POSITION && onMergeNext(position)
            }
            editor.moveVertical = { direction, column ->
                val position = bindingAdapterPosition
                position != RecyclerView.NO_POSITION && onMoveVertical(position, direction, column)
            }
            editor.onFocusChangeListener = View.OnFocusChangeListener { _, focused ->
                itemView.setBackgroundColor(
                    ContextCompat.getColor(
                        itemView.context,
                        if (focused) R.color.source_current_line else R.color.surface
                    )
                )
            }
        }

        fun bind(
            line: SourceLineBlock,
            position: Int,
            enabled: Boolean,
            highlights: List<SourceLineHighlight>
        ) {
            bindGutterWidth(gutterWidthPx)
            bindIndex(position)
            bindEnabled(enabled)
            binding = true
            try {
                if (editor.text?.toString() != line.text) editor.setText(line.text)
                applyHighlights(highlights)
            } finally {
                binding = false
            }
        }

        fun bindIndex(position: Int) {
            lineNumber.text = (position + 1).toString()
        }

        fun bindGutterWidth(widthPx: Int) {
            if (widthPx <= 0) return
            val params = lineNumber.layoutParams ?: return
            if (params.width != widthPx) {
                params.width = widthPx
                lineNumber.layoutParams = params
            }
        }

        fun bindEnabled(enabled: Boolean) {
            editor.isEnabled = enabled
        }

        fun bindHighlights(highlights: List<SourceLineHighlight>) {
            binding = true
            try {
                applyHighlights(highlights)
            } finally {
                binding = false
            }
        }

        fun focus(column: Int) {
            editor.requestFocus()
            editor.setSelection(column.coerceIn(0, editor.length()))
        }

        private fun applyHighlights(highlights: List<SourceLineHighlight>) {
            val editable = editor.text ?: return
            editable.getSpans(0, editable.length, BackgroundColorSpan::class.java)
                .forEach(editable::removeSpan)
            highlights.forEach { highlight ->
                val start = highlight.start.coerceIn(0, editable.length)
                val end = highlight.end.coerceIn(start, editable.length)
                if (start < end) {
                    editable.setSpan(
                        BackgroundColorSpan(highlight.color),
                        start,
                        end,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
        }
    }

    private companion object {
        const val PAYLOAD_INDEX = "source_index"
        const val PAYLOAD_TEXT = "source_text"
        const val PAYLOAD_HIGHLIGHT = "source_highlight"
        const val PAYLOAD_ENABLED = "source_enabled"
        const val PAYLOAD_FOCUS = "source_focus"
        const val PAYLOAD_GUTTER = "source_gutter"
    }

    private fun computeGutterWidth(lineCount: Int): Int {
        val resources = context.resources
        val density = resources.displayMetrics.density
        val digits = lineCount.coerceAtLeast(1).toString().length
        val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                12f,
                resources.displayMetrics
            )
        }
        val numberWidth = ceil(numberPaint.measureText("8".repeat(digits))).toInt()
        val horizontalPadding = (12f * density).roundToInt() // XML: 4dp start + 8dp end
        // Leave a small allowance for TextView/font rounding differences at digit boundaries
        // (for example, the transition from 9999 to 10000).
        val measurementAllowance = (2f * density).roundToInt()
        val minimumWidth = (24f * density).roundToInt()
        return max(minimumWidth, numberWidth + horizontalPadding + measurementAllowance)
    }
}
