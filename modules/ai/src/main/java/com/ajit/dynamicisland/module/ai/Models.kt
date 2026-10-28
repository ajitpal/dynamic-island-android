package com.ajit.dynamicisland.module.ai

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import java.io.File

/**
 * Open models this engine can run (LiteRT-LM format, from Google's litert-community on Hugging Face,
 * all Apache 2.0, no sign-in needed). URLs are pinned to a commit and every file is checked against
 * its SHA-256 after download.
 */
enum class Model(
    val label: String, val blurb: String, val repo: String, val commit: String, val file: String,
    val bytes: Long, val sha256: String, val contextTokens: Int, val minRamGb: Int, val qwen: Boolean,
) {
    LIGHT("Qwen3 0.6B", "Fastest and smallest. Fine for short notes and replies.",
        "litert-community/Qwen3-0.6B", "fed81d17517e6ea64362e917ecf7e5accff12492", "Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm",
        344_671_744L, "03e7da1eb1108b50dffaa9bb52cc7bcbad2eb0c66ca990267f480c1e545d2856", 4096, 4, true),
    BALANCED("Qwen3 1.7B", "Good balance of quality and speed. Recommended.",
        "litert-community/Qwen3-1.7B", "79bf0667b32ae025b972e5fe555fe72ca6e8816c", "Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm",
        977_184_032L, "2a72330b504a6596d53a2c00923c57173b10f1b83e6a541213ffec08d1fc5de8", 4096, 6, true),
    BEST("Gemma 4 E2B", "Best notes, but a big download and slower. For phones with plenty of memory.",
        "litert-community/gemma-4-E2B-it-litert-lm", "1caa527c1e88b55f046fcd8d2b4265606680663d", "gemma-4-E2B-it.litertlm",
        2_588_147_712L, "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c", 4096, 8, false);

    val url get() = "https://huggingface.co/$repo/resolve/$commit/$file"
    val licenceUrl get() = "https://huggingface.co/$repo"
}

object Models {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("ai", Context.MODE_PRIVATE)

    fun dir(ctx: Context) = File(ctx.filesDir, "models").apply { mkdirs() }
    fun file(ctx: Context, m: Model) = File(dir(ctx), m.file)
    fun partFile(ctx: Context, m: Model) = File(dir(ctx), m.file + ".part")

    /**
     * Where LiteRT-LM keeps each model's "prepared" (XNNPACK) file, about the size of the model, made
     * on the first load. In app storage rather than the cache folder, so Android doesn't delete it to
     * free space (which would make the next load slow again).
     */
    fun preparedDir(ctx: Context) = File(ctx.filesDir, "prepared").apply { mkdirs() }

    fun isPresent(ctx: Context, m: Model) = file(ctx, m).let { it.exists() && it.length() == m.bytes }

    fun selected(ctx: Context): Model =
        runCatching { Model.valueOf(prefs(ctx).getString("model", null)!!) }.getOrNull()
            ?: Model.entries.firstOrNull { isPresent(ctx, it) } ?: Model.BALANCED
    fun select(ctx: Context, m: Model) = prefs(ctx).edit().putString("model", m.name).apply()

    /** The model to actually run: the selected one if downloaded, otherwise any downloaded one. */
    fun active(ctx: Context): Model? = selected(ctx).takeIf { isPresent(ctx, it) } ?: Model.entries.firstOrNull { isPresent(ctx, it) }

    fun useGpu(ctx: Context) = prefs(ctx).getBoolean("gpu", false)
    fun setUseGpu(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("gpu", on).apply()

    fun delete(ctx: Context, m: Model) {
        file(ctx, m).delete(); partFile(ctx, m).delete()
        listOf(preparedDir(ctx), ctx.cacheDir).forEach { d -> d.listFiles()?.filter { it.name.startsWith(m.file + "_") }?.forEach { it.delete() } }
    }

    fun totalRamGb(ctx: Context): Double {
        val mi = ActivityManager.MemoryInfo(); ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
        return mi.totalMem / 1_073_741_824.0
    }

    /** Phones advertise e.g. "8 GB" but report ~7.3 GB usable, so allow some slack. */
    fun enoughRam(ctx: Context, m: Model) = totalRamGb(ctx) + 1.0 >= m.minRamGb

    fun freeBytes(ctx: Context) = StatFs(dir(ctx).path).availableBytes
}
