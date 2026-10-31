package com.ajit.dynamicisland.module.scanner

import android.net.Uri
import com.ajit.dynamicisland.sdk.ActionStyle
import com.ajit.dynamicisland.sdk.IslandCard
import com.ajit.dynamicisland.sdk.IslandModuleService
import com.ajit.dynamicisland.sdk.IslandTile
import com.ajit.dynamicisland.sdk.ModuleTool

/**
 * Connects to the island. Tools: Scan Code, Scan Document, Scans. After a scan the island shows what
 * the code is (link, Wi-Fi, UPI payment…) with the right buttons, or "Document saved" with Open/Share.
 */
class ScannerModule : IslandModuleService() {
    companion object {
        const val CARD = "result"
        val instance get() = current as? ScannerModule
    }

    override val tools get() = listOf(
        ModuleTool("code", "Scan Code", R.drawable.ic_qr, CodeActivity::class.java),
        ModuleTool("doc", "Scan Document", R.drawable.ic_doc, DocActivity::class.java),
        ModuleTool("scans", "Scans", R.drawable.ic_list, MainActivity::class.java),
    )
    override val mainActivity: Class<*> get() = MainActivity::class.java

    override fun onIslandConnected() { postTile() }

    fun postTile() {
        val last = History.all(this).firstOrNull()
        val b = IslandTile.Builder(this).icon(R.drawable.ic_qr).tapActivity(MainActivity::class.java)
            .title(last?.payload?.title ?: "Scanner").text(if (last == null) "Scan QR codes, barcodes and documents" else "Last scan · ${last.payload.detail}".take(110))
            .activityAction("code", "Scan Code", CodeActivity::class.java)
            .activityAction("doc", "Document", DocActivity::class.java)
        island.postTile(b.build())
    }

    /** The scanned code, previewed first (the link's address, who a UPI payment goes to) with buttons to act on it. */
    fun postCode(item: History.Item): Boolean {
        if (!island.isConnected) return false
        val p = item.payload
        val acts = Actions.forPayload(p)
        val b = IslandCard.Builder(this, CARD).title(p.title).text(p.detail).compact(kindLabel(p))
            .icon(iconFor(p)).accent(colorFor(p)).peek(8000).expiresIn(5 * 60_000L).version((item.id / 1000).toInt())
            .tapActivity(MainActivity::class.java)
        acts.forEachIndexed { i, a -> b.activityAction(a.id, a.label, OpenActivity::class.java, OpenActivity.code(item.id, a.id), if (i == 0) ActionStyle.POSITIVE else ActionStyle.NORMAL) }
        island.post(b.build())
        postTile()
        return true
    }

    fun postDoc(uri: Uri, pages: Int): Boolean {
        if (!island.isConnected) return false
        island.post(IslandCard.Builder(this, CARD).title("Document Saved").text("$pages ${if (pages == 1) "page" else "pages"} · Documents › Dynamic Island")
            .compact("PDF").icon(R.drawable.ic_doc).accent(0xFF30D158.toInt()).peek(6000).expiresIn(5 * 60_000L)
            .tapActivity(OpenActivity::class.java, OpenActivity.pdf(uri, "open"))
            .activityAction("open", "Open", OpenActivity::class.java, OpenActivity.pdf(uri, "open"), ActionStyle.POSITIVE)
            .activityAction("share", "Share", OpenActivity::class.java, OpenActivity.pdf(uri, "share")).build())
        return true
    }

    private fun kindLabel(p: Payload) = when (p) {
        is Payload.Url -> "Link"; is Payload.Wifi -> "Wi-Fi"; is Payload.Upi -> "UPI"; is Payload.Phone -> "Phone"
        is Payload.Email -> "Email"; is Payload.Sms -> "SMS"; is Payload.Geo -> "Map"; is Payload.Contact -> "Contact"
        is Payload.Product -> "Barcode"; is Payload.Text -> "Text"
    }
}

fun iconFor(p: Payload) = when (p) {
    is Payload.Url -> R.drawable.ic_link
    is Payload.Wifi -> R.drawable.ic_wifi
    is Payload.Upi -> R.drawable.ic_rupee
    is Payload.Geo -> R.drawable.ic_location
    is Payload.Product -> R.drawable.ic_barcode
    else -> R.drawable.ic_qr
}

fun colorFor(p: Payload) = when (p) {
    is Payload.Url -> 0xFF0A84FF.toInt()
    is Payload.Wifi -> 0xFF30B0C7.toInt()
    is Payload.Upi -> 0xFF30D158.toInt()
    is Payload.Geo -> 0xFFFF9500.toInt()
    else -> 0xFFFFCC00.toInt()
}
