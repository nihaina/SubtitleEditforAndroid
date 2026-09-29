package com.subtitleedit.adapter

import android.view.ContextThemeWrapper
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.subtitleedit.R
import com.subtitleedit.model.SubtitleEntry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SubtitleAdapterTimeConflictTest {
    @Test
    fun followingStartTimeRefreshesWhenPreviousEndBeginsOverlapping() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ContextThemeWrapper(
                InstrumentationRegistry.getInstrumentation().targetContext,
                R.style.Theme_SubtitleEditforAndroid
            )
            val adapter = SubtitleAdapter(
                onItemClick = { _, _ -> },
                onItemLongClick = { _, _ -> },
                onTimeClick = { _, _, _ -> },
                onTextClick = { _, _ -> },
                onJumpToTimeClick = { _, _ -> },
                onSetTimeClick = { _, _ -> }
            )
            val entries = listOf(
                SubtitleEntry(startTime = 0, endTime = 1000),
                SubtitleEntry(startTime = 1500, endTime = 2500)
            )
            adapter.submitList(entries)
            val parent = FrameLayout(context)
            val firstHolder = adapter.onCreateViewHolder(parent, 0)
            val secondHolder = adapter.onCreateViewHolder(parent, 0)
            val firstEnd = firstHolder.itemView.findViewById<TextView>(R.id.tvEndTime)
            val secondStart = secondHolder.itemView.findViewById<TextView>(R.id.tvStartTime)
            val normal = ContextCompat.getColor(context, R.color.primary)
            val error = ContextCompat.getColor(context, R.color.error)

            adapter.onBindViewHolder(firstHolder, 0)
            adapter.onBindViewHolder(secondHolder, 1)
            assertEquals(normal, secondStart.currentTextColor)

            entries[0].endTime = 2000
            adapter.onBindViewHolder(firstHolder, 0, mutableListOf(SubtitleAdapter.PAYLOAD_TIME_CONFLICT))
            adapter.onBindViewHolder(
                secondHolder, 1,
                mutableListOf(
                    SubtitleAdapter.PAYLOAD_PLAYING,
                    SubtitleAdapter.PAYLOAD_SELECTION,
                    SubtitleAdapter.PAYLOAD_TIME_CONFLICT
                )
            )
            assertEquals(error, firstEnd.currentTextColor)
            assertEquals(error, secondStart.currentTextColor)

            entries[0].endTime = 1000
            adapter.onBindViewHolder(secondHolder, 1, mutableListOf(SubtitleAdapter.PAYLOAD_TIME_CONFLICT))
            assertEquals(normal, secondStart.currentTextColor)

            // The rows have passed each other in time. The signed gap is still negative.
            entries[0].startTime = 5000
            entries[0].endTime = 6000
            adapter.onBindViewHolder(firstHolder, 0, mutableListOf(SubtitleAdapter.PAYLOAD_TIME_CONFLICT))
            adapter.onBindViewHolder(secondHolder, 1, mutableListOf(SubtitleAdapter.PAYLOAD_TIME_CONFLICT))
            assertEquals(error, firstEnd.currentTextColor)
            assertEquals(error, secondStart.currentTextColor)
        }
    }
}
