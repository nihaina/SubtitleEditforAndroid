package com.subtitleedit.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.ui.components.SettingsGroup
import com.subtitleedit.ui.settings.settingsGroupItems
import com.subtitleedit.ui.theme.SubtitleEditComposeTheme
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The lazy row joins must keep the old rounded silhouette, continuous fill and outer border. */
@RunWith(AndroidJUnit4::class)
class SettingsLazyGroupShapeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun materialRowsKeepTheOriginalGroupPixels() = compareGroups(true)

    @Test fun legacyRowsKeepTheOriginalGroupPixels() = compareGroups(false)

    private fun compareGroups(material: Boolean) {
        compose.setContent {
            SubtitleEditComposeTheme {
                // A fixed integral density compares the group rasterization itself, rather
                // than device-dependent rounding of the reference's individual row heights.
                CompositionLocalProvider(LocalDensity provides Density(3f, LocalDensity.current.fontScale)) {
                Column(Modifier.width(240.dp).background(Color.Magenta)) {
                    Box(Modifier.testTag("reference")) {
                        if (material) {
                            Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.extraLarge),
                                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                repeat(3) { Tile(material) }
                            }
                        } else SettingsGroup { repeat(3) { Tile(material) } }
                    }
                    LazyColumn(Modifier.height(if (material) 172.dp else 176.dp).testTag("lazy")) {
                        settingsGroupItems("group", 3, material) { Tile(material) }
                    }
                }
                }
            }
        }
        val reference = compose.onNodeWithTag("reference").captureToImage().toPixelMap()
        val actual = compose.onNodeWithTag("lazy").captureToImage().toPixelMap()
        assertEquals(reference.width, actual.width)
        assertEquals(reference.height, actual.height)
        var different = 0
        for (y in 0 until reference.height) for (x in 0 until reference.width) {
            val a = reference[x, y]
            val b = actual[x, y]
            if (abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue) > 0.04f) different++
        }
        assertTrue("Group pixels changed at $different points", different < reference.width * reference.height * 0.003)
    }

    @Composable private fun Tile(material: Boolean) {
        Box(Modifier.fillMaxWidth().height(56.dp).then(
            if (material) Modifier.background(Color(0xFFFAF7FE), RoundedCornerShape(4.dp)) else Modifier
        ))
    }
}
