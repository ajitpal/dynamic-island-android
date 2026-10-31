package com.ajit.dynamicisland.core

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.toBitmap
import java.util.Objects

/** Turns a posted notification into an island activity (or null when it shouldn't show). */
object NotificationParser {

    private val PAYMENT_APPS = setOf(
        "com.google.android.apps.walletnfcrel",      // Google Wallet
        "com.google.android.apps.nbu.paisa.user",    // Google Pay (India)
        "com.phonepe.app", "net.one97.paytm", "in.org.npci.upiapp", "com.dreamplug.androidapp", // PhonePe, Paytm, BHIM, CRED
        "com.mipay.wallet.in",
    )
    private val PAYMENT_WORDS = Regex("(?i)\\b(paid|payment|sent|received|debited|credited|successful|transaction)\\b")
    private val AMOUNT = Regex("(₹|Rs\\.?|INR|\\$|€|£)\\s?[0-9][0-9,]*(\\.[0-9]{1,2})?")
    private val RECORDER = Regex("(?i)(screenrecorder|soundrecorder|voicerecorder|recorder)")
    private val LIVE_APPS = setOf(
        "com.cricbuzz.android", "com.july.cricinfo", "com.espn.score_center", "com.onefootball.android.onefootball",
        "com.google.android.googlequicksearchbox", // Google app live sports
        "com.ubercab", "com.olacabs.customer", "com.application.zomato", "in.swiggy.android", "com.grofers.customerapp",
        "com.zeptoconsumerapp", "com.rapido.passenger",
    )

    private val iconCache = HashMap<String, Drawable>()
    private val labelCache = HashMap<String, String>()

    fun appIcon(ctx: Context, pkg: String): Drawable? {
        iconCache[pkg]?.let { return it }
        return try {
            ctx.packageManager.getApplicationIcon(pkg).also { iconCache[pkg] = it }
        } catch (e: Exception) {
            null
        }
    }

    fun appLabel(ctx: Context, pkg: String): String = labelCache.getOrPut(pkg) {
        try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
    }

    fun parse(ctx: Context, sbn: StatusBarNotification, prefs: Prefs): IslandActivity? {
        val n = sbn.notification ?: return null
        val pkg = sbn.packageName
        if (pkg == ctx.packageName || pkg == "android" || pkg == "com.android.systemui") return null
        if (prefs.isBlocked(pkg)) return null
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return null
        // A connected module shows its own card (REC timer, progress…); its ongoing notification would
        // show the same thing a second time, now visible with the "+1" circle (2.1).
        if ((n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE)) != 0 &&
            com.ajit.dynamicisland.core.modules.ModuleRegistry.modules.value.any { it.pkg == pkg && it.connected }) return null
        val ex: Bundle = n.extras ?: Bundle()
        // Music is handled through MediaSessions (richer + controllable).
        if (ex.containsKey(Notification.EXTRA_MEDIA_SESSION)) return null

