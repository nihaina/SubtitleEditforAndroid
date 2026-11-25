package com.subtitleedit.player

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Handler
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import java.util.UUID

internal enum class UsbRouteChange { ATTACHED, PERMISSION_RESULT, DETACHED }

/** Used only by the audio-mode player. All callbacks run on its mpv access thread. */
internal class PlayerUsbAudio(
    private val context: Context,
    private val handler: Handler,
    private val accessLock: Any,
    private val onRouteChanged: (UsbRouteChange) -> Unit,
) : AutoCloseable {
    private val manager = context.getSystemService(UsbManager::class.java)
    private val permissionAction = "${context.packageName}.player.USB_PERMISSION.${UUID.randomUUID()}"
    private var registered = false
    private var device: UsbDevice? = null
    private var connection: UsbDeviceConnection? = null
    private var permissionIntent: PendingIntent? = null
    private var permissionRequest = 0
    private var needsOpenRetry = false

    val descriptor get() = connection?.fileDescriptor?.takeIf { it >= 0 }
    val awaitingPermission get() = permissionIntent != null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent): Unit = synchronized(accessLock) {
            if (!registered) return
            val changed = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            when (intent.action) {
                permissionAction -> {
                    if (!awaitingPermission || intent.getIntExtra(REQUEST_ID, -1) != permissionRequest) return
                    val target = device ?: return
                    if (changed != null && changed != target) return
                    cancelPermission()
                    if (manager?.hasPermission(target) == true) open(target)
                    else PlayerUsbDiagnostics.record("USB permission denied: ${target.usbLabel()}", failed = true)
                    onRouteChanged(UsbRouteChange.PERMISSION_RESULT)
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    if (changed != null && descriptor == null && !awaitingPermission && changed.hasAudioOutput()) {
                        select(changed)
                        onRouteChanged(UsbRouteChange.ATTACHED)
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> if (changed != null && changed == device) {
                    val lost = descriptor != null || awaitingPermission
                    cancelPermission()
                    val previous = connection
                    connection = null
                    device = null
                    needsOpenRetry = false
                    try {
                        PlayerUsbDiagnostics.record("DAC detached: ${changed.usbLabel()}")
                        if (lost) onRouteChanged(UsbRouteChange.DETACHED)
                    } finally {
                        // Stop supplying the old FD to mpv before dropping the framework connection.
                        previous?.close()
                    }
                }
            }
        }
    }

    fun start() {
        if (manager == null) return
        if (registered) {
            if (needsOpenRetry) device?.let(::select)
            return
        }
        try {
            ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
                addAction(permissionAction)
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }, null, handler, ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
            PlayerUsbDeviceMonitor.get(context).let { monitor ->
                monitor.refresh()
                monitor.devices.value.firstOrNull()?.let(::select)
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not prepare USB audio", error)
            close()
        }
    }

    private fun select(target: UsbDevice) {
        device = target
        needsOpenRetry = false
        val usb = manager ?: return
        if (usb.hasPermission(target)) {
            open(target)
            return
        }
        val request = ++permissionRequest
        val intent = Intent(permissionAction).setPackage(context.packageName).putExtra(REQUEST_ID, request)
        val pending = PendingIntent.getBroadcast(context, request, intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE)
        permissionIntent = pending
        PlayerUsbDiagnostics.record("Requesting USB permission: ${target.usbLabel()}")
        try {
            usb.requestPermission(target, pending)
        } catch (error: RuntimeException) {
            cancelPermission()
            Log.w(TAG, "Could not request USB permission", error)
            PlayerUsbDiagnostics.record("USB permission request failed: ${error.javaClass.simpleName}", failed = true)
        }
    }

    private fun open(target: UsbDevice) {
        connection = try {
            manager?.openDevice(target)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not open USB audio device", error)
            null
        }
        val opened = descriptor != null
        PlayerUsbDiagnostics.record(
            if (opened) "DAC opened: ${target.usbLabel()}" else "DAC open failed: ${target.usbLabel()}",
            failed = !opened,
        )
    }

    fun releaseForRetry() {
        connection?.close()
        connection = null
        needsOpenRetry = device != null
    }

    override fun close() {
        if (registered) {
            registered = false
            context.unregisterReceiver(receiver)
        }
        cancelPermission()
        releaseDevice()
    }

    private fun cancelPermission() {
        permissionIntent?.cancel()
        permissionIntent = null
    }

    private fun releaseDevice() {
        connection?.close()
        connection = null
        device = null
        needsOpenRetry = false
    }

    private companion object {
        const val TAG = "PlayerUsbAudio"
        const val REQUEST_ID = "permission_request"
    }
}
