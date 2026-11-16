package com.subtitleedit.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.annotation.WorkerThread
import androidx.core.content.ContextCompat
import com.subtitleedit.R
import com.subtitleedit.library.SharedMediaCache
import com.subtitleedit.util.SharedFileCache

/** Notification metadata shares the browser's versioned tags and embedded artwork. */
internal data class PlayerMediaNotificationMetadata(
    val path: String,
    val title: String,
    val artist: String,
    val album: String,
    val artwork: Bitmap?,
    val durationMs: Long
) {
    companion object {
        private const val ARTWORK_SIZE = 320
        private const val FALLBACK_SIZE = 192

        /** Load for a track or file revision change, never for a playback progress tick. */
        @WorkerThread
        fun load(context: Context, track: PlayerTrack, fallbackDurationMs: Long): PlayerMediaNotificationMetadata {
            val file = track.file
            val metadata = try { SharedFileCache.attributes.get(file) } catch (_: Exception) { null }
            if (metadata != null && fallbackDurationMs > 0L) {
                SharedMediaCache.seedDuration(metadata, fallbackDurationMs)
            }
            val cached = try { metadata?.let(SharedMediaCache::read) } catch (_: Exception) { null }
            val cover = try {
                metadata?.let { SharedMediaCache.artwork(it, ARTWORK_SIZE, allowVideoFrame = false) }
                    ?.let(::boundedArtwork)
            } catch (_: Exception) { null }
            return PlayerMediaNotificationMetadata(
                path = track.path,
                title = cached?.tags?.title?.takeIf { it.isNotBlank() }
                    ?: file.nameWithoutExtension.ifBlank { track.name.ifBlank { file.name } },
                artist = cached?.tags?.artist.orEmpty(),
                album = cached?.tags?.album.orEmpty(),
                artwork = cover ?: defaultArtwork(context, track.isVideo),
                durationMs = fallbackDurationMs.takeIf { it > 0L } ?: cached?.durationMs?.coerceAtLeast(0L) ?: 0L
            )
        }

        private fun boundedArtwork(bitmap: Bitmap): Bitmap {
            val largest = maxOf(bitmap.width, bitmap.height)
            if (largest <= ARTWORK_SIZE) return bitmap
            val scale = ARTWORK_SIZE.toFloat() / largest
            // The source belongs to SharedMediaCache and must not be recycled here.
            return Bitmap.createScaledBitmap(bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1), true)
        }

        private fun defaultArtwork(context: Context, isVideo: Boolean): Bitmap? = try {
            val icon = ContextCompat.getDrawable(context,
                if (isVideo) R.drawable.ic_file_video else R.drawable.ic_file_audio)?.mutate()
            icon?.let {
                Bitmap.createBitmap(FALLBACK_SIZE, FALLBACK_SIZE, Bitmap.Config.ARGB_8888).also { bitmap ->
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(ContextCompat.getColor(context, R.color.surface_container))
                    it.setTint(ContextCompat.getColor(context, R.color.on_surface_variant))
                    it.setBounds(32, 32, FALLBACK_SIZE - 32, FALLBACK_SIZE - 32)
                    it.draw(canvas)
                }
            }
        } catch (_: Exception) { null }
    }
}
