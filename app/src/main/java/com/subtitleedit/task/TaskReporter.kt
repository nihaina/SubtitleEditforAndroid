package com.subtitleedit.task

internal class TaskReporter(
    private val store: TaskStateStore,
    val id: String,
    val type: String
) {
    init {
        store.create(id, type)
    }

    fun queued() {
        store.update(id, TaskStatus.QUEUED)
    }

    fun running(progress: TaskProgress? = null) {
        store.update(id, TaskStatus.RUNNING, progress)
    }

    fun succeeded(progress: TaskProgress? = null) {
        store.update(id, TaskStatus.SUCCEEDED, progress)
    }

    fun failed(error: Throwable, progress: TaskProgress? = null) {
        store.update(
            id,
            TaskStatus.FAILED,
            progress ?: store.states.value[id]?.progress ?: TaskProgress(),
            error.message
        )
    }

    fun cancelled(progress: TaskProgress? = null) {
        store.update(id, TaskStatus.CANCELLED, progress)
    }
}
