package com.subtitleedit.mpv

import android.content.Context
import android.view.Surface

internal object MPVLib {
    init {
        System.loadLibrary("mpv")
        System.loadLibrary("subtitleedit_mpv")
    }

    external fun create(appContext: Context): Boolean
    external fun init(): Int
    external fun destroy()
    external fun attachSurface(surface: Surface)
    external fun detachSurface()
    external fun command(command: Array<out String>)
    external fun setOptionString(name: String, value: String): Int
    external fun getPropertyInt(property: String): Int?
    external fun setPropertyInt(property: String, value: Int)
    external fun getPropertyDouble(property: String): Double?
    external fun setPropertyDouble(property: String, value: Double)
    external fun getPropertyBoolean(property: String): Boolean?
    external fun setPropertyBoolean(property: String, value: Boolean)
    external fun getPropertyString(property: String): String?
    external fun setPropertyString(property: String, value: String)
    external fun observeProperty(property: String, format: Int)

    private val observers = mutableListOf<EventObserver>()
    private val logObservers = mutableListOf<LogObserver>()

    @JvmStatic
    fun addObserver(observer: EventObserver) = synchronized(observers) {
        observers.add(observer)
    }

    @JvmStatic
    fun removeObserver(observer: EventObserver) = synchronized(observers) {
        observers.remove(observer)
    }

    @JvmStatic
    fun eventProperty(property: String, value: Long) = synchronized(observers) {
        observers.toList().forEach { it.eventProperty(property, value) }
    }

    @JvmStatic
    fun eventProperty(property: String, value: Boolean) = synchronized(observers) {
        observers.toList().forEach { it.eventProperty(property, value) }
    }

    @JvmStatic
    fun eventProperty(property: String, value: Double) = synchronized(observers) {
        observers.toList().forEach { it.eventProperty(property, value) }
    }

    @JvmStatic
    fun eventProperty(property: String, value: String) = synchronized(observers) {
        observers.toList().forEach { it.eventProperty(property, value) }
    }

    @JvmStatic
    fun eventProperty(property: String) = synchronized(observers) {
        observers.toList().forEach { it.eventProperty(property) }
    }

    @JvmStatic
    fun event(eventId: Int) = synchronized(observers) {
        observers.toList().forEach { it.event(eventId) }
    }

    @JvmStatic
    fun endFileError(error: Int, message: String) = synchronized(observers) {
        observers.toList().forEach { it.endFileError(error, message) }
    }

    @JvmStatic
    fun addLogObserver(observer: LogObserver) = synchronized(logObservers) {
        logObservers.add(observer)
    }

    @JvmStatic
    fun removeLogObserver(observer: LogObserver) = synchronized(logObservers) {
        logObservers.remove(observer)
    }

    @JvmStatic
    fun logMessage(prefix: String, level: Int, text: String) = synchronized(logObservers) {
        logObservers.toList().forEach { it.logMessage(prefix, level, text) }
    }

    internal interface EventObserver {
        fun eventProperty(property: String) = Unit
        fun eventProperty(property: String, value: Long) = Unit
        fun eventProperty(property: String, value: Boolean) = Unit
        fun eventProperty(property: String, value: String) = Unit
        fun eventProperty(property: String, value: Double) = Unit
        fun event(eventId: Int) = Unit
        fun endFileError(error: Int, message: String) = Unit
    }

    internal interface LogObserver {
        fun logMessage(prefix: String, level: Int, text: String)
    }

    internal object MpvFormat {
        const val NONE = 0
        const val STRING = 1
        const val FLAG = 3
        const val INT64 = 4
        const val DOUBLE = 5
    }

    internal object MpvEvent {
        const val SHUTDOWN = 1
        const val END_FILE = 7
        const val FILE_LOADED = 8
        const val SEEK = 20
        const val PLAYBACK_RESTART = 21
    }
}
