package com.ajit.dynamicisland.core.modules

import android.content.Context
import android.net.Uri
import com.ajit.dynamicisland.sdk.SignedCatalog

/**
 * Modules that have been reviewed and listed ("Get more").
 *
 * Two sources, newest wins:
 *  1. **Bundled** `assets/catalog.json`, generated from the files in catalog/modules by tools/gen_catalog.py.
 *     Always there; the island has no internet permission.
 *  2. **Live** (2.0): the Updater module downloads the catalogue signed with the Dynamic Island release
 *     key from the website and shares the files through a provider only this app can read. The island
 *     checks the signature against its own signing key and uses it only if its serial is newer, so a
 *     newly approved module appears (and is verified) without an island update.
 */
data class CatalogEntry(val id: String, val name: String, val icon: String, val pkg: String, val summary: String,
                        val developer: String, val download: String, val certSha256: String)

object Catalog {
    private const val MAX_AGE_MS = 10 * 60_000L
    @Volatile private var cache: List<CatalogEntry>? = null
    @Volatile private var loadedAt = 0L
    /** Serial of the catalogue in use (0 = the copy bundled with the app). */
    @Volatile var serial = 0L
        private set
    /** True when the list came from the Updater's newer, signed copy. */
    @Volatile var live = false
        private set

    fun load(ctx: Context): List<CatalogEntry> {
        val c = cache
        if (c != null && System.currentTimeMillis() - loadedAt < MAX_AGE_MS) return c
        val bundled = try { SignedCatalog.parse(ctx.assets.open(SignedCatalog.FILE).bufferedReader().use { it.readText() }) } catch (e: Exception) { null }
        val fromUpdater = liveCopy(ctx)
        val use = SignedCatalog.newer(bundled, fromUpdater)
        live = use != null && use === fromUpdater && use !== bundled
        serial = use?.serial ?: 0
        val list = use?.modules.orEmpty().map { CatalogEntry(it.id, it.name, it.icon, it.pkg, it.summary, it.developer, it.download, it.certSha256) }
        cache = list; loadedAt = System.currentTimeMillis()
        return list
    }

    /** Certificates of every listed developer: the island trusts these in code (the manifest allow-list is fixed per release). */
    fun certs(ctx: Context): Set<String> = load(ctx).mapTo(HashSet()) { it.certSha256 }

    fun invalidate() { loadedAt = 0 }

    private fun liveCopy(ctx: Context): SignedCatalog.Catalog? = try {
        val base = Uri.parse("content://${SignedCatalog.PROVIDER_AUTHORITY}/")
        val cr = ctx.contentResolver
        val data = cr.openInputStream(base.buildUpon().appendPath(SignedCatalog.FILE).build())?.use { it.readBytes(SignedCatalog.MAX_BYTES) }
        val sig = cr.openInputStream(base.buildUpon().appendPath(SignedCatalog.SIG_FILE).build())?.use { it.readBytes(4096) }
        if (data == null || sig == null) null else SignedCatalog.open(ctx, data, sig)
    } catch (e: Exception) { null } // Updater not installed, turned off, or nothing downloaded yet

    private fun java.io.InputStream.readBytes(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192)
        while (true) { val n = read(buf); if (n < 0) break; out.write(buf, 0, n); if (out.size() > max) throw java.io.IOException("too large") }
        return out.toByteArray()
    }
}
