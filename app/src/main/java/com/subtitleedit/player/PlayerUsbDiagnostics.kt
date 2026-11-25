package com.subtitleedit.player

import com.subtitleedit.util.RuntimeLogManager
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal object PlayerUsbDiagnostics {
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines = _lines.asStateFlow()
    private val _active = MutableStateFlow(false)
    val active = _active.asStateFlow()
    private val _hardwareVolumeSupported = MutableStateFlow<Boolean?>(null)
    val hardwareVolumeSupported = _hardwareVolumeSupported.asStateFlow()

    fun setActive(active: Boolean) {
        if (!active) _hardwareVolumeSupported.value = null
        _active.value = active
    }

    fun setHardwareVolumeSupported(supported: Boolean?) {
        _hardwareVolumeSupported.value = supported
    }

    fun record(message: String, failed: Boolean = false) {
        val text = message.trim()
        if (text.isEmpty()) return
        val line = "${LocalTime.now().format(timeFormat)}  $text"
        _lines.update { (it + line).takeLast(80) }
        if (failed) RuntimeLogManager.e("UsbExclusive", text) else RuntimeLogManager.i("UsbExclusive", text)
    }
}
