package com.subtitleedit.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskStateTest {
    @Test
    fun reporterPublishesOneSharedTaskLifecycle() {
        val store = TaskStateStore()
        val reporter = TaskReporter(store, "task-1", "demo")

        reporter.running(TaskProgress("处理中", 2L, 10L))
        assertEquals(TaskStatus.RUNNING, store.states.value["task-1"]?.status)
        assertEquals(2L, store.states.value["task-1"]?.progress?.current)

        reporter.succeeded(TaskProgress("完成", 10L, 10L))
        assertEquals(TaskStatus.SUCCEEDED, store.states.value["task-1"]?.status)
        assertEquals("完成", store.states.value["task-1"]?.progress?.message)

        store.remove("task-1")
        assertNull(store.states.value["task-1"])
    }

    @Test
    fun failureKeepsProgressAndExposesErrorMessage() {
        val store = TaskStateStore()
        val reporter = TaskReporter(store, "task-2", "demo")
        reporter.running(TaskProgress("处理中", 3L, 10L))

        reporter.failed(IllegalStateException("failed"))

        val state = store.states.value.getValue("task-2")
        assertEquals(TaskStatus.FAILED, state.status)
        assertEquals("failed", state.errorMessage)
        assertEquals(3L, state.progress.current)
    }

    @Test
    fun terminalStateCannotBeRegressedByLateProgress() {
        val store = TaskStateStore()
        val reporter = TaskReporter(store, "task-3", "demo")
        reporter.succeeded(TaskProgress("完成", 10L, 10L))

        val published = store.updateState(
            TaskState("task-3", "demo", TaskStatus.RUNNING, TaskProgress("迟到的进度"))
        )

        assertEquals(TaskStatus.SUCCEEDED, published.status)
        assertEquals(TaskStatus.SUCCEEDED, store.states.value.getValue("task-3").status)
        assertEquals("完成", store.states.value.getValue("task-3").progress.message)
    }
}
