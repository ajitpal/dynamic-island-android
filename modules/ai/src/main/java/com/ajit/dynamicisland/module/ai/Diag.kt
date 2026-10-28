package com.ajit.dynamicisland.module.ai

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the engine did, kept on the phone so a problem can be explained: recent runs with timings, an
 * event log, a crash marker around model loading, and a report the person can copy and share.
 * Nothing leaves the phone unless the person copies the report.
 */
object Diag {
    private const val TAG = "AiEngine"
    private const val MAX_LINES = 300
    @Volatile private var file: File? = null
    private val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun init(ctx: Context) { file = File(ctx.filesDir, "diagnostics.log") }

    @Synchronized fun log(msg: String) {
        Log.i(TAG, msg)
        val f = file ?: return
        runCatching {
            val lines = (if (f.exists()) f.readLines() else emptyList()) + "${time.format(Date())}  $msg"
            f.writeText(lines.takeLast(MAX_LINES).joinToString("\n") + "\n")
        }
    }

    /** One finished request, shown on the AI Engine screen. */
    fun record(ctx: Context, model: String, result: String) {
        log("run $model: $result")
        val sp = ctx.getSharedPreferences("diag", Context.MODE_PRIVATE)
        val runs = (sp.getString("runs", "") ?: "").split('\n').filter { it.isNotBlank() }
        sp.edit().putString("runs", (runs + "${time.format(Date())} · $model · $result").takeLast(8).joinToString("\n")).apply()
    }

    fun runs(ctx: Context): List<String> =
        (ctx.getSharedPreferences("diag", Context.MODE_PRIVATE).getString("runs", "") ?: "").split('\n').filter { it.isNotBlank() }.reversed()

    // ---- crash marker: set before a native load, cleared after. Still set at the next start = the load crashed the process.
    fun markLoading(ctx: Context, model: String) = ctx.getSharedPreferences("diag", Context.MODE_PRIVATE).edit().putString("loading", model).commit()
    fun clearLoading(ctx: Context) = ctx.getSharedPreferences("diag", Context.MODE_PRIVATE).edit().remove("loading").commit()

    /** At process start: if the last load never finished, the process died during it. Clean up and remember. */
    fun checkCrashedLoad(ctx: Context) {
        val sp = ctx.getSharedPreferences("diag", Context.MODE_PRIVATE)
        val name = sp.getString("loading", null) ?: return
        clearLoading(ctx)
        val m = runCatching { Model.valueOf(name) }.getOrNull() ?: return
        val f = Models.file(ctx, m)
        listOf(Models.preparedDir(ctx), ctx.cacheDir).forEach { d ->
            d.listFiles()?.filter { it.name.startsWith(f.name + "_") && it.name.endsWith(".xnnpack_cache") }?.forEach { it.delete() }
        }
        record(ctx, m.label, "the AI Engine closed while loading this model (often: not enough memory). Prepared file removed.")
        sp.edit().putInt("crashes_${m.name}", sp.getInt("crashes_${m.name}", 0) + 1).apply()
    }

    fun crashes(ctx: Context, m: Model) = ctx.getSharedPreferences("diag", Context.MODE_PRIVATE).getInt("crashes_${m.name}", 0)

    /** Everything useful for "it doesn't work on my phone", as plain text. */
    fun report(ctx: Context): String {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val gb = { b: Long -> "%.1f GB".format(b / 1_073_741_824.0) }
        val pi = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }.getOrNull()
        val sb = StringBuilder()
        sb.appendLine("AI Engine ${pi?.versionName} (${pi?.longVersionCode}) · LiteRT-LM 0.18.1")
        sb.appendLine("Phone: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("Chip: ${if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL else Build.HARDWARE} · ${Runtime.getRuntime().availableProcessors()} cores · ${Build.SUPPORTED_ABIS.joinToString()}")
        sb.appendLine("Memory: ${gb(mi.totalMem)} total, ${gb(mi.availMem)} free${if (mi.lowMemory) " (LOW)" else ""} · app limit ${am.largeMemoryClass} MB")
        sb.appendLine("Storage free: ${gb(Models.freeBytes(ctx))}")
        sb.appendLine("Selected: ${Models.selected(ctx).label} · ${if (Models.useGpu(ctx)) "GPU" else "CPU"} · loaded now: ${LlmEngine.loadedModel ?: "none"}")
        Model.entries.forEach { m ->
            val f = Models.file(ctx, m)
            val prepared = listOf(Models.preparedDir(ctx), ctx.cacheDir).flatMap { d -> d.listFiles()?.filter { it.name.startsWith(f.name + "_") }.orEmpty() }
            sb.appendLine("  ${m.label}: ${if (f.exists()) "${f.length()} B${if (f.length() == m.bytes) " ✓" else " (size wrong)"}" else "not downloaded"}" +
                (if (prepared.isNotEmpty()) " · prepared ${prepared.sumOf { it.length() }} B" else "") + (if (crashes(ctx, m) > 0) " · closed while loading ×${crashes(ctx, m)}" else ""))
        }
        sb.appendLine().appendLine("Recent runs:")
        runs(ctx).ifEmpty { listOf("none") }.forEach { sb.appendLine("  $it") }
        sb.appendLine().appendLine("Engine log:")
        file?.takeIf { it.exists() }?.readLines()?.takeLast(60)?.forEach { sb.appendLine("  $it") }
        // An app may read its own log lines; LiteRT-LM writes there too (native warnings and errors).
        val own = runCatching {
            val p = ProcessBuilder("logcat", "-d", "-t", "400", "--pid=${android.os.Process.myPid()}").redirectErrorStream(true).start()
            p.inputStream.bufferedReader().readLines().filter { l -> listOf("AiEngine", "litert", "LiteRt", "tflite", "xnnpack", "FATAL", " E ").any { l.contains(it, true) } }.takeLast(80)
        }.getOrDefault(emptyList())
        if (own.isNotEmpty()) { sb.appendLine().appendLine("System log (this app only):"); own.forEach { sb.appendLine("  $it") } }
        return sb.toString()
    }
}
