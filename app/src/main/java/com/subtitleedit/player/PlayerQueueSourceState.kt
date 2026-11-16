package com.subtitleedit.player

import android.content.Intent
import android.os.Bundle

private const val QUEUE_SOURCE = "player_queue_source"

internal fun Bundle.putPlayerQueueSource(source: PlayerQueueSource?) {
    if (source == null) {
        remove(QUEUE_SOURCE)
        return
    }
    putBundle(QUEUE_SOURCE, Bundle().apply {
        putString("kind", source.kind.name)
        putString("key", source.key)
        putStringArrayList("roots", ArrayList(source.roots))
    })
}

internal fun Bundle?.getPlayerQueueSource(): PlayerQueueSource? {
    val source = this?.getBundle(QUEUE_SOURCE) ?: return null
    val kind = PlayerQueueSourceKind.entries.firstOrNull { it.name == source.getString("kind") }
        ?: return null
    val key = source.getString("key") ?: return null
    return PlayerQueueSource(kind, key, source.getStringArrayList("roots").orEmpty())
}

internal fun Intent.putPlayerQueueSource(source: PlayerQueueSource?) {
    removeExtra(QUEUE_SOURCE)
    if (source != null) putExtras(Bundle().apply { putPlayerQueueSource(source) })
}

internal fun Intent.getPlayerQueueSource(): PlayerQueueSource? = extras.getPlayerQueueSource()
