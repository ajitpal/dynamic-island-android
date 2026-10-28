package com.ajit.dynamicisland.module.updater

import org.json.JSONObject

/**
 * What a GitHub release offers, read from its `update.json` asset (written by tools/collect_release.py):
 *
 * ```
 * {"schema":1, "release":"1.10.0", "tag":"v1.10.0", "notes":"…",
 *  "apps":[{"package":"com.ajit.dynamicisland","name":"Dynamic Island","versionCode":17,"versionName":"1.10.0",
 *           "file":"DynamicIsland-v1.10.0.apk","sha256":"…","size":2687092}, …]}
 * ```
 * Pure Kotlin so it's unit-tested without a phone.
 */
data class Release(val version: String, val tag: String, val notes: String, val apps: List<App>) {
    data class App(
        val pkg: String, val name: String, val versionCode: Long, val versionName: String,
        val file: String, val sha256: String, val size: Long,
    )

    fun downloadUrl(a: App) = "https://github.com/${Repo.OWNER_REPO}/releases/download/$tag/${a.file}"

    companion object {
        fun parse(json: String): Release {
            val o = JSONObject(json)
            require(o.optInt("schema", 1) == 1) { "This release needs a newer Updater" }
            val arr = o.getJSONArray("apps")
            val apps = (0 until arr.length()).map { i ->
                val a = arr.getJSONObject(i)
                App(
                    pkg = a.getString("package"), name = a.getString("name"),
                    versionCode = a.getLong("versionCode"), versionName = a.getString("versionName"),
                    file = a.getString("file").also { require(!it.contains('/') && it.endsWith(".apk")) { "Bad file name" } },
                    sha256 = a.getString("sha256").lowercase().also { require(Regex("[0-9a-f]{64}").matches(it)) { "Bad checksum" } },
                    size = a.optLong("size", 0),
                )
            }
            return Release(o.getString("release"), o.getString("tag"), o.optString("notes"), apps)
        }
    }
}

/** An installed app (the island or one of its modules) and the newer version a release offers. */
data class Update(val app: Release.App, val installedVersion: String, val installedCode: Long)

object Repo {
    const val OWNER_REPO = "ajitpal/dynamic-island-android"
    /** GitHub redirects this to the newest release's asset; no API key or rate limit involved. */
    const val MANIFEST_URL = "https://github.com/$OWNER_REPO/releases/latest/download/update.json"
    const val RELEASES_PAGE = "https://github.com/$OWNER_REPO/releases/latest"
    const val UPDATER_PACKAGE = "com.ajit.dynamicisland.module.updater"
    const val ISLAND_PACKAGE = "com.ajit.dynamicisland"

    /**
     * Updates for apps that are installed and older than the release. Order: modules first, then the
     * island, then the Updater itself (installing it ends this process).
     */
    fun pending(r: Release, installed: (String) -> Pair<Long, String>?): List<Update> =
        r.apps.mapNotNull { a ->
            val (code, name) = installed(a.pkg) ?: return@mapNotNull null
            if (a.versionCode > code) Update(a, name, code) else null
        }.sortedBy { when (it.app.pkg) { UPDATER_PACKAGE -> 2; ISLAND_PACKAGE -> 1; else -> 0 } }
}
