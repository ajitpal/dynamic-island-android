package com.ajit.dynamicisland.module.scanner

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.ajit.dynamicisland.module.scanner.hig.Hig
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Scanner: start a code or document scan, see what you scanned recently (and act on it again), and the
 * PDFs you saved. Laid out like iPhone Settings.
 */
class MainActivity : AppCompatActivity() {
    companion object { const val EXTRA_SHOW = "show" }

    private lateinit var hig: Hig
    private lateinit var content: LinearLayout
    private var show: Long = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hig = Hig(this)
        val page = hig.page("Scanner")
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.column.addView(content, hig.mp())
        setContentView(page.root)
        ViewCompat.setOnApplyWindowInsetsListener(page.root) { v, i ->
            val b = i.getInsets(WindowInsetsCompat.Type.systemBars()); v.setPadding(0, b.top, 0, b.bottom); i
        }
        show = intent.getLongExtra(EXTRA_SHOW, -1)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); show = intent.getLongExtra(EXTRA_SHOW, -1) }
    override fun onResume() { super.onResume(); render() }

    private fun render() {
        content.removeAllViews()
        // Just scanned (when the island isn't there to show it).
        History.get(this, show)?.let { item ->
            val s = hig.section(content, "Scanned")
            s.add(row(item))
            Actions.forPayload(item.payload).forEach { a -> s.add(hig.action(a.label) { Actions.run(this, a.id, item.payload) }) }
        }
        val start = hig.section(content)
        start.add(hig.nav("Scan Code", R.drawable.ic_qr, Hig.Tile.YELLOW, subtitle = "QR codes and barcodes: links, Wi-Fi, UPI, contacts") {
            startActivity(Intent(this, CodeActivity::class.java)) })
        start.add(hig.nav("Scan Document", R.drawable.ic_doc, Hig.Tile.GREEN, subtitle = "Pages to one PDF, straightened and cleaned up") {
            startActivity(Intent(this, DocActivity::class.java)) })

        val codes = History.all(this)
        if (codes.isNotEmpty()) {
            val s = hig.section(content, "Recent Codes", action = "Clear" to {
                MaterialAlertDialogBuilder(this).setTitle("Clear scan history?").setMessage("This removes the codes you scanned from this list.")
                    .setPositiveButton("Clear") { _, _ -> History.clear(this); render() }.setNegativeButton("Cancel", null).show()
            })
            codes.take(15).forEach { s.add(row(it, clickable = true)) }
        }

        Thread {
            val docs = Docs.saved(this)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (docs.isEmpty()) { hig.note(content, PRIVACY); return@runOnUiThread }
                val s = hig.section(content, "Documents", footer = "Saved in Documents › Dynamic Island. Touch and hold to share or delete.")
                docs.forEach { d ->
                    s.add(hig.row(d.name.removeSuffix(".pdf"), ago(d.time) +
                        " · " + Formatter.formatShortFileSize(this, d.size), hig.tile(R.drawable.ic_doc, Hig.Tile.GREEN), chevron = true,
                        onClick = { openPdf(this, d.uri, share = false) },
                        onLongClick = {
                            MaterialAlertDialogBuilder(this).setTitle(d.name.removeSuffix(".pdf")).setItems(arrayOf("Open", "Share", "Delete")) { _, i ->
                                when (i) { 0 -> openPdf(this, d.uri, false); 1 -> openPdf(this, d.uri, true)
                                    2 -> { Docs.delete(this, d.uri); render() } }
                            }.show()
                        }))
                }
                hig.note(content, PRIVACY)
            }
        }.start()
    }

    /** "Just now", "5 min. ago"… */
    private fun ago(t: Long) = if (System.currentTimeMillis() - t < 60_000) "Just now"
        else DateUtils.getRelativeTimeSpanString(t, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()

    private val PRIVACY = "Codes are read on your phone and pictures aren't kept. Nothing opens until you tap a button, and the link or payee is shown first. No internet permission."

    private fun row(item: History.Item, clickable: Boolean = false) = hig.row(item.payload.title,
        item.payload.detail + " · " + ago(item.time),
        hig.tile(iconFor(item.payload), colorFor(item.payload)), chevron = clickable,
        onClick = if (!clickable) null else ({ menu(item) }))

    private fun menu(item: History.Item) {
        val acts = Actions.forPayload(item.payload)
        MaterialAlertDialogBuilder(this).setTitle(item.payload.title)
            .setItems((acts.map { it.label } + "Delete").toTypedArray()) { _, i ->
                if (i < acts.size) Actions.run(this, acts[i].id, item.payload) else { History.remove(this, item.id); render() }
            }.show()
    }
}
