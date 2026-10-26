package com.ajit.dynamicisland.core

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import java.util.Objects

/**
 * Watches every active MediaSession (Spotify, YouTube Music, JioSaavn, podcasts, video apps)
 * and publishes the one that's playing as the "now playing" activity.
 * Requires notification-listener access (that's what lets us read other apps' sessions).
 */
class MediaTracker(
    private val ctx: Context,
    private val component: ComponentName,
    private val prefs: Prefs,
) {
    companion object { const val ID = "media" }

    private val msm = ctx.getSystemService(MediaSessionManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val controllers = LinkedHashMap<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private var lastPlaying: MediaSession.Token? = null

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { update(it.orEmpty()) }

    fun start() {
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
            update(msm.getActiveSessions(component))
        } catch (e: SecurityException) {
            // Listener not enabled yet.
        }
    }

    fun stop() {
        try { msm.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Exception) {}
        controllers.values.forEach { (c, cb) -> c.unregisterCallback(cb) }
        controllers.clear()
        IslandRepository.remove(ID)
    }

    private fun update(list: List<MediaController>) {
        val tokens = list.map { it.sessionToken }.toSet()
        controllers.keys.filter { it !in tokens }.forEach { t ->
            controllers.remove(t)?.let { (c, cb) -> c.unregisterCallback(cb) }
        }
        list.forEach { c ->
            if (!controllers.containsKey(c.sessionToken)) {
                val cb = object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: PlaybackState?) { publish() }
                    override fun onMetadataChanged(metadata: MediaMetadata?) { publish() }
                    override fun onSessionDestroyed() {
                        handler.post {
                            controllers.remove(c.sessionToken)?.let { (cc, cbb) -> cc.unregisterCallback(cbb) }
                            publish()
                        }
                    }
                }
                c.registerCallback(cb, handler)
                controllers[c.sessionToken] = c to cb
            }
        }
        publish()
    }

    private fun publish() {
        val all = controllers.values.map { it.first }
        val playing = all.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        if (playing != null) lastPlaying = playing.sessionToken
        val c = playing ?: all.firstOrNull { it.sessionToken == lastPlaying }
        val meta = c?.metadata
        if (c == null || meta == null || !prefs.fMedia) {
            IslandRepository.remove(ID); return
        }
        val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        if (title.isNullOrBlank()) { IslandRepository.remove(ID); return }
        val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        val art = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val isPlaying = playing != null
        val pkg = c.packageName
        IslandRepository.upsert(
            IslandActivity(
                id = ID, kind = Kind.MEDIA, packageName = pkg, title = title, text = artist,
                icon = NotificationParser.appIcon(ctx, pkg), image = art,
                accent = Colors.vivid(art) ?: Colors.PINK,
                isPlaying = isPlaying, media = c, contentIntent = c.sessionActivity,
                appLabel = NotificationParser.appLabel(ctx, pkg),
                // Paused media lingers for a minute (like iOS), then disappears.
                expiresAt = if (isPlaying) 0L else System.currentTimeMillis() + 60_000,
                priority = if (isPlaying) Kind.MEDIA.basePriority else 15,
                contentHash = Objects.hash(title, artist, isPlaying, pkg),
            )
        )
    }
}
