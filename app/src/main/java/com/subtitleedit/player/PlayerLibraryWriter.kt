package com.subtitleedit.player

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Ordered process-owned work; closing an Activity cannot cancel an accepted final save. */
internal object PlayerLibraryWriter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Channel<() -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (command in commands) {
                try {
                    command()
                } catch (error: Exception) {
                    Log.e("PlayerLibrary", "Could not persist player library state", error)
                }
            }
        }
    }

    fun submit(command: () -> Unit) {
        check(commands.trySend(command).isSuccess)
    }

    /** Reads share the queue so their results include all earlier player writes. */
    suspend fun <T> execute(command: () -> T): T {
        val result = CompletableDeferred<Result<T>>()
        commands.send { result.complete(runCatching(command)) }
        return result.await().getOrThrow()
    }
}
