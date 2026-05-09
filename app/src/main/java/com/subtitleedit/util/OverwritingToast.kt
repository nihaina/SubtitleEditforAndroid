package com.subtitleedit.util

import android.content.Context
import android.widget.Toast

/**
 * Toast helper that replaces any still-visible toast with the latest message.
 */
object OverwritingToast {
    private var currentToast: Toast? = null

    fun makeText(context: Context, text: CharSequence, duration: Int): Toast {
        currentToast?.cancel()
        return Toast.makeText(context.applicationContext, text, duration).also {
            currentToast = it
        }
    }

    fun makeText(context: Context, resId: Int, duration: Int): Toast {
        currentToast?.cancel()
        return Toast.makeText(context.applicationContext, resId, duration).also {
            currentToast = it
        }
    }
}
