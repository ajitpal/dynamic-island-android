package com.ajit.dynamicisland.service

import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.ajit.dynamicisland.core.IslandRepository
import com.ajit.dynamicisland.core.Kind
import com.ajit.dynamicisland.core.MediaTracker
import com.ajit.dynamicisland.core.NotificationParser
import com.ajit.dynamicisland.core.Prefs

/** Feeds notifications (messages, calls, timers, navigation, progress) and media sessions to the island. */
class IslandNotificationListener : NotificationListenerService() {

    private lateinit var prefs: Prefs
    private var media: MediaTracker? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        media = MediaTracker(this, ComponentName(this, IslandNotificationListener::class.java), prefs).also { it.start() }
        // Pick up things already running (a timer, a download, navigation) but don't re-announce old messages.
        try {
            activeNotifications?.forEach { sbn ->
                NotificationParser.parse(this, sbn, prefs)?.let {
                    if (it.kind != Kind.MESSAGE && it.kind != Kind.GENERIC) IslandRepository.upsert(it.copy(peek = false))
                }
            }
        } catch (_: Exception) {}
    }

    override fun onListenerDisconnected() {
        connected = false
        media?.stop(); media = null
        IslandRepository.removeWhere { it.id.startsWith("n:") }
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName != packageName) prefs.markSeen(sbn.packageName)
        var a = try { NotificationParser.parse(this, sbn, prefs) } catch (e: Exception) { null }
        // Silent / low-importance notifications still show in the island, but don't pop it open.
        if (a != null && a.peek && a.kind == com.ajit.dynamicisland.core.Kind.GENERIC) {
            val r = Ranking()
            if (currentRanking?.getRanking(sbn.key, r) == true && r.importance <= android.app.NotificationManager.IMPORTANCE_LOW) {
                a = a.copy(peek = false, expiresAt = System.currentTimeMillis() + 4000)
            }
        }
        com.ajit.dynamicisland.core.IslandDebug.lastNotification =
            "${sbn.packageName} cat=${sbn.notification?.category} → ${a?.kind ?: "ignored"}" 
        if (a != null) IslandRepository.upsert(a) else IslandRepository.remove("n:" + sbn.key)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        IslandRepository.remove("n:" + sbn.key)
    }

    companion object {
        @Volatile var connected = false
            private set
    }
}
