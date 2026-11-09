package com.subtitleedit.mpv

import android.content.Context
import android.net.Uri
import com.subtitleedit.util.PickedUriAccess
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Uses its own paused libmpv instance; it never borrows or changes the editor player. */
internal object MpvSubtitleProbe {
    init {
        System.loadLibrary("mpv")
        System.loadLibrary("subtitleedit_mpv_probe")
    }

    private val mutex = Mutex()

    suspend fun read(context: Context, uri: Uri): List<MpvSubtitleTrack> = readDetails(context, uri).subtitleTracks

    suspend fun readDetails(context: Context, uri: Uri): MpvMediaProbeResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val file = PickedUriAccess.file(uri)
            val fields = if (file != null) {
                nativeProbe(file.absolutePath.toByteArray(Charsets.UTF_8), 10_000L)
            } else {
                val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: throw IOException("Cannot open media for subtitle detection")
                descriptor.use {
                    nativeProbe("/proc/self/fd/${it.fd}".toByteArray(Charsets.UTF_8), 10_000L)
                }
            }
            MpvMediaProbeResultDecoder.decode(fields)
        }
    }

    private external fun nativeProbe(path: ByteArray, timeoutMs: Long): Array<String>
}
