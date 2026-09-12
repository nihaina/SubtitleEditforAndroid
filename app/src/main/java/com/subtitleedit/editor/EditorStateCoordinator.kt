package com.subtitleedit.editor

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.subtitleedit.EditorEffect
import com.subtitleedit.EditorUiState
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Observes editor state and transient effects and forwards only UI work to the host. */
internal class EditorStateCoordinator(
    private val lifecycleOwner: LifecycleOwner,
    private val state: StateFlow<EditorUiState>,
    private val effects: SharedFlow<EditorEffect>,
    private val onStateChanged: (EditorUiState, EditorUiState?) -> Unit,
    private val invalidateMenu: () -> Unit,
    private val showMessage: (String) -> Unit
) {
    private var previousState: EditorUiState? = null

    fun bind() {
        lifecycleOwner.lifecycleScope.launch {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    state.collect { current ->
                        onStateChanged(current, previousState)
                        previousState = current
                    }
                }
                launch {
                    effects.collect { effect ->
                        when (effect) {
                            EditorEffect.InvalidateOptionsMenu -> invalidateMenu()
                            is EditorEffect.ShowMessage -> showMessage(effect.message)
                        }
                    }
                }
            }
        }
    }
}
