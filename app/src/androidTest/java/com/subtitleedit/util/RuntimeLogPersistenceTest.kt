package com.subtitleedit.util

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import java.io.ByteArrayOutputStream
import java.util.UUID
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeLogPersistenceTest {
    @Test fun readingAndExportingDrainQueuedAppMessages() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val previewMarker = "preview-barrier-${UUID.randomUUID()}"
        RuntimeLogManager.i("PersistenceTest", previewMarker)
        val snapshot = RuntimeLogManager.captureRecent(context, RuntimeLogManager.DisplayMode.DETAILED)
        assertTrue(snapshot.content.contains(previewMarker))

        val exportMarker = "export-barrier-${UUID.randomUUID()}"
        RuntimeLogManager.i("PersistenceTest", exportMarker)
        val output = ByteArrayOutputStream()
        RuntimeLogManager.exportRecent(context, RuntimeLogManager.DisplayMode.DETAILED, output)
        assertTrue(output.toString(Charsets.UTF_8.name()).contains(exportMarker))
    }
}
