package com.subtitleedit

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.subtitleedit.util.OverwritingToast
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Base for Compose page ViewModels. Page state and long tasks live here so they
 * survive configuration changes; the Activity only renders state, launches
 * pickers/navigation and forwards lifecycle callbacks.
 *
 * Long tasks should be launched on [viewModelScope][androidx.lifecycle.viewModelScope]
 * and are cancelled in [onCleared], i.e. only when the page really finishes.
 */
internal abstract class AppViewModel<S, E>(
    application: Application,
    initialState: S
) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<S> = _state.asStateFlow()

    /** Snapshot of the current state for logic that should not subscribe. */
    protected val currentState: S get() = _state.value

    private val eventChannel = Channel<E>(Channel.BUFFERED)

    /** One-shot events (navigation, activity results) consumed by the Activity. */
    val events: Flow<E> = eventChannel.receiveAsFlow()

    protected val app: Application get() = getApplication()

    protected val dependencies get() = (app as SubtitleEditApplication).dependencies

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Thread-safe state update; may be called from any dispatcher. */
    protected fun setState(reducer: S.() -> S) = _state.update(reducer)

    protected fun sendEvent(event: E) {
        eventChannel.trySend(event)
    }

    protected fun string(@StringRes id: Int, vararg args: Any): String =
        if (args.isEmpty()) app.getString(id) else app.getString(id, *args)

    protected fun toast(text: CharSequence, duration: Int = Toast.LENGTH_SHORT) {
        val show = { OverwritingToast.makeText(app, text, duration).show() }
        if (Looper.myLooper() == Looper.getMainLooper()) show() else mainHandler.post(show)
    }

    protected fun toast(@StringRes id: Int, vararg args: Any, duration: Int = Toast.LENGTH_SHORT) =
        toast(string(id, *args), duration)

    override fun onCleared() {
        mainHandler.removeCallbacksAndMessages(null)
        eventChannel.close()
        super.onCleared()
    }
}

/** Collects one-shot ViewModel events while the owner is at least STARTED. */
internal fun <E> LifecycleOwner.collectEvents(events: Flow<E>, handler: (E) -> Unit) {
    lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.STARTED) {
            events.collect(handler)
        }
    }
}
