package com.subtitleedit.player

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.subtitleedit.R
import com.subtitleedit.library.MediaLibraryRepository
import com.subtitleedit.library.MediaLibraryTrack
import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** View-model integration uses a media-stream stand-in so these assertions never alter the phone. */
@RunWith(AndroidJUnit4::class)
class PlayerSystemVolumeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application

    private class MediaStream : PlayerSystemVolume {
        var actual = SystemMediaVolume(index = 3, minIndex = 0, maxIndex = 15)
        var ceiling = 15

        override fun read(): SystemMediaVolume = actual

        override fun setIndex(index: Int) {
            val accepted = index.coerceIn(0, ceiling)
            actual = actual.copy(index = accepted, muted = accepted == 0)
        }
    }

    @Test fun gestureHintShowsTheAcceptedSystemStepAndStopsBeingPinnedAfterRelease() = Fixture().use { fixture ->
        onMain {
            assertEquals("Initial player volume mirrors the phone", 20, fixture.model.state.value.volumePercent)
            fixture.model.setVolumeFromGesture(50)
            val state = fixture.model.state.value
            assertEquals(8, fixture.stream.actual.index)
            assertEquals(53, state.volumePercent)
            assertEquals(app.getString(R.string.player_volume_percent, 53), state.gestureHint?.text)
            assertEquals(R.drawable.ic_player_volume, state.gestureHint?.iconRes)
            assertTrue(state.volumeGestureActive)

            fixture.model.endVolumeGesture()
            assertFalse(fixture.model.state.value.volumeGestureActive)
            assertEquals(state.gestureHint, fixture.model.state.value.gestureHint)
        }
    }

    @Test fun boostingAndExternalSystemChangesKeepTheDisplayedPercentInSync() = Fixture().use { fixture ->
        onMain {
            fixture.model.setVolumeFromGesture(160)
            var state = fixture.model.state.value
            assertEquals(15, fixture.stream.actual.index)
            assertEquals(160, state.volumePercent)
            assertEquals(app.getString(R.string.player_volume_boost_percent, 160), state.gestureHint?.text)

            // Simulate a hardware key or Android's media-volume slider changing the actual stream.
            fixture.stream.actual = fixture.stream.actual.copy(index = 6)
            assertEquals(40, fixture.model.refreshSystemVolume())
            assertEquals(40, fixture.model.state.value.volumePercent)
            fixture.model.setVolumeFromGesture(60)
            state = fixture.model.state.value
            assertEquals(9, fixture.stream.actual.index)
            assertEquals(app.getString(R.string.player_volume_percent, 60), state.gestureHint?.text)
        }
    }

    @Test fun rejectedSystemMaximumDoesNotOfferSoftwareBoostAndMuteReadsBackAsZero() = Fixture().use { fixture ->
        onMain {
            fixture.stream.ceiling = 9
            fixture.model.setVolumeFromGesture(200)
            var state = fixture.model.state.value
            assertEquals(9, fixture.stream.actual.index)
            assertEquals(60, state.volumePercent)
            assertEquals(app.getString(R.string.player_volume_percent, 60), state.gestureHint?.text)

            fixture.model.setVolumeFromGesture(0)
            state = fixture.model.state.value
            assertEquals(0, state.volumePercent)
            assertTrue(fixture.stream.actual.muted)
            assertEquals(app.getString(R.string.player_volume_percent, 0), state.gestureHint?.text)
        }
    }

    private inner class Fixture : Closeable {
        private val databaseName = "player-system-volume-${System.nanoTime()}.db"
        private val repository = MediaLibraryRepository.open(app, databaseName) { file, duration ->
            MediaLibraryTrack(file.path, file.nameWithoutExtension, durationMs = duration)
        }
        val stream = MediaStream()
        private val store = ViewModelStore()
        val model = mainValue {
            ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    PlayerViewModel(app, repository, stream) as T
            })[PlayerViewModel::class.java]
        }

        override fun close() {
            onMain { store.clear() }
            repository.close()
            app.deleteDatabase(databaseName)
        }
    }

    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync { action() }

    private fun <T> mainValue(action: () -> T): T {
        val result = AtomicReference<T>()
        onMain { result.set(action()) }
        return result.get()
    }
}
