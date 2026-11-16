package com.subtitleedit.player

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.subtitleedit.EditorActivity

/** Wait for the outgoing exclusive playback Activity to release the process-wide mpv. */
internal object PlayerOutputHandoff : Application.ActivityLifecycleCallbacks {
    private val owners = mutableSetOf<Activity>()
    private val listeners = mutableSetOf<() -> Unit>()
    val hasExclusiveOwner: Boolean get() = owners.isNotEmpty()

    fun install(application: Application) { application.registerActivityLifecycleCallbacks(this) }
    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (activity is EditorActivity || activity is PlayerActivity) {
            owners += activity
            listeners.toList().forEach { it() }
        }
    }

    override fun onActivityDestroyed(activity: Activity) {
        // These Activities release playback before invoking super.onDestroy().
        if (owners.remove(activity)) listeners.toList().forEach { it() }
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
