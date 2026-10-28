package com.ajit.dynamicisland.module.speech

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import java.io.File

/**
 * Speech models (Meta's Omnilingual ASR, Apache 2.0, ONNX int8 export by k2-fsa). Pinned to a commit
 * and checked against their SHA-256. Measured on Hindi (FLEURS, 40 clips, Devanagari/Latin-only decoding):
 * Standard WER 18% / CER 10%, Best WER 10% / CER 4.5%.
 */
enum class Model(val label: String, val blurb: String, val repo: String, val commit: String, val bytes: Long, val sha256: String, val minRamGb: Int) {
    STANDARD("Standard", "Good accuracy, works on most phones.",
        "csukuangfj/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12", "6abf1ece20cd2308bdb7d13cd78ec1c44fa4c094",
        365_352_120L, "e7c4e54ee4c4c47829cc6667d5d00ed8ea7bef1dcfeef0fce766f77752a2726c", 4),
    BEST("Best", "About half the mistakes of Standard. Bigger and slower; for phones with 8 GB of memory.",
        "csukuangfj/sherpa-onnx-omnilingual-asr-1600-languages-1B-ctc-int8-2025-11-12", "17db76eab583b0b868ffb0df104ab879145087e5",
        1_031_628_252L, "f7b74c964039162423b83e3fa950ce24810c9a635d9ff8468b5f4d142b7c1e8c", 8);

    val url get() = "https://huggingface.co/$repo/resolve/$commit/model.int8.onnx"
}

object Models {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("speech", Context.MODE_PRIVATE)
    fun dir(ctx: Context) = File(ctx.filesDir, "models").apply { mkdirs() }
    fun file(ctx: Context, m: Model) = File(dir(ctx), "${m.name.lowercase()}.onnx")
    fun partFile(ctx: Context, m: Model) = File(dir(ctx), "${m.name.lowercase()}.onnx.part")
    fun isPresent(ctx: Context, m: Model) = file(ctx, m).let { it.exists() && it.length() == m.bytes }

    fun selected(ctx: Context): Model = runCatching { Model.valueOf(prefs(ctx).getString("model", null)!!) }.getOrNull()
        ?: Model.entries.firstOrNull { isPresent(ctx, it) } ?: if (totalRamGb(ctx) + 1 >= 8) Model.BEST else Model.STANDARD
    fun select(ctx: Context, m: Model) = prefs(ctx).edit().putString("model", m.name).apply()
    fun active(ctx: Context): Model? = selected(ctx).takeIf { isPresent(ctx, it) } ?: Model.entries.firstOrNull { isPresent(ctx, it) }
    fun delete(ctx: Context, m: Model) { file(ctx, m).delete(); partFile(ctx, m).delete() }

    /** Small files bundled in the app (tokens shared by both models, and the Silero VAD), copied out once. */
    fun asset(ctx: Context, name: String): File {
        val f = File(ctx.filesDir, name)
        if (!f.exists()) ctx.assets.open(name).use { i -> File(ctx.filesDir, "$name.tmp").also { t -> t.outputStream().use { i.copyTo(it) }; t.renameTo(f) } }
        return f
    }

    fun totalRamGb(ctx: Context): Double {
        val mi = ActivityManager.MemoryInfo(); ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
        return mi.totalMem / 1_073_741_824.0
    }
    /** Unknown memory (0) is treated as enough: we only warn when we actually know the phone is small. */
    fun enoughRam(ctx: Context, m: Model) = totalRamGb(ctx).let { it <= 0.0 || it + 1.0 >= m.minRamGb }
    fun freeBytes(ctx: Context) = StatFs(dir(ctx).path).availableBytes
}
