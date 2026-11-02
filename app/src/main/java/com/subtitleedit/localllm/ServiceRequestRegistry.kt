package com.subtitleedit.localllm

/** Couples callback-based requests to the connection that must complete them. */
internal class ServiceRequestRegistry {
    private val lock = Any()
    private var connection: Any? = null
    private val requests = mutableMapOf<Any, (Throwable) -> Unit>()

    fun connected(token: Any) {
        val abandoned = synchronized(lock) {
            if (connection === token) return
            connection = token
            requests.values.toList().also { requests.clear() }
        }
        abandoned.forEach { it(IllegalStateException("本地 LLM 服务连接已更换")) }
    }

    fun register(token: Any, fail: (Throwable) -> Unit): AutoCloseable {
        val request = Any()
        val registered = synchronized(lock) {
            if (connection !== token) false else {
                requests[request] = fail
                true
            }
        }
        if (!registered) fail(IllegalStateException("本地 LLM 服务已断开"))
        return AutoCloseable { synchronized(lock) { requests.remove(request) }; Unit }
    }

    fun disconnected(token: Any?, error: Throwable) {
        val abandoned = synchronized(lock) {
            if (connection !== token) return
            connection = null
            requests.values.toList().also { requests.clear() }
        }
        abandoned.forEach { it(error) }
    }
}
