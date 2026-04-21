package com.kargathra.timegrapher.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log

/**
 * Monitors audio input device changes and enforces policy:
 *
 *  - REQ-9.3: Built-in microphone is ALWAYS the default input. USB-C input
 *    is only used when the user explicitly opts in (via the UI toggle).
 *
 *  - REQ-1.5 / REQ-9.2: Bluetooth audio sources are IGNORED entirely.
 *    They are never offered to the user, never routed to, never mentioned.
 *    Bluetooth codecs introduce 100-300ms of latency and destroy the sharp
 *    transient signals required for sub-millisecond watch-beat timing.
 *    There is no fallback behaviour, no error dialog — Bluetooth devices
 *    are simply invisible to this app.
 *
 *  - REQ-9.4: When USB-C availability changes (plug/unplug), the UI is
 *    notified so it can show/hide the toggle. The app does NOT auto-switch.
 *
 * Register via [register] when measurement starts; [unregister] on stop.
 */
class AudioDeviceMonitor(
    context: Context,
    private val engineJNI: AudioEngineJNI,
    /** Called when a USB-C audio input becomes available. UI can now offer the toggle. */
    private val onUsbCAvailable: (deviceId: Int) -> Unit = {},
    /** Called when the USB-C audio input is disconnected. UI should hide the toggle. */
    private val onUsbCUnavailable: () -> Unit = {}
) {
    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val callback = object : AudioDeviceCallback() {

        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            for (device in addedDevices) {
                if (!device.isSource) continue
                Log.d(TAG, "Device added: id=${device.id} type=${device.type} name=${device.productName}")

                // Bluetooth input devices are silently ignored — they are never
                // candidates for measurement under any circumstance (REQ-1.5).
                if (isBluetoothDevice(device)) {
                    Log.d(TAG, "Ignoring Bluetooth input device (REQ-1.5 policy)")
                    continue
                }

                // USB-C device plugged in: notify the UI that the user now has
                // the OPTION to switch to it. Do NOT auto-switch — the default
                // input is always the built-in mic unless the user chooses
                // otherwise (REQ-9.3).
                if (isUsbCDevice(device)) {
                    Log.i(TAG, "USB-C audio device available: id=${device.id} (notifying UI)")
                    onUsbCAvailable(device.id)
                }
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            for (device in removedDevices) {
                if (!device.isSource) continue
                Log.d(TAG, "Device removed: id=${device.id} type=${device.type}")

                // Bluetooth removals are irrelevant — we never used it.
                if (isBluetoothDevice(device)) continue

                // USB-C removal: if we were actively routing through it, the
                // engine must fall back to the built-in mic immediately.
                // The AudioEngine's routing handler decides whether a switch
                // is needed based on whether this was the active device.
                if (isUsbCDevice(device)) {
                    Log.i(TAG, "USB-C audio device removed (id=${device.id})")
                    engineJNI.onDeviceRemoved(device.id)
                    onUsbCUnavailable()
                }
            }
        }
    }

    fun register() {
        audioManager.registerAudioDeviceCallback(callback, null)
        Log.d(TAG, "AudioDeviceMonitor registered")
    }

    fun unregister() {
        audioManager.unregisterAudioDeviceCallback(callback)
        Log.d(TAG, "AudioDeviceMonitor unregistered")
    }

    /**
     * Returns the input device ID to open.
     *
     * Policy (REQ-9.3):
     *   - DEFAULT: always return 0 (Oboe default = built-in bottom mic).
     *     This is the correct choice for the vast majority of sessions
     *     because the built-in Pixel mic is known, characterised, and
     *     predictable.
     *   - USB-C: only returned when the caller explicitly passes
     *     preferUsbC = true. This is triggered by a user-initiated UI
     *     action (e.g., tapping the USB-C toggle) or by an AudioDeviceCallback
     *     event after the user plugged a device in mid-session.
     *   - BLUETOOTH: NEVER considered, regardless of what the caller asks
     *     for. Bluetooth audio's codec latency and signal filtering make
     *     it unusable for sub-millisecond timing (REQ-1.5, REQ-9.2).
     */
    fun selectInputDevice(preferUsbC: Boolean = false): Int {
        if (preferUsbC) {
            val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            val usbC = inputs.firstOrNull { it.isSource && isUsbCDevice(it) }
            if (usbC != null) {
                Log.i(TAG, "Selecting USB-C device: id=${usbC.id} (user-requested)")
                return usbC.id
            }
            Log.w(TAG, "USB-C requested but no USB-C device present — falling back to built-in mic")
        }
        // Always default to built-in mic
        Log.i(TAG, "Selecting built-in microphone (default)")
        return 0
    }

    /**
     * Returns true if a USB-C audio input device is currently connected.
     * Used by the UI to show/hide the "switch to USB-C" toggle.
     */
    fun isUsbCAvailable(): Boolean {
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        return inputs.any { it.isSource && isUsbCDevice(it) }
    }

    companion object {
        private const val TAG = "AudioDeviceMonitor"

        // REQ-1.5: All Bluetooth source types
        private val BLUETOOTH_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_BROADCAST
        )

        // REQ-1.4: USB-C audio input types
        private val USBC_TYPES = setOf(
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY
        )

        fun isBluetoothDevice(device: AudioDeviceInfo): Boolean =
            device.type in BLUETOOTH_TYPES

        fun isUsbCDevice(device: AudioDeviceInfo): Boolean =
            device.type in USBC_TYPES
    }
}
