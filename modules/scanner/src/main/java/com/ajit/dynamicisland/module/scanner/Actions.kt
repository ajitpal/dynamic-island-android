package com.ajit.dynamicisland.module.scanner

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiNetworkSuggestion
import android.provider.ContactsContract
import android.provider.Settings
import android.widget.Toast

/** What each button does with a scanned code. Every action needs a tap; nothing opens by itself. */
object Actions {
    data class Action(val id: String, val label: String)

    /** Up to three buttons, most useful first. */
    fun forPayload(p: Payload): List<Action> = when (p) {
        is Payload.Url -> listOf(Action("open", "Open"), Action("copy", "Copy"), Action("share", "Share"))
        is Payload.Wifi -> listOfNotNull(Action("wifi", "Join"), if (p.password != null) Action("copy", "Copy Password") else null)
        is Payload.Upi -> listOf(Action("open", "Pay"), Action("copy", "Copy UPI ID"))
        is Payload.Phone -> listOf(Action("open", "Call"), Action("contact", "Add Contact"), Action("copy", "Copy"))
        is Payload.Email -> listOf(Action("open", "Email"), Action("copy", "Copy"))
        is Payload.Sms -> listOf(Action("open", "Message"), Action("copy", "Copy"))
        is Payload.Geo -> listOf(Action("open", "Open Map"), Action("copy", "Copy"))
        is Payload.Contact -> listOf(Action("contact", "Add Contact"), Action("copy", "Copy"))
        is Payload.Product -> listOf(Action("search", "Search"), Action("copy", "Copy"))
        is Payload.Text -> listOf(Action("copy", "Copy"), Action("share", "Share"))
    }

    /** Runs [action] from a visible activity (Android only lets visible screens start others). */
    fun run(ctx: Context, action: String, p: Payload) {
        when (action) {
            "copy" -> copy(ctx, when (p) {
                is Payload.Wifi -> p.password ?: p.ssid
                is Payload.Upi -> p.vpa
                is Payload.Url -> p.url
                is Payload.Phone -> p.number
                is Payload.Email -> p.to
                is Payload.Product -> p.code
                else -> p.raw
            }, sensitive = p is Payload.Wifi)
            "share" -> start(ctx, Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, (p as? Payload.Url)?.url ?: p.raw), "Share"))
            "search" -> start(ctx, Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, (p as? Payload.Product)?.code ?: p.raw))
            "wifi" -> (p as? Payload.Wifi)?.let { joinWifi(ctx, it) }
            "contact" -> start(ctx, Intent(ContactsContract.Intents.Insert.ACTION).setType(ContactsContract.RawContacts.CONTENT_TYPE).apply {
                when (p) {
                    is Payload.Contact -> { p.name?.let { putExtra(ContactsContract.Intents.Insert.NAME, it) }; p.phone?.let { putExtra(ContactsContract.Intents.Insert.PHONE, it) }
                        p.email?.let { putExtra(ContactsContract.Intents.Insert.EMAIL, it) }; p.org?.let { putExtra(ContactsContract.Intents.Insert.COMPANY, it) } }
                    is Payload.Phone -> putExtra(ContactsContract.Intents.Insert.PHONE, p.number)
                    else -> Unit
                }
            })
            "open" -> when (p) {
                is Payload.Url -> start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(p.url)))
                // Android shows the UPI apps (GPay, PhonePe, Paytm, BHIM…); the app shows who you're paying before you confirm.
                is Payload.Upi -> start(ctx, Intent.createChooser(Intent(Intent.ACTION_VIEW, Uri.parse(p.raw)), "Pay ${p.name ?: p.vpa} with"))
                is Payload.Phone -> start(ctx, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(p.number))))
                is Payload.Email -> start(ctx, Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + p.to)).apply {
                    p.subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }; p.body?.let { putExtra(Intent.EXTRA_TEXT, it) } })
                is Payload.Sms -> start(ctx, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(p.number))).apply { p.body?.let { putExtra("sms_body", it) } })
                is Payload.Geo -> start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(p.raw)))
                else -> copy(ctx, p.raw)
            }
        }
    }

    private fun joinWifi(ctx: Context, w: Payload.Wifi) {
        val b = WifiNetworkSuggestion.Builder().setSsid(w.ssid).setIsHiddenSsid(w.hidden)
        when {
            w.password == null -> Unit
            w.security == "WPA3" -> b.setWpa3Passphrase(w.password)
            w.security == "WEP" -> { copy(ctx, w.password, sensitive = true); start(ctx, Intent(Settings.Panel.ACTION_WIFI)); return } // Android can't add WEP networks for apps
            else -> b.setWpa2Passphrase(w.password)
        }
        // Android's own "Save this network?" sheet (Android 11+).
        start(ctx, Intent(Settings.ACTION_WIFI_ADD_NETWORKS).putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, arrayListOf(b.build())))
    }

    fun copy(ctx: Context, text: String, sensitive: Boolean = false) {
        val clip = ClipData.newPlainText("Scanned code", text)
        // Android 13+: keep passwords out of the clipboard preview.
        if (sensitive) clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
        if (android.os.Build.VERSION.SDK_INT < 33) Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun start(ctx: Context, i: Intent) {
        try { ctx.startActivity(i) } catch (e: Exception) { Toast.makeText(ctx, "No app can open this", Toast.LENGTH_LONG).show() }
    }

}
