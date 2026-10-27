package com.ajit.dynamicisland.module.voice

import android.content.Context
import ai.moonshine.voice.AssetDownloader
import ai.moonshine.voice.ModelCache
import ai.moonshine.voice.ModelSpec
import java.io.File

/**
 * The on-device English speech model (Moonshine, MIT licence). Downloaded once from Moonshine's CDN
 * on the user's request; after that, transcription works fully offline.
 */
object ModelStore {
    /** [arch] values are Moonshine's streaming model architectures (checked against the 0.1.5 AAR). */
    enum class Size(val arch: Int, val label: String, val blurb: String) {
        SMALL(4, "Small", "More accurate. Recommended for meetings."),
        TINY(2, "Tiny", "Smaller download and lighter on older phones, a little less accurate."),
    }

    private const val PREFS = "voice"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun selected(ctx: Context): Size =
        runCatching { Size.valueOf(prefs(ctx).getString("model", Size.SMALL.name)!!) }.getOrDefault(Size.SMALL)
    fun select(ctx: Context, s: Size) = prefs(ctx).edit().putString("model", s.name).apply()

    private fun spec(s: Size) = ModelSpec.stt("en", s.arch, false)
    private fun root(ctx: Context) = ModelCache.defaultRoot(ctx)

    /** The model's own folder. Note: AssetDownloader's File argument is this folder, not the cache root
     *  (checked in the 0.1.5 bytecode: CatalogLoader passes ModelCache.directoryFor(...) to it). */
    fun dir(ctx: Context, s: Size = selected(ctx)): File = ModelCache.directoryFor(ctx, spec(s), root(ctx))

    fun isPresent(ctx: Context, s: Size = selected(ctx)) =
        runCatching { AssetDownloader().isModelPresent(dir(ctx, s), spec(s)) }.getOrDefault(false)

    fun sizeOnDisk(ctx: Context, s: Size): Long =
        dir(ctx, s).takeIf { it.exists() }?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    fun delete(ctx: Context, s: Size) { dir(ctx, s).deleteRecursively() }

    @Volatile var downloading: Size? = null
        private set

    /** Downloads on a background thread. Callbacks arrive on that thread. Returns the model folder. */
    fun download(ctx: Context, s: Size, progress: (done: Long, total: Long) -> Unit, done: (File?, Throwable?) -> Unit) {
        if (downloading != null) return
        downloading = s
        val app = ctx.applicationContext
        Thread({
            try {
                val dir = AssetDownloader().ensureModelPresent(dir(app, s), spec(s)) { _, fileIndex, totalFiles, got, total ->
                    // Coarse overall progress: whole files done + fraction of the current one.
                    val per = 1_000_000L
                    val frac = if (total > 0) got.toDouble() / total else 0.0
                    progress(((fileIndex + frac) * per).toLong(), totalFiles.coerceAtLeast(1) * per)
                }
                downloading = null; done(dir, null)
            } catch (t: Throwable) {
                downloading = null; done(null, t)
            }
        }, "model-download").start()
    }
}
