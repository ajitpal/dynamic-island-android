package com.ajit.dynamicisland.module.screen

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ajit.dynamicisland.sdk.IslandContract

/**
 * Invisible screen behind the island's Play / Share buttons: opens the video with read access for
 * the player or the app you share to, then closes. Only the island and this app can open it.
 */
class OpenActivity : AppCompatActivity() {
    companion object {
        const val PLAY = "play"
        const val SHARE = "share"
        fun arg(what: String, uri: Uri) = "$what|$uri"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val arg = intent.getStringExtra(IslandContract.EXTRA_ARG).orEmpty()
        val what = arg.substringBefore('|')
        val uri = arg.substringAfter('|', "").takeIf { it.startsWith("content://media/") }?.let(Uri::parse)
        if (uri == null) { finish(); return }
        val i = if (what == SHARE) Intent.createChooser(Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share recording")
        else Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(i) } catch (e: Exception) { Toast.makeText(this, "No app can open this video", Toast.LENGTH_LONG).show() }
        finish()
    }
}
