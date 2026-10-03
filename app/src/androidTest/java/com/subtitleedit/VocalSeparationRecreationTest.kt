package com.subtitleedit

import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression for the rotation bug: page state must live in the ViewModel and
 * survive Activity recreation (rotation, theme or locale change).
 */
@RunWith(AndroidJUnit4::class)
class VocalSeparationRecreationTest {

    @Test
    fun selectedFilesSurviveRecreation() {
        ActivityScenario.launch(VocalSeparationActivity::class.java).use { scenario ->
            lateinit var before: VocalSeparationViewModel
            scenario.onActivity { activity ->
                before = ViewModelProvider(activity)[VocalSeparationViewModel::class.java]
                before.selectFiles(listOf(Uri.parse("file:///nonexistent/song.mp3")))
            }

            scenario.recreate()

            scenario.onActivity { activity ->
                val after = ViewModelProvider(activity)[VocalSeparationViewModel::class.java]
                assertSame(before, after)
                assertTrue(after.state.value.hasSelectedFiles)
                assertTrue(after.state.value.selectedFilesText.orEmpty().contains("song.mp3"))
            }
        }
    }
}
