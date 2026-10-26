package com.ajit.dynamicisland.core

import android.app.NotificationManager
import android.bluetooth.BluetoothDevice
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.provider.Settings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.ajit.dynamicisland.R

/**
 * iPhone-style system "glimpses": charging, low battery, silent/vibrate, bluetooth & wired
 * headphones, airplane mode, flashlight, and camera / microphone privacy indicators.
 */
class SystemEventMonitor(private val ctx: Context, private val prefs: Prefs) {
    private val handler = Handler(Looper.getMainLooper())
    private val cameraManager = ctx.getSystemService(CameraManager::class.java)
    private val audioManager = ctx.getSystemService(AudioManager::class.java)
    private var lastRinger = -1
    private var lowWarned = false
    private val camerasInUse = mutableSetOf<String>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_POWER_CONNECTED -> charging(true)
                Intent.ACTION_POWER_DISCONNECTED -> charging(false)
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = levelOf(i)
                    val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                    if (level in 1..20 && !plugged && !lowWarned) {
                        lowWarned = true
                        show("Low battery", "$level% remaining", "$level%", R.drawable.ic_battery, Colors.RED, 3500)
                    }
                    if (level > 25 || plugged) lowWarned = false
                }
                AudioManager.RINGER_MODE_CHANGED_ACTION -> {
                    val mode = i.getIntExtra(AudioManager.EXTRA_RINGER_MODE, -1)
                    if (lastRinger != -1 && mode != lastRinger) when (mode) {
                        AudioManager.RINGER_MODE_SILENT -> show("Silent mode", "Calls and alerts are silenced", "Silent", R.drawable.ic_bell_off, Colors.RED)
                        AudioManager.RINGER_MODE_VIBRATE -> show("Vibrate", "Ringer set to vibrate", "Vibrate", R.drawable.ic_bell_off, Colors.ORANGE)
                        AudioManager.RINGER_MODE_NORMAL -> show("Ring", "Ringer is on", "Ring", R.drawable.ic_bell, Colors.GRAY)
                    }
                    lastRinger = mode
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    @Suppress("DEPRECATION")
                    val dev = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) as? BluetoothDevice
                    val name = try { dev?.name } catch (e: SecurityException) { null }
                    lastBtName = name; lastBtAt = System.currentTimeMillis()
                    val level = btBattery(dev)
                    showBt(name, level)
                }
                ACTION_BT_BATTERY -> {
                    // Earbuds often report battery a moment after connecting: update the glimpse if it's recent.
                    val level = i.getIntExtra(EXTRA_BT_BATTERY, -1)
                    if (level >= 0 && System.currentTimeMillis() - lastBtAt < 15_000) showBt(lastBtName, level)
                }
                Intent.ACTION_USER_PRESENT -> if (prefs.unlockAnim) {
                    show("Unlocked", "Welcome back", "", R.drawable.ic_lock_open, Colors.GREEN, 1300)
                }
                NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED -> {
                    val nm = ctx.getSystemService(NotificationManager::class.java)
                    val on = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
                    show("Do Not Disturb", if (on) "On – notifications are silenced" else "Off", if (on) "On" else "Off", R.drawable.ic_moon, if (on) Colors.PURPLE else Colors.GRAY)
                }
                ACTION_HOTSPOT -> hotspot(i.getIntExtra("wifi_state", 0) == 13)
                ACTION_NFC -> if (prefs.connectivity) {
                    when (i.getIntExtra(EXTRA_NFC_STATE, 0)) {
                        3 -> show("NFC", "On – ready to tap & pay", "On", R.drawable.ic_nfc, Colors.BLUE)
                        1 -> show("NFC", "Off", "Off", R.drawable.ic_nfc, Colors.GRAY)
                    }
                }
                Intent.ACTION_HEADSET_PLUG -> if (!isInitialStickyBroadcast && i.getIntExtra("state", 0) == 1) {
                    show("Headphones", "Wired headphones connected", "Wired", R.drawable.ic_headphones, Colors.BLUE)
                }
                Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                    val on = i.getBooleanExtra("state", false)
                    show("Airplane mode", if (on) "On" else "Off", if (on) "On" else "Off", R.drawable.ic_airplane, if (on) Colors.ORANGE else Colors.GRAY)
                }
            }
        }
    }

    private var lastBtName: String? = null
    private var lastBtAt = 0L
    private var offline = false
    private val cm = ctx.getSystemService(ConnectivityManager::class.java)

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { handler.post { online(true) } }
        override fun onLost(network: Network) {
            // Wait a moment: switching Wi-Fi → mobile data briefly loses the network.
            handler.postDelayed({
                val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
                if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != true) online(false)
            }, 2500)
        }
    }

    private fun online(ok: Boolean) {
        if (!prefs.connectivity) return
        val airplane = Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        if (!ok && !offline && !airplane) { offline = true; show("No internet", "You're offline", "Offline", R.drawable.ic_wifi_off, Colors.RED, 3500) }
        if (ok && offline) { offline = false; show("Back online", "Internet connection restored", "Online", R.drawable.ic_wifi, Colors.GREEN, 2200) }
    }

    private fun hotspot(on: Boolean) {
        if (on && prefs.connectivity) {
            IslandRepository.upsert(
                IslandActivity(
                    id = HOTSPOT_ID, kind = Kind.TOOL, packageName = ctx.packageName,
                    title = "Personal hotspot", text = "Sharing your mobile data",
                    icon = ContextCompat.getDrawable(ctx, R.drawable.ic_hotspot), tintIcon = true,
                    accent = Colors.GREEN, compactText = "Hotspot",
                    actions = listOf(IslandAction("Settings", onClick = {
                        try { ctx.startActivity(android.content.Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                    })),
                    contentHash = 1,
                )
            )
        } else IslandRepository.remove(HOTSPOT_ID)
    }

    /** Hidden-but-stable API; works on most phones, returns -1 when unavailable. */
    private fun btBattery(dev: BluetoothDevice?): Int = try {
        (dev?.javaClass?.getMethod("getBatteryLevel")?.invoke(dev) as? Int) ?: -1
    } catch (e: Exception) { -1 }

    private fun showBt(name: String?, level: Int) {
        val n = name ?: "Bluetooth device"
        if (level in 0..100) show(n, "Connected · battery $level%", "$level%", R.drawable.ic_headphones, if (level <= 20) Colors.RED else Colors.GREEN, 3200, level / 100f)
        else show(n, "Connected", "Connected", R.drawable.ic_bluetooth, Colors.BLUE)
    }

    private val cameraCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraUnavailable(cameraId: String) { camerasInUse += cameraId; updateCamera() }
        override fun onCameraAvailable(cameraId: String) { camerasInUse -= cameraId; updateCamera() }
    }

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            IslandRepository.torchOn.value = enabled
            if (enabled) {
                IslandRepository.upsert(
                    IslandActivity(
                        id = IslandTools.TORCH_ID, kind = Kind.TOOL, packageName = ctx.packageName,
                        title = "Flashlight", text = "Tap to turn off",
                        icon = ContextCompat.getDrawable(ctx, R.drawable.ic_torch), tintIcon = true,
                        accent = Colors.YELLOW, compactText = "On",
                        onTap = { IslandTools.toggleTorch(ctx) },
                        actions = listOf(IslandAction("Turn off", style = ActionStyle.NEGATIVE, onClick = { IslandTools.toggleTorch(ctx) })),
                        contentHash = 1,
                    )
                )
            } else {
                IslandRepository.remove(IslandTools.TORCH_ID)
            }
            updateCamera()
        }
    }

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
            IslandRepository.micInUse.value = configs.orEmpty().any { !it.isClientSilenced }
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(Intent.ACTION_HEADSET_PLUG)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            addAction(ACTION_BT_BATTERY)
            addAction(ACTION_HOTSPOT)
            addAction(ACTION_NFC)
        }
        lastRinger = audioManager.ringerMode
        ContextCompat.registerReceiver(ctx, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        try { cm?.registerDefaultNetworkCallback(netCallback) } catch (_: Exception) {}
        try { cameraManager.registerAvailabilityCallback(cameraCallback, handler) } catch (_: Exception) {}
        try { cameraManager.registerTorchCallback(torchCallback, handler) } catch (_: Exception) {}
        try {
            audioManager.registerAudioRecordingCallback(recordingCallback, handler)
            IslandRepository.micInUse.value = audioManager.activeRecordingConfigurations.any { !it.isClientSilenced }
        } catch (_: Exception) {}
    }

    fun stop() {
        try { ctx.unregisterReceiver(receiver) } catch (_: Exception) {}
        try { cm?.unregisterNetworkCallback(netCallback) } catch (_: Exception) {}
        try { cameraManager.unregisterAvailabilityCallback(cameraCallback) } catch (_: Exception) {}
        try { cameraManager.unregisterTorchCallback(torchCallback) } catch (_: Exception) {}
        try { audioManager.unregisterAudioRecordingCallback(recordingCallback) } catch (_: Exception) {}
    }

    private fun updateCamera() {
        // Turning on the torch makes the camera "unavailable" on many phones; ignore that case.
        IslandRepository.cameraInUse.value = camerasInUse.isNotEmpty() && !IslandRepository.torchOn.value
    }

    private fun levelOf(i: Intent): Int {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return if (level < 0 || scale <= 0) -1 else level * 100 / scale
    }

    private fun charging(connected: Boolean) {
        val sticky = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = sticky?.let { levelOf(it) } ?: -1
        val pct = if (level >= 0) "$level%" else ""
        if (connected) show("Charging", "Battery $pct", pct, R.drawable.ic_bolt, Colors.GREEN, 3000, if (level >= 0) level / 100f else -1f)
        else show("Not charging", "Battery $pct", pct, R.drawable.ic_battery, Colors.GRAY, 2000)
    }

    private fun show(title: String, text: String, compact: String, iconRes: Int, accent: Int, ms: Long = 2800, progress: Float = -1f) {
        if (!prefs.fSystem) return
        val now = System.currentTimeMillis()
        IslandRepository.upsert(
            IslandActivity(
                id = "sys", kind = Kind.SYSTEM, packageName = ctx.packageName, title = title, text = text,
                icon = ContextCompat.getDrawable(ctx, iconRes), tintIcon = true, accent = accent,
                compactText = compact, progress = progress, expiresAt = now + ms, contentHash = now.toInt(),
            )
        )
    }

    companion object {
        const val HOTSPOT_ID = "sys:hotspot"
        // Not in the public SDK but broadcast by the system on Android 12-16.
        const val ACTION_BT_BATTERY = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        const val EXTRA_BT_BATTERY = "android.bluetooth.device.extra.BATTERY_LEVEL"
        const val ACTION_HOTSPOT = "android.net.wifi.WIFI_AP_STATE_CHANGED"
        const val ACTION_NFC = "android.nfc.action.ADAPTER_STATE_CHANGED"
        const val EXTRA_NFC_STATE = "android.nfc.extra.ADAPTER_STATE"
    }
}
