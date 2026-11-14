package com.subtitleedit.player

import android.app.AlarmManager
import android.content.Context
import android.os.Handler
import android.os.Looper

/** Wakes a live player session for its deadline even when the screen is off. */
internal class PlayerTimerAlarm(context: Context, private val onDeadline: () -> Unit) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var scheduledAt: Long? = null
    private val listener = AlarmManager.OnAlarmListener {
        scheduledAt = null
        onDeadline()
    }

    fun scheduleAt(deadlineMs: Long?) {
        if (scheduledAt == deadlineMs) return
        cancel()
        if (deadlineMs == null) return
        scheduledAt = deadlineMs
        // In-process listener alarms do not require the exact-alarm special permission.
        alarms.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadlineMs, "player-timer", listener, handler)
    }

    fun cancel() {
        alarms.cancel(listener)
        scheduledAt = null
    }
}
