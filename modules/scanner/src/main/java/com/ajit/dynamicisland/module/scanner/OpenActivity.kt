package com.ajit.dynamicisland.module.scanner

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ajit.dynamicisland.sdk.IslandContract

/**
 * Invisible screen behind the island's buttons (Open, Join, Pay, Copy, Share…): runs the action from a
 * visible screen, as Android requires, then closes. Only the island and this app can open it.
 */
class OpenActivity : AppCompatActivity() {
    companion object {
        fun code(id: Long, action: String) = "code|$id|$action"
        fun pdf(uri: Uri, action: String) = "pdf|$action|$uri"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val arg = intent.getStringExtra(IslandContract.EXTRA_ARG).orEmpty().split('|', limit = 3)
        when (arg.getOrNull(0)) {
            "code" -> {
                val item = arg.getOrNull(1)?.toLongOrNull()?.let { History.get(this, it) }
                if (item == null) Toast.makeText(this, "That scan is no longer in your history", Toast.LENGTH_LONG).show()
                else Actions.run(this, arg.getOrNull(2) ?: "open", item.payload)
            }
            "pdf" -> {
                val uri = arg.getOrNull(2)?.takeIf { it.startsWith("content://media/") }?.let(Uri::parse)
                if (uri != null) openPdf(this, uri, arg.getOrNull(1) == "share")
            }
        }
        finish()
    }
}

fun openPdf(a: android.app.Activity, uri: Uri, share: Boolean) {
    val i = if (share) Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share scan")
    else Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try { a.startActivity(i) } catch (e: Exception) { Toast.makeText(a, "No app can open PDFs", Toast.LENGTH_LONG).show() }
}