        val ongoing = (n.flags and Notification.FLAG_ONGOING_EVENT) != 0 ||
            (n.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0
        val title = ex.getCharSequence(Notification.EXTRA_TITLE_BIG)
            ?: ex.getCharSequence(Notification.EXTRA_TITLE)
        val text = ex.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: ex.getCharSequence(Notification.EXTRA_TEXT)
        val label = appLabel(ctx, pkg)
        val icon = appIcon(ctx, pkg) ?: try { n.smallIcon?.loadDrawable(ctx) } catch (e: Exception) { null }
        val large = largeIcon(ctx, n)
        val accent = if (n.color != Notification.COLOR_DEFAULT && n.color != 0) n.color or 0xFF000000.toInt() else Colors.BLUE
        val callType = ex.getInt("android.callType", 0) // Notification.CallStyle (1 incoming, 2 ongoing, 3 screening)
        val pMax = ex.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val p = ex.getInt(Notification.EXTRA_PROGRESS, 0)
        val indeterminate = ex.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
        val chrono = ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)
        val countDown = ex.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN, false)
        val cat = n.category
        val actions = n.actions?.map { toAction(ctx, it) } ?: emptyList()
        val id = "n:" + sbn.key
        val now = System.currentTimeMillis()

        // ---- Calls -------------------------------------------------------------------------
        if (cat == Notification.CATEGORY_CALL || callType != 0) {
            if (!prefs.fCalls) return null
            val incoming = callType == 1 || (callType == 0 && actions.any { it.style == ActionStyle.POSITIVE })
            val callActions = callActions(ex, actions)
            val caller = personName(ex) ?: title ?: label
            return IslandActivity(
                id = id, kind = Kind.CALL, packageName = pkg, title = caller,
                text = if (incoming) "Incoming $label call" else (text ?: label),
                icon = icon, image = large, accent = Colors.GREEN,
                compactText = if (incoming) "Call" else null,
                chronoBase = if (!incoming && n.`when` > 0 && n.`when` <= now) n.`when` else 0L,
                incoming = incoming, contentIntent = n.contentIntent, actions = callActions,
                appLabel = label, peek = incoming, peekMs = 0L,
                contentHash = Objects.hash(caller.toString(), incoming),
            )
        }

        // ---- Payments (Google Wallet tap-to-pay, UPI apps) – like Apple Pay confirmations ---------
        if (!ongoing && pkg in PAYMENT_APPS && prefs.fPayments) {
            val all = "${title ?: ""} ${text ?: ""}"
            if (PAYMENT_WORDS.containsMatchIn(all)) {
                return IslandActivity(
                    id = id, kind = Kind.SYSTEM, packageName = pkg, title = title ?: "Payment", text = text,
                    icon = ctx.getDrawable(com.ajit.dynamicisland.R.drawable.ic_check), tintIcon = true,
                    accent = Colors.GREEN, compactText = AMOUNT.find(all)?.value ?: "Done",
                    contentIntent = n.contentIntent, appLabel = label,
                    peek = true, peekMs = 3500, expiresAt = now + 5000,
                    contentHash = Objects.hash(title?.toString(), text?.toString()),
                )
            }
        }

        // ---- Screen recording / voice recorder (red, like iOS) ---------------------------------
        if (ongoing && prefs.fRecording && RECORDER.containsMatchIn(pkg)) {
            val screen = pkg.contains("screen", ignoreCase = true)
            val hasChrono = n.`when` in 1..now
            return IslandActivity(
                id = id, kind = Kind.TIMER, packageName = pkg,
                title = if (screen) "Screen recording" else (title ?: "Recording"), text = text ?: label,
                icon = ctx.getDrawable(com.ajit.dynamicisland.R.drawable.ic_record), tintIcon = true,
                accent = Colors.RED, compactText = "REC",
                chronoBase = if (hasChrono) n.`when` else 0L,
                contentIntent = n.contentIntent, actions = actions.take(2), appLabel = label,
                contentHash = Objects.hash(title?.toString(), n.`when`),
            )
        }

        // ---- Live scores & Android 16 Live Updates ---------------------------------------------
        val promoted = ex.getBoolean("android.requestPromotedOngoing", false)
        if (ongoing && (promoted || pkg in LIVE_APPS) && prefs.fLive && (title != null || text != null)) {
            return IslandActivity(
                id = id, kind = Kind.LIVE, packageName = pkg, title = title ?: label, text = text,
                icon = icon, image = large, accent = accent,
                compactText = ex.getCharSequence("android.shortCriticalText") ?: title,
                progress = if (pMax > 0) p.toFloat() / pMax else -1f,
                contentIntent = n.contentIntent, actions = actions.take(2), appLabel = label,
                contentHash = Objects.hash(title?.toString(), text?.toString()),
            )
        }

        // ---- Navigation ----------------------------------------------------------------------
        if (cat == Notification.CATEGORY_NAVIGATION && ongoing) {
            if (!prefs.fNav) return null
            return IslandActivity(
                id = id, kind = Kind.NAVIGATION, packageName = pkg, title = title ?: label, text = text,
                icon = icon, image = large, accent = Colors.BLUE, compactText = title,
                contentIntent = n.contentIntent, actions = actions.take(2), appLabel = label,
                contentHash = Objects.hash(title?.toString(), text?.toString()),
            )
        }

        // ---- Timers / stopwatches / anything with a live chronometer ------------------------
        if ((cat == Notification.CATEGORY_STOPWATCH || chrono) && ongoing && n.`when` > 0) {
            if (!prefs.fTimers) return null
            return IslandActivity(
                id = id, kind = Kind.TIMER, packageName = pkg, title = title ?: label, text = text,
                icon = icon, image = large, accent = Colors.ORANGE, chronoBase = n.`when`, countDown = countDown,
                contentIntent = n.contentIntent, actions = actions.take(3), appLabel = label,
                contentHash = Objects.hash(title?.toString(), n.`when`, countDown),
            )
        }

        // ---- Progress (downloads, uploads, deliveries, cab ETA) -----------------------------
        if ((pMax > 0 || indeterminate) && (ongoing || cat == Notification.CATEGORY_PROGRESS)) {
            if (!prefs.fProgress) return null
            val frac = if (pMax > 0) p.toFloat() / pMax else -1f
            return IslandActivity(
                id = id, kind = Kind.PROGRESS, packageName = pkg, title = title ?: label, text = text,
                icon = icon, image = large, accent = accent, progress = frac, indeterminate = indeterminate,
                contentIntent = n.contentIntent, actions = actions.take(3), appLabel = label,
                contentHash = Objects.hash(title?.toString(), (frac * 100).toInt()),
            )
        }

        // ---- Messages -------------------------------------------------------------------------
        val style = try {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        } catch (e: Exception) {
            null
        }
        if (!ongoing && (cat == Notification.CATEGORY_MESSAGE || style != null)) {
            if (!prefs.fMessages) return null
            var t: CharSequence? = title
            var body: CharSequence? = text
            var avatar: Bitmap? = large
            val last = style?.messages?.lastOrNull()
            if (style != null && last != null) {
                val sender = last.person?.name
                body = last.text
                t = style.conversationTitle ?: sender ?: title
                if (style.isGroupConversation && sender != null) body = "$sender: ${last.text}"
                try {
                    last.person?.icon?.loadDrawable(ctx)?.let { avatar = it.toBitmap(128, 128) }
                } catch (_: Exception) {
                }
            }
            if (t == null && body == null) return null
            val peekMs = prefs.peekSeconds * 1000L
            return IslandActivity(
                id = id, kind = Kind.MESSAGE, packageName = pkg, title = t ?: label, text = body,
                icon = icon, image = avatar, accent = accent, compactText = t ?: label,
                contentIntent = n.contentIntent, actions = actions.take(3), appLabel = label,
                peek = true, peekMs = peekMs, expiresAt = now + peekMs + 6000,
                contentHash = Objects.hash(t?.toString(), body?.toString()),
            )
        }

        // ---- Everything else (opt-in) ----------------------------------------------------------
        if (prefs.fAll && !ongoing && (title != null || text != null)) {
            val peekMs = prefs.peekSeconds * 1000L
            return IslandActivity(
                id = id, kind = Kind.GENERIC, packageName = pkg, title = title ?: label, text = text,
                icon = icon, image = large, accent = accent, compactText = label,
                contentIntent = n.contentIntent, actions = actions.take(3), appLabel = label,
                peek = true, peekMs = peekMs, expiresAt = now + peekMs + 3000,
                contentHash = Objects.hash(title?.toString(), text?.toString()),
            )
        }
        return null
    }

    private fun largeIcon(ctx: Context, n: Notification): Bitmap? = try {
        n.getLargeIcon()?.loadDrawable(ctx)?.toBitmap(128, 128)
    } catch (e: Exception) {
        null
    }

    @Suppress("DEPRECATION")
    private fun personName(ex: Bundle): CharSequence? = try {
        (ex.getParcelable("android.callPerson") as? android.app.Person)?.name
    } catch (e: Exception) {
        null
    }

    private fun styleFor(title: String): ActionStyle {
        val t = title.lowercase()
        return when {
            listOf("answer", "accept", "pick up").any { it in t } -> ActionStyle.POSITIVE
            listOf("decline", "reject", "hang up", "end", "dismiss").any { it in t } -> ActionStyle.NEGATIVE
            else -> ActionStyle.NORMAL
        }
    }

    private fun toAction(ctx: Context, a: Notification.Action): IslandAction {
        val title = a.title?.toString() ?: ""
        val inputs = a.remoteInputs?.filter { it.allowFreeFormInput }
        return if (!inputs.isNullOrEmpty()) {
            val all = a.remoteInputs
            IslandAction(
                title = title, intent = a.actionIntent,
                inputHint = inputs.first().label?.toString() ?: "Reply…",
                onInput = { txt -> sendRemoteInput(ctx.applicationContext, a.actionIntent, all, txt) },
            )
        } else {
            IslandAction(title = title, intent = a.actionIntent, style = styleFor(title))
        }
    }

    private fun sendRemoteInput(ctx: Context, pi: PendingIntent?, inputs: Array<RemoteInput>, text: String) {
        pi ?: return
        val intent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        val results = Bundle()
        inputs.filter { it.allowFreeFormInput }.forEach { results.putCharSequence(it.resultKey, text) }
        RemoteInput.addResultsToIntent(inputs, intent, results)
        try {
            pi.send(ctx, 0, intent)
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun callActions(ex: Bundle, fallback: List<IslandAction>): List<IslandAction> {
        fun pi(key: String) = try { ex.getParcelable(key) as? PendingIntent } catch (e: Exception) { null }
        val decline = pi("android.declineIntent")
        val hangUp = pi("android.hangUpIntent")
        val answer = pi("android.answerIntent")
        val list = mutableListOf<IslandAction>()
        if (decline != null) list += IslandAction("Decline", decline, ActionStyle.NEGATIVE)
        if (hangUp != null) list += IslandAction("End", hangUp, ActionStyle.NEGATIVE)
        if (answer != null) list += IslandAction("Answer", answer, ActionStyle.POSITIVE)
        return if (list.isNotEmpty()) list else fallback.take(3)
    }
}
