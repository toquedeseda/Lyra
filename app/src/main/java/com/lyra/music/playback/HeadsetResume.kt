package com.lyra.music.playback

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import androidx.media3.common.util.UnstableApi
import com.lyra.music.LyraApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Seguir al conectar auriculares": si la música se paró porque se desconectaron
 * los cascos (o el Bluetooth del coche), al volver a conectarlos sigue donde iba.
 * Si la pausaste tú, no hace nada.
 */
object HeadsetResume {
    private const val PREFS = "headset_resume"
    private const val KEY_PAUSED_AT = "paused_by_disconnect_at"

    /** Salidas que cuentan como auriculares: cable, USB y Bluetooth (también el del coche). */
    private val HEADPHONE_TYPES = buildSet {
        add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
        add(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
        add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        add(AudioDeviceInfo.TYPE_USB_HEADSET)
        if (Build.VERSION.SDK_INT >= 31) {
            add(AudioDeviceInfo.TYPE_BLE_HEADSET)
            add(AudioDeviceInfo.TYPE_BLE_SPEAKER)
        }
    }

    fun isHeadphones(device: AudioDeviceInfo) = device.isSink && device.type in HEADPHONE_TYPES

    fun markPausedByDisconnect(context: Context) = prefs(context).edit { putLong(KEY_PAUSED_AT, System.currentTimeMillis()) }

    fun clear(context: Context) {
        if (pending(context)) prefs(context).edit { remove(KEY_PAUSED_AT) }
    }

    fun pending(context: Context): Boolean = prefs(context).getLong(KEY_PAUSED_AT, 0L) > 0L

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Mientras el servicio de música vive: avisa cuando se conectan unos auriculares nuevos. */
class HeadphonesWatcher(context: Context, private val onConnected: () -> Unit) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val known = mutableSetOf<Int>()

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            // Al registrarse, Android repite los que ya estaban conectados: esos no cuentan.
            val fresh = addedDevices.filter { known.add(it.id) }
            if (fresh.any(HeadsetResume::isHeadphones)) onConnected()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            removedDevices.forEach { known.remove(it.id) }
        }
    }

    fun start() {
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).forEach { known.add(it.id) }
        audio.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
    }

    fun stop() = audio.unregisterAudioDeviceCallback(callback)
}

/**
 * Con la app cerrada: al conectarse unos cascos Bluetooth, retoma lo que se paró al
 * quitarlos. (Los de cable solo se detectan con la app abierta; Android no avisa si no.)
 */
@UnstableApi
class HeadsetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED) return
        if (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) != BluetoothProfile.STATE_CONNECTED) return
        if (!HeadsetResume.pending(context)) return
        val container = (context.applicationContext as? LyraApp)?.container ?: return
        val result = goAsync()
        container.scope.launch {
            try {
                if (container.settings.loaded().resumeOnConnect) {
                    delay(2_500) // Android tarda un momento en pasar el audio a los cascos.
                    if (HeadsetResume.pending(context)) {
                        withTimeoutOrNull(6_000) { container.player.resumeAfterReconnect() }
                    }
                }
            } finally {
                result.finish()
            }
        }
    }
}
