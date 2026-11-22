package com.subtitleedit.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View.MeasureSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerOverlayCaptionViewTest {
    @Test fun timedWrappingScrollsFromTheFirstLineToTheLastInAOneLineViewport() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = PlayerOverlayCaptionView(instrumentation.targetContext)
            val text = "First words in this line lead to a different phrase at the very end"
            val cue = PlayerOverlaySubtitleCue(1, text, PlayerSubtitleCueTiming(1_000, 5_000), 1_000, 0, false, 1f)
            val style = PlayerFloatingSubtitleStyle(colorArgb = Color.WHITE, outlineColorArgb = Color.RED)
            val width = instrumentation.targetContext.captionPixels(220)
            fun draw(): Bitmap {
                view.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), 0)
                val height = view.measuredHeight
                view.layout(0, 0, width, height)
                return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    .also { view.draw(Canvas(it)) }
            }
            view.applyStyle(style)
            view.setCaption(text, cue)
            val full = draw()
            view.applyStyle(style.copy(scrollEnabled = true))
            val first = draw()
            assertTrue("Wrapped text must take less space with scrolling enabled", first.height < full.height)
            val pixels = IntArray(first.width * first.height)
            first.getPixels(pixels, 0, first.width, 0, 0, first.width, first.height)
            assertTrue("The configured red outline must be visible", pixels.any {
                Color.red(it) > 160 && Color.green(it) < 100 && Color.blue(it) < 100
            })
            view.setCaption(text, cue.copy(positionMs = 1_500))
            val heldFirst = draw()
            view.setCaption(text, cue.copy(positionMs = 3_000))
            val moving = draw()
            view.setCaption(text, cue.copy(positionMs = 4_500))
            val last = draw()
            view.setCaption(text, cue.copy(positionMs = 4_950))
            val heldLast = draw()
            assertTrue(first.sameAs(heldFirst))
            assertFalse(first.sameAs(moving))
            assertFalse(moving.sameAs(last))
            assertTrue(last.sameAs(heldLast))
            listOf(full, first, heldFirst, moving, last, heldLast).forEach(Bitmap::recycle)
        }
    }
}
