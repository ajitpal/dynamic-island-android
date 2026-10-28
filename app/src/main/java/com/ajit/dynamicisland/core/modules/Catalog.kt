package com.ajit.dynamicisland.core.modules

import android.content.Context
import org.json.JSONArray

/**
 * Modules that have been reviewed and listed (assets/catalog.json, generated from the JSON files in catalog/modules
 * by tools/gen_catalog.py). Bundled with the app because the island has no internet permission:
 * a newly approved module appears with the next island release.
 */
data class CatalogEntry(val id: String, val name: String, val icon: String, val pkg: String, val summary: String,
                        val developer: String, val download: String, val certSha256: String)

object Catalog {
    @Volatile private var cache: List<CatalogEntry>? = null

    fun load(ctx: Context): List<CatalogEntry> = cache ?: try {
        val arr = JSONArray(ctx.assets.open("catalog.json").bufferedReader().use { it.readText() })
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            CatalogEntry(o.getString("id"), o.getString("name"), o.optString("icon", "🧩"), o.getString("package"),
                o.getString("summary"), o.optString("developer"), o.getString("download"), o.getString("certSha256"))
        }
    } catch (e: Exception) { emptyList() }.also { cache = it }
}
