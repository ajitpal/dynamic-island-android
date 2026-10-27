package com.ajit.dynamicisland.core.inbox

import android.app.Notification
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.ajit.dynamicisland.core.NotificationParser
import com.ajit.dynamicisland.core.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

data class InboxEntry(
    val id: Long,
    val key: String,
    val pkg: String,
    val app: String,
    val title: String,
    val text: String,
    val posted: Long,
    val read: Boolean,
    val category: String,
) {
    val otp: String? get() = Otp.extract("$title $text")
    val isPayment: Boolean get() = pkg in InboxStore.PAYMENT_APPS || InboxStore.PAYMENT_WORDS.containsMatchIn("$title $text")
    val isMessage: Boolean get() = category == "msg"
}

enum class InboxFilter(val label: String) { ALL("All"), UNREAD("Unread"), MESSAGES("Messages"), PAYMENTS("Payments"), OTP("OTPs") }

/**
 * Every notification the island sees, kept on-device until the user clears it (or it's older
 * than the retention period). Title/text are AES-GCM encrypted with a Keystore key.
 */
object InboxStore {
    val PAYMENT_APPS = setOf(
        "com.google.android.apps.walletnfcrel", "com.google.android.apps.nbu.paisa.user",
        "com.phonepe.app", "net.one97.paytm", "in.org.npci.upiapp", "com.dreamplug.androidapp",
    )
    val PAYMENT_WORDS = Regex("(?i)\\b(paid|payment|debited|credited|received ₹|sent ₹|rs\\.? ?[0-9]|₹ ?[0-9])")
    private const val MAX_ROWS = 2000

    private val exec = Executors.newSingleThreadExecutor()
    private val lastHashByKey = ConcurrentHashMap<String, Int>()
    /** Tap targets of notifications still alive (or moved into the island). Not persisted. */
    val liveIntents = ConcurrentHashMap<String, PendingIntent>()

    private val _version = MutableStateFlow(0)
    /** Bumps on every change, so screens can refresh. */
    val version: StateFlow<Int> = _version

