package com.ajit.dynamicisland.core.modules

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.ajit.dynamicisland.sdk.SignedCatalog
import org.json.JSONArray

/**
 * Updates the Updater module has found (2.2.1), shown on the Modules tab. Read from the Updater's
 * read-only provider (only this app can read it). Information only: installing still happens in the
 * Updater, which checks every download, so nothing here has to be trusted.
 */
object ModuleUpdates {
    data class Pending(val pkg: String, val name: String, val version: String)

    const val FILE = "updates.json"
    const val UPDATER = "com.ajit.dynamicisland.module.updater"
    private const val MAX_AGE_MS = 60_000L
    @Volatile private var cache: List<Pending> = emptyList()
    @Volatile private var loadedAt = 0L

    fun invalidate() { loadedAt = 0 }

    /** Pending updates for apps that are still installed and older than offered. Cached for a minute. */
    fun load(ctx: Context): List<Pending> {
        if (System.currentTimeMillis() - loadedAt < MAX_AGE_MS) return cache
        val list = try {
            val uri = Uri.parse("content://${SignedCatalog.PROVIDER_AUTHORITY}/$FILE")
            val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().takeIf { b -> b.size < 64 * 1024 }?.toString(Charsets.UTF_8) }
            if (text == null) emptyList() else parse(text).filter { p -> stillOlder(ctx, p) }
        } catch (e: Exception) { emptyList() } // no Updater, turned off, or no check yet
        cache = list; loadedAt = System.currentTimeMillis()
        return list
    }

    fun parse(json: String): List<Pending> {
        val arr = JSONArray(json)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val pkg = o.optString("package").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Pending(pkg, o.optString("name", pkg), o.optString("versionName"))
        }
    }

    /** The file can be a day old: drop apps updated (or removed) since. */
    private fun stillOlder(ctx: Context, p: Pending): Boolean = try {
        val code = ctx.packageManager.getPackageInfo(p.pkg, 0).versionName
        code != p.version
    } catch (e: Exception) { false }

    /** Open the Updater's Software Update screen (where the download is checked and installed). */
    fun openUpdater(ctx: Context): Boolean = try {
        ctx.startActivity(Intent().setComponent(ComponentName(UPDATER, "$UPDATER.MainActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (e: Exception) { false }
}
