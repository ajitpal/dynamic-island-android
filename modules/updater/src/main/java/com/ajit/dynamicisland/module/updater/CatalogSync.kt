package com.ajit.dynamicisland.module.updater

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.ajit.dynamicisland.sdk.SignedCatalog
import java.io.File
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Keeps the module catalogue ("Get more") fresh between island releases: downloads the signed
 * catalogue from the website with the daily check, keeps it only if the release key signed it and it
 * is newer than the copy it has, and hands the files to the island through [CatalogProvider].
 * The island checks the signature again itself, so nothing here has to be trusted.
 */
object CatalogSync {
    private fun dir(ctx: Context) = File(ctx.filesDir, "catalog").apply { mkdirs() }
    fun file(ctx: Context, name: String) = File(dir(ctx), name)

    /** The serial of the catalogue kept now, or -1. */
    fun currentSerial(ctx: Context): Long = try {
        val d = file(ctx, SignedCatalog.FILE).readBytes(); val s = file(ctx, SignedCatalog.SIG_FILE).readBytes()
        SignedCatalog.open(ctx, d, s)?.serial ?: -1
    } catch (e: Exception) { -1 }

    /** Worker thread. Returns true if a newer catalogue was saved. Failures are silent: "Get more" keeps working from the copy in the island. */
    fun sync(ctx: Context): Boolean = try {
        val data = fetch(SignedCatalog.URL)
        val sig = fetch(SignedCatalog.URL + ".sig")
        val cat = SignedCatalog.open(ctx, data, sig)
        if (cat != null && cat.serial > currentSerial(ctx)) {
            write(file(ctx, SignedCatalog.FILE), data); write(file(ctx, SignedCatalog.SIG_FILE), sig); true
        } else false
    } catch (e: Exception) { false }

    private fun write(f: File, bytes: ByteArray) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) { f.writeBytes(bytes); tmp.delete() }
    }

    private fun fetch(url: String): ByteArray {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "DynamicIsland-Updater")
        }
        try {
            if (c.responseCode !in 200..299) throw FileNotFoundException("$url: ${c.responseCode}")
            return c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n)
                    require(out.size() <= SignedCatalog.MAX_BYTES) { "Catalogue too large" }
                }
                out.toByteArray()
            }
        } finally { c.disconnect() }
    }
}

/**
 * Read-only: `content://com.ajit.dynamicisland.module.updater.catalog/catalog.json` and `/catalog.json.sig`.
 * Protected by the island's HOST signature permission, so only the genuine island can read it.
 */
class CatalogProvider : ContentProvider() {
    override fun onCreate() = true
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read-only")
        val name = uri.lastPathSegment
        if (name != SignedCatalog.FILE && name != SignedCatalog.SIG_FILE) throw FileNotFoundException(uri.toString())
        val f = CatalogSync.file(context!!, name)
        if (!f.exists()) throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun getType(uri: Uri): String? = if (uri.lastPathSegment == SignedCatalog.FILE) "application/json" else "application/octet-stream"
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw SecurityException("read-only")
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = throw SecurityException("read-only")
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = throw SecurityException("read-only")
}