    @Volatile private var helper: Helper? = null
    private fun db(ctx: Context): SQLiteDatabase =
        (helper ?: synchronized(this) { helper ?: Helper(ctx.applicationContext).also { helper = it } }).writableDatabase

    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx, "inbox.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE entries(id INTEGER PRIMARY KEY AUTOINCREMENT, nkey TEXT, pkg TEXT, app TEXT, " +
                    "title BLOB, body BLOB, posted INTEGER, read INTEGER DEFAULT 0, category TEXT, hash INTEGER)"
            )
            db.execSQL("CREATE INDEX idx_posted ON entries(posted)")
            db.execSQL("CREATE INDEX idx_key ON entries(nkey)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }

    private fun bump() { _version.value = _version.value + 1 }

    /** Called from the notification listener for every posted notification. */
    fun capture(ctx: Context, sbn: StatusBarNotification, prefs: Prefs) {
        if (!prefs.inboxEnabled) return
        val n = sbn.notification ?: return
        val pkg = sbn.packageName
        if (pkg == ctx.packageName || pkg == "android" || pkg == "com.android.systemui" || prefs.isBlocked(pkg)) return
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        if ((n.flags and Notification.FLAG_ONGOING_EVENT) != 0 || (n.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0) return
        val ex = n.extras ?: return
        if (ex.containsKey(Notification.EXTRA_MEDIA_SESSION)) return

        var title = (ex.getCharSequence(Notification.EXTRA_TITLE_BIG) ?: ex.getCharSequence(Notification.EXTRA_TITLE))?.toString() ?: ""
        var body = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
        var category = n.category ?: ""
        val style = try { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) } catch (e: Exception) { null }
        val last = style?.messages?.lastOrNull()
        if (style != null && last != null) {
            val sender = last.person?.name?.toString()
            title = (style.conversationTitle ?: sender ?: title).toString()
            body = if (style.isGroupConversation && sender != null) "$sender: ${last.text}" else last.text?.toString() ?: body
            category = "msg"
        } else if (category == Notification.CATEGORY_MESSAGE) category = "msg"
        if (title.isBlank() && body.isBlank()) return

        n.contentIntent?.let { liveIntents[sbn.key] = it }
        val hash = (title + "\u0000" + body).hashCode()
        if (lastHashByKey.put(sbn.key, hash) == hash) return // same notification re-posted: not a new entry
        val app = NotificationParser.appLabel(ctx, pkg)
        val posted = sbn.postTime
        val key = sbn.key
        exec.execute {
            try {
                val db = db(ctx)
                // Second line of defence against duplicates (e.g. after a restart).
                db.rawQuery("SELECT 1 FROM entries WHERE nkey=? AND hash=? LIMIT 1", arrayOf(key, hash.toString())).use { if (it.moveToFirst()) return@execute }
                db.insert("entries", null, ContentValues().apply {
                    put("nkey", key); put("pkg", pkg); put("app", app)
                    put("title", Crypto.encrypt(title)); put("body", Crypto.encrypt(body))
                    put("posted", posted); put("category", category); put("hash", hash)
                })
                prune(ctx, prefs)
                bump()
            } catch (_: Exception) {
            }
        }
    }

    private fun prune(ctx: Context, prefs: Prefs) {
        val db = db(ctx)
        val cutoff = System.currentTimeMillis() - prefs.inboxDays * 24L * 3600_000L
        db.delete("entries", "posted < ?", arrayOf(cutoff.toString()))
        db.execSQL("DELETE FROM entries WHERE id NOT IN (SELECT id FROM entries ORDER BY posted DESC LIMIT $MAX_ROWS)")
    }

    /** Blocking: call off the main thread for big lists (the island panel only asks for a few rows). */
    fun query(ctx: Context, filter: InboxFilter = InboxFilter.ALL, search: String? = null, limit: Int = 1000): List<InboxEntry> {
        val out = ArrayList<InboxEntry>()
        try {
            db(ctx).rawQuery(
                "SELECT id,nkey,pkg,app,title,body,posted,read,category FROM entries " +
                    (if (filter == InboxFilter.UNREAD) "WHERE read=0 " else "") + "ORDER BY posted DESC LIMIT 3000", null
            ).use { c ->
                val q = search?.trim()?.lowercase().orEmpty()
                while (c.moveToNext() && out.size < limit) {
                    val e = InboxEntry(
                        c.getLong(0), c.getString(1), c.getString(2), c.getString(3) ?: "",
                        Crypto.decrypt(c.getBlob(4)), Crypto.decrypt(c.getBlob(5)), c.getLong(6), c.getInt(7) == 1, c.getString(8) ?: "",
                    )
                    val ok = when (filter) {
                        InboxFilter.MESSAGES -> e.isMessage
                        InboxFilter.PAYMENTS -> e.isPayment
                        InboxFilter.OTP -> e.otp != null
                        else -> true
                    }
                    if (!ok) continue
                    if (q.isNotEmpty() && !("${e.app} ${e.title} ${e.text}".lowercase().contains(q))) continue
                    out += e
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun unreadCount(ctx: Context): Int = try {
        db(ctx).rawQuery("SELECT COUNT(*) FROM entries WHERE read=0", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    } catch (e: Exception) { 0 }

    /** Most recent OTP received in the last [withinMs]. */
    fun latestOtp(ctx: Context, withinMs: Long = 15 * 60_000L): String? {
        val since = System.currentTimeMillis() - withinMs
        return query(ctx, limit = 40).firstOrNull { it.posted >= since && it.otp != null }?.otp
    }

    fun markRead(ctx: Context, id: Long) = write(ctx) { it.execSQL("UPDATE entries SET read=1 WHERE id=$id") }
    fun markAllRead(ctx: Context) = write(ctx) { it.execSQL("UPDATE entries SET read=1") }
    fun delete(ctx: Context, id: Long) = write(ctx) { it.delete("entries", "id=?", arrayOf(id.toString())) }
    fun clearAll(ctx: Context) = write(ctx) { it.delete("entries", null, null); lastHashByKey.clear() }

    private fun write(ctx: Context, op: (SQLiteDatabase) -> Unit) {
        exec.execute { try { op(db(ctx)); bump() } catch (_: Exception) {} }
    }
}
