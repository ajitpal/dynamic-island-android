package com.ajit.dynamicisland.core

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.core.content.ContextCompat
import com.ajit.dynamicisland.R

/** Built-in tools that live inside the island: timer, stopwatch, flashlight, pinned note. */
object IslandTools {
    const val TIMER_ID = "tool:timer"
    const val TIMER_DONE_ID = "tool:timerdone"
    const val STOPWATCH_ID = "tool:stopwatch"
    const val NOTE_ID = "tool:note"
    const val TORCH_ID = "tool:torch"

    private val handler = Handler(Looper.getMainLooper())
    private var timerDone: Runnable? = null

    fun startTimer(context: Context, ms: Long) {
        val ctx = context.applicationContext
        val end = System.currentTimeMillis() + ms
        timerDone?.let(handler::removeCallbacks)
        IslandRepository.remove(TIMER_DONE_ID)
        IslandRepository.upsert(
            IslandActivity(
                id = TIMER_ID, kind = Kind.TIMER, packageName = ctx.packageName,
                title = "Timer", text = "${formatClock(ms)} timer",
                icon = ContextCompat.getDrawable(ctx, R.drawable.ic_timer), tintIcon = true,
                accent = Colors.ORANGE, chronoBase = end, countDown = true,
                actions = listOf(
                    IslandAction("Cancel", style = ActionStyle.NEGATIVE, onClick = { cancelTimer() }),
                    IslandAction("+1 min", onClick = {
                        startTimer(ctx, (end - System.currentTimeMillis()).coerceAtLeast(0) + 60_000)
                    }),
                ),
                contentHash = end.hashCode(),
            )
        )
        timerDone = Runnable {
            IslandRepository.remove(TIMER_ID)
            vibrate(ctx, longArrayOf(0, 300, 150, 300, 150, 300))
            val now = System.currentTimeMillis()
            IslandRepository.upsert(
                IslandActivity(
                    id = TIMER_DONE_ID, kind = Kind.SYSTEM, packageName = ctx.packageName,
                    title = "Timer done", text = "Your ${formatClock(ms)} timer has finished",
                    icon = ContextCompat.getDrawable(ctx, R.drawable.ic_timer), tintIcon = true,
                    accent = Colors.ORANGE, compactText = "Done",
                    actions = listOf(
                        IslandAction("Dismiss", onClick = { IslandRepository.remove(TIMER_DONE_ID) }),
                        IslandAction("Repeat", style = ActionStyle.POSITIVE, onClick = { startTimer(ctx, ms) }),
                    ),
                    peek = true, peekMs = 8000, expiresAt = now + 10_000, contentHash = now.toInt(),
                )
            )
        }.also { handler.postDelayed(it, ms) }
    }

    fun cancelTimer() {
        timerDone?.let(handler::removeCallbacks)
        timerDone = null
        IslandRepository.remove(TIMER_ID)
    }

    fun startStopwatch(context: Context) {
        val ctx = context.applicationContext
        val start = System.currentTimeMillis()
        IslandRepository.upsert(
            IslandActivity(
                id = STOPWATCH_ID, kind = Kind.TIMER, packageName = ctx.packageName,
                title = "Stopwatch", text = "Running",
                icon = ContextCompat.getDrawable(ctx, R.drawable.ic_stopwatch), tintIcon = true,
                accent = Colors.ORANGE, chronoBase = start,
                actions = listOf(
                    IslandAction("Stop", style = ActionStyle.NEGATIVE, onClick = { IslandRepository.remove(STOPWATCH_ID) }),
                    IslandAction("Restart", onClick = { startStopwatch(ctx) }),
                ),
                contentHash = start.hashCode(),
            )
        )
    }

    fun pinNote(context: Context, text: String) {
        val ctx = context.applicationContext
        val prefs = Prefs(ctx)
        prefs.note = text.trim()
        if (text.isBlank()) { IslandRepository.remove(NOTE_ID); return }
        IslandRepository.upsert(
            IslandActivity(
                id = NOTE_ID, kind = Kind.NOTE, packageName = ctx.packageName,
                title = "Pinned note", text = text.trim(),
                icon = ContextCompat.getDrawable(ctx, R.drawable.ic_note), tintIcon = true,
                accent = Colors.YELLOW, compactText = text.trim(),
                actions = listOf(
                    IslandAction("Unpin", style = ActionStyle.NEGATIVE, onClick = { pinNote(ctx, "") }),
                    IslandAction("Copy", onClick = {
                        ctx.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("note", text.trim()))
                    }),
                    IslandAction("Edit", onInput = { pinNote(ctx, it) }, inputHint = "Edit note…"),
                ),
                contentHash = text.hashCode(),
            )
        )
    }

    fun restore(context: Context) {
        val note = Prefs(context).note
        if (note.isNotBlank()) pinNote(context, note)
    }

    fun toggleTorch(context: Context) {
        val cm = context.getSystemService(CameraManager::class.java) ?: return
        try {
            val id = cm.cameraIdList.firstOrNull {
                val ch = cm.getCameraCharacteristics(it)
                ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: return
            cm.setTorchMode(id, !IslandRepository.torchOn.value)
        } catch (_: Exception) {
        }
    }

    fun vibrate(context: Context, pattern: LongArray) {
        try {
            val v = context.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (_: Exception) {
        }
    }

    fun tick(context: Context) {
        try {
            val v = context.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
            v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        } catch (_: Exception) {
        }
    }
}
