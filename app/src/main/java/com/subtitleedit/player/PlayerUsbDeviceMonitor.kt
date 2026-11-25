package com.subtitleedit.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import java.util.Locale
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Watches devices even when the audio-mode player has no open track. */
internal class PlayerUsbDeviceMonitor private constructor(context: Context) {
    private val manager = context.getSystemService(UsbManager::class.java)
    private val _devices = MutableStateFlow<List<UsbDevice>>(emptyList())
    val devices = _devices.asStateFlow()
    private val _attachments = MutableSharedFlow<UsbDevice>(extraBufferCapacity = 8)
    val attachments = _attachments.asSharedFlow()

    init {
        if (manager != null) {
            ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    refresh()
                    if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED && device?.hasAudioOutput() == true) {
                        _attachments.tryEmit(device)
                    }
                }
            }, IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            refresh()
        }
    }

    fun refresh() {
        _devices.value = manager?.deviceList?.values.orEmpty().filter { it.hasAudioOutput() }.sortedBy { it.deviceName }
    }

    companion object {
        @Volatile private var instance: PlayerUsbDeviceMonitor? = null

        fun get(context: Context): PlayerUsbDeviceMonitor = instance ?: synchronized(this) {
            instance ?: PlayerUsbDeviceMonitor(context.applicationContext).also { instance = it }
        }
    }
}

internal fun UsbDevice.hasAudioOutput(): Boolean = (0 until interfaceCount).any { index ->
    val stream = getInterface(index)
    stream.interfaceClass == UsbConstants.USB_CLASS_AUDIO && stream.interfaceSubclass == 2 &&
        (0 until stream.endpointCount).any { endpointIndex ->
            val endpoint = stream.getEndpoint(endpointIndex)
            endpoint.direction == UsbConstants.USB_DIR_OUT && endpoint.type == UsbConstants.USB_ENDPOINT_XFER_ISOC
        }
}

internal fun UsbDevice.usbLabel(): String = productName?.takeIf(String::isNotBlank)
    ?: String.format(Locale.ROOT, "USB DAC %04X:%04X", vendorId, productId)
