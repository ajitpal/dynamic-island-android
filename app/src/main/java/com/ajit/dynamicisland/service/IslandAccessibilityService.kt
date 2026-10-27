package com.ajit.dynamicisland.service

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.content.res.Configuration
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import com.ajit.dynamicisland.core.IslandRepository
import com.ajit.dynamicisland.core.IslandTools
import com.ajit.dynamicisland.core.Prefs
import com.ajit.dynamicisland.core.SystemEventMonitor
import com.ajit.dynamicisland.ui.IslandOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Hosts the island window. An accessibility overlay is the only way for a normal app to draw
 * on top of the status bar / lock screen and to know which app is in the foreground.
 * We only listen to window-state changes; no screen content is read.
 */
class IslandAccessibilityService : AccessibilityService() {

    private lateinit var prefs: Prefs
    private var overlay: IslandOverlay? = null
    private var monitor: SystemEventMonitor? = null
    private var scope: CoroutineScope? = null
    private val ignoredPackages = mutableSetOf("com.android.systemui")

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != Prefs.NOTE) overlay?.onSettingsChanged()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        prefs = Prefs(this)
        try {
            getSystemService(InputMethodManager::class.java)?.inputMethodList?.forEach { ignoredPackages += it.packageName }
        } catch (_: Exception) {}

        overlay = IslandOverlay(this, prefs) { performGlobalAction(it) }.also { it.attach() }
        monitor = SystemEventMonitor(this, prefs).also { it.start() }
        IslandTools.restore(this)
        com.ajit.dynamicisland.core.modules.ModuleRegistry.start(this)

        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { s ->
            s.launch { IslandRepository.activities.collect { overlay?.refresh() } }
            s.launch { IslandRepository.peeks.collect { overlay?.onPeek(it) } }
            s.launch { com.ajit.dynamicisland.core.inbox.InboxStore.version.collect { overlay?.onInboxChanged() } }
            s.launch { com.ajit.dynamicisland.core.modules.ModuleRegistry.tools.collect { overlay?.onSettingsChanged() } }
            s.launch {
                combine(IslandRepository.cameraInUse, IslandRepository.micInUse, IslandRepository.torchOn) { a, b, c -> Triple(a, b, c) }
                    .collect { overlay?.refresh() }
            }
        }
        prefs.sp.registerOnSharedPreferenceChangeListener(prefListener)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg in ignoredPackages) return
        overlay?.setForeground(pkg)
    }

    override fun onInterrupt() {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.onSettingsChanged()
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        running = false
        try { prefs.sp.unregisterOnSharedPreferenceChangeListener(prefListener) } catch (_: Exception) {}
        scope?.cancel(); scope = null
        com.ajit.dynamicisland.core.modules.ModuleRegistry.stop()
        monitor?.stop(); monitor = null
        overlay?.detach(); overlay = null
    }

    companion object {
        @Volatile var running = false
            private set
    }
}
