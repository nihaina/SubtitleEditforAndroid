package com.subtitleedit

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme

/** Hosts Compose dialogs for imperative Android controllers without replacing the Activity content. */
internal object ComposeDialogHost {
    fun show(
        activity: Activity,
        content: @Composable (ComposeDialogHandle) -> Unit
    ): ComposeDialogHandle {
        val contentRoot = activity.findViewById<ViewGroup>(android.R.id.content)
        val hostView = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isClickable = false
        }
        lateinit var handle: ComposeDialogHandle
        handle = ComposeDialogHandle(hostView)
        hostView.setContent {
            SubtitleEditComposeTheme {
                content(handle)
            }
        }
        contentRoot.addView(hostView, ViewGroup.LayoutParams(1, 1))
        return handle
    }
}

internal class ComposeDialogHandle internal constructor(
    private val hostView: ComposeView
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val dismissListeners = mutableListOf<() -> Unit>()

    @Volatile
    private var showing = true

    val isShowing: Boolean
        get() = showing

    fun dismiss() {
        if (!showing) return
        showing = false
        val remove = {
            (hostView.parent as? ViewGroup)?.removeView(hostView)
            dismissListeners.toList().forEach { it() }
            dismissListeners.clear()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) remove() else mainHandler.post(remove)
    }

    fun addOnDismissListener(listener: () -> Unit) {
        if (showing) dismissListeners += listener else listener()
    }
}
