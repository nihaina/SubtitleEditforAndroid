package com.subtitleedit.player

import android.content.Intent
import android.os.Bundle
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerQueueSourceStateTest {
    @Test fun savedSourceSurvivesParcelForEveryBrowsingKind() {
        PlayerQueueSourceKind.entries.forEach { kind ->
            val source = PlayerQueueSource(kind, "原始分组", listOf("/storage/music/", "/storage/video/"))
            val state = Bundle().apply { putPlayerQueueSource(source) }
            val parcel = Parcel.obtain()
            try {
                state.writeToParcel(parcel, 0)
                parcel.setDataPosition(0)
                assertEquals(source, Bundle.CREATOR.createFromParcel(parcel).getPlayerQueueSource())
            } finally {
                parcel.recycle()
            }
        }
    }

    @Test fun intentCarriesSourceAlongsideExistingPlaylistExtras() {
        val source = PlayerQueueSource(PlayerQueueSourceKind.PLAYLIST, "-1")
        val intent = Intent().apply {
            putStringArrayListExtra(PlayerActivity.EXTRA_PLAYLIST, arrayListOf("/music/one.flac"))
            putPlayerQueueSource(source)
        }
        assertEquals(source, intent.getPlayerQueueSource())
        assertEquals(listOf("/music/one.flac"), intent.getStringArrayListExtra(PlayerActivity.EXTRA_PLAYLIST))
    }

    @Test fun emptyMetadataGroupKeepsItsValidEmptyKey() {
        val source = PlayerQueueSource(PlayerQueueSourceKind.ALBUM, "")
        assertEquals(source, Bundle().apply { putPlayerQueueSource(source) }.getPlayerQueueSource())
    }

    @Test fun absentAndUnrecognizedSourcesAreIgnored() {
        assertNull((null as Bundle?).getPlayerQueueSource())
        assertNull(Bundle().getPlayerQueueSource())
        assertNull(Intent().getPlayerQueueSource())
        val state = Bundle().apply {
            putBundle("player_queue_source", Bundle().apply {
                putString("kind", "FUTURE_KIND")
                putString("key", "group")
            })
        }
        assertNull(state.getPlayerQueueSource())
    }

    @Test fun clearingSourceRemovesAnEarlierBrowsingContext() {
        val source = PlayerQueueSource(PlayerQueueSourceKind.DIRECTORY, "/music/album")
        val state = Bundle().apply { putPlayerQueueSource(source); putPlayerQueueSource(null) }
        val intent = Intent().apply { putPlayerQueueSource(source); putPlayerQueueSource(null) }
        assertNull(state.getPlayerQueueSource())
        assertNull(intent.getPlayerQueueSource())
    }
}
