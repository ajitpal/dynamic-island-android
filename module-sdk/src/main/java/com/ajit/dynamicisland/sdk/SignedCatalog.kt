package com.ajit.dynamicisland.sdk

import android.content.Context
import android.content.pm.PackageManager
import java.io.ByteArrayInputStream
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * The module catalogue that lists reviewed modules ("Get more"). Since 2.0 it is published, signed,
 * between island releases, so a newly approved module shows up without waiting for an island update.
 *
 * Files (written by tools/gen_catalog.py, signed by CI with the Dynamic Island release key):
 *  - `catalog.json`: `{"format":1,"serial":<seconds>,"modules":[{"id","name","icon","package","summary",
 *    "developer","download","certSha256"}, …]}`
 *  - `catalog.json.sig`: the release key's signature over the exact bytes of `catalog.json`
 *    (`openssl dgst -sha256 -sign`, i.e. SHA256withRSA or SHA256withECDSA).
 *
 * Only the island and the Updater (both signed with that key) use this: each checks the signature
 * against **its own** signing certificate, so neither has to trust the network or the other app.
 * A catalogue is used only if its [Catalog.serial] is newer than the one already in use (no rollback).
 */
object SignedCatalog {
    const val FORMAT = 1
    const val FILE = "catalog.json"
    const val SIG_FILE = "catalog.json.sig"
    /** Where the website publishes the signed catalogue (GitHub Pages, rebuilt on every push to main). */
    const val URL = "https://ajitpal.github.io/dynamic-island-android/catalog.json"
    /** The Updater shares the files it downloaded with the island through this read-only provider. */
    const val PROVIDER_AUTHORITY = "com.ajit.dynamicisland.module.updater.catalog"
    const val MAX_BYTES = 256 * 1024

    data class Entry(
        val id: String, val name: String, val icon: String, val pkg: String, val summary: String,
        val developer: String, val download: String, val certSha256: String,
    )

    data class Catalog(val serial: Long, val modules: List<Entry>)

    /** Parse either the signed format or the older plain list (serial 0). Throws on anything malformed. */
    fun parse(json: String): Catalog {
        val t = json.trim()
        val (serial, arr) = if (t.startsWith("[")) 0L to org.json.JSONArray(t) else {
            val o = org.json.JSONObject(t)
            require(o.optInt("format", 0) == FORMAT) { "This catalogue needs a newer app" }
            o.getLong("serial") to o.getJSONArray("modules")
        }
        val hex = Regex("[0-9a-f]{64}")
        val list = (0 until arr.length()).map { i ->
            val m = arr.getJSONObject(i)
            Entry(
                id = m.getString("id"), name = m.getString("name"), icon = m.optString("icon", "🧩"),
                pkg = m.getString("package"), summary = m.getString("summary"), developer = m.optString("developer"),
                download = m.getString("download").also { require(it.startsWith("https://")) { "Download links must be https" } },
                certSha256 = m.getString("certSha256").lowercase().also { require(hex.matches(it)) { "Bad certificate digest" } },
            )
        }
        return Catalog(serial, list)
    }

    /** True if [sig] is a valid signature over [data] by [key]. Never throws. */
    fun verify(data: ByteArray, sig: ByteArray, key: PublicKey): Boolean = try {
        val alg = when (key.algorithm) { "EC" -> "SHA256withECDSA"; "RSA" -> "SHA256withRSA"; else -> return false }
        Signature.getInstance(alg).run { initVerify(key); update(data); verify(sig) }
    } catch (e: Exception) { false }

    /** The public key this app is signed with (the release key in a release build). */
    fun ownKey(ctx: Context): PublicKey? = try {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signer = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return null
        (CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(signer.toByteArray())) as X509Certificate).publicKey
    } catch (e: Exception) { null }

    /**
     * Verify with this app's own signing key, then parse. Null if the signature is wrong, the file is
     * too large or malformed: callers then keep what they had.
     */
    fun open(ctx: Context, data: ByteArray, sig: ByteArray): Catalog? {
        if (data.size > MAX_BYTES || sig.size > 4096) return null
        val key = ownKey(ctx) ?: return null
        if (!verify(data, sig, key)) return null
        return try { parse(String(data, Charsets.UTF_8)) } catch (e: Exception) { null }
    }

    /** Which of two catalogues to use: the newer one; the current one when serials tie. */
    fun newer(current: Catalog?, candidate: Catalog?): Catalog? = when {
        candidate == null -> current
        current == null -> candidate
        candidate.serial > current.serial -> candidate
        else -> current
    }
}
