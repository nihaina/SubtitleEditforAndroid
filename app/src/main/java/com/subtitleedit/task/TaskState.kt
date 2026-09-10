package com.subtitleedit.task

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal enum class TaskStatus {
    QUEUED,
    RUNNING,
    CANCELLING,
    SUCCEEDED,
    FAILED,
    CANCELLED
}

internal data class TaskProgress(
    val message: String = "",
    val current: Long = 0L,
    val total: Long = -1L
)

internal data class TaskState(
    val id: String,
    val type: String,
    val status: TaskStatus,
    val progress: TaskProgress = TaskProgress(),
    val errorMessage: String? = null,
    val outputPath: String? = null
)

internal class TaskStateStore {
    private val _states = MutableStateFlow<Map<String, TaskState>>(emptyMap())
    val states: StateFlow<Map<String, TaskState>> = _states.asStateFlow()

    fun create(id: String, type: String): TaskState = updateState(
        TaskState(id = id, type = type, status = TaskStatus.QUEUED)
    )

    @Synchronized
    fun updateState(state: TaskState): TaskState {
        val previous = _states.value[state.id]
        if (previous != null && previous.status.isTerminal() && !state.status.isTerminal()) {
            return previous
        }
        if (previous?.status == TaskStatus.CANCELLING &&
            (state.status == TaskStatus.QUEUED || state.status == TaskStatus.RUNNING)) {
            return previous
        }
        _states.value = _states.value + (state.id to state)
        return state
    }

    fun update(
        id: String,
        status: TaskStatus,
        progress: TaskProgress? = null,
        errorMessage: String? = null
    ): TaskState? {
        val previous = _states.value[id] ?: return null
        return updateState(
            previous.copy(
                status = status,
                progress = progress ?: previous.progress,
                errorMessage = errorMessage
            )
        )
    }

    fun remove(id: String) {
        _states.update { it - id }
    }

    private fun TaskStatus.isTerminal(): Boolean = when (this) {
        TaskStatus.SUCCEEDED, TaskStatus.FAILED, TaskStatus.CANCELLED -> true
        TaskStatus.QUEUED, TaskStatus.RUNNING, TaskStatus.CANCELLING -> false
    }
}
