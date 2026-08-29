package com.subtitleedit.view

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.appcompat.widget.AppCompatEditText

/** Single source-line input that delegates cross-line editing to the RecyclerView model. */
internal class SourceLineEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    var mergePrevious: (() -> Boolean)? = null
    var mergeNext: (() -> Boolean)? = null
    var splitLine: ((Int) -> Boolean)? = null
    var moveVertical: ((Int, Int) -> Boolean)? = null

    override fun onCreateInputConnection(outAttrs: android.view.inputmethod.EditorInfo): InputConnection? {
        val target = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(target, true) {
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (selectionStart == selectionEnd) {
                    if (beforeLength > 0 && afterLength == 0 && selectionStart == 0) {
                        if (mergePrevious?.invoke() == true) return true
                    }
                    if (beforeLength == 0 && afterLength > 0 && selectionStart == length()) {
                        if (mergeNext?.invoke() == true) return true
                    }
                }
                return super.deleteSurroundingText(beforeLength, afterLength)
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN && handleBoundaryKey(event.keyCode)) {
                    return true
                }
                return super.sendKeyEvent(event)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (handleBoundaryKey(keyCode)) return true
        return super.onKeyDown(keyCode, event)
    }

    private fun handleBoundaryKey(keyCode: Int): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ENTER && selectionStart == selectionEnd) {
            if (splitLine?.invoke(selectionStart.coerceAtLeast(0)) == true) return true
        }
        if (selectionStart != selectionEnd) return false
        return when {
            keyCode == KeyEvent.KEYCODE_DEL && selectionStart == 0 ->
                mergePrevious?.invoke() == true
            keyCode == KeyEvent.KEYCODE_FORWARD_DEL && selectionStart == length() ->
                mergeNext?.invoke() == true
            keyCode == KeyEvent.KEYCODE_DPAD_UP ->
                moveVertical?.invoke(-1, selectionStart.coerceAtLeast(0)) == true
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN ->
                moveVertical?.invoke(1, selectionStart.coerceAtLeast(0)) == true
            else -> false
        }
    }
}
