package com.subtitleedit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow

/** Keep the last displayed value while hidden; resume from the current source on activation. */
@Composable
internal fun <T> StateFlow<T>.collectRetainedPageState(active: Boolean): State<T> {
    val retained = remember(this) { mutableStateOf(value) }
    if (!active) return retained
    // Re-enter with StateFlow.value immediately, so query keys never briefly use a stale revision.
    val current = collectAsStateWithLifecycle()
    SideEffect { retained.value = current.value }
    return current
}
