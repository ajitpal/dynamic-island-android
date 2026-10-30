package com.ajit.dynamicisland.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.ajit.dynamicisland.R

/** Fake activities so you can see every island state without waiting for a real event. */
object Demo {
    private val handler = Handler(Looper.getMainLooper())

    fun message(context: Context) {
        val ctx = context.applicationContext
        val now = System.currentTimeMillis()
        val peekMs = Prefs(ctx).peekSeconds * 1000L
        IslandRepository.upsert(
            IslandActivity(
                id = "demo:msg", kind = Kind.MESSAGE, packageName = ctx.packageName,
                title = "Priya", text = "Are we still on for dinner at 7? I booked the table 🍕",
                accent = Colors.GREEN, compactText = "Priya", appLabel = "Demo chat",
                actions = listOf(
                    IslandAction("Reply", inputHint = "Reply to Priya…", onInput = {
                        Toast.makeText(ctx, "Demo reply: $it", Toast.LENGTH_SHORT).show()
                        IslandRepository.remove("demo:msg")
                    }),
                    IslandAction("Mark read", onClick = { IslandRepository.remove("demo:msg") }),
                ),
                peek = true, peekMs = peekMs, expiresAt = now + peekMs + 6000, contentHash = now.toInt(),
            )
        )
    }

    fun incomingCall(context: Context) {
        val ctx = context.applicationContext
        val phone = ContextCompat.getDrawable(ctx, R.drawable.ic_phone)
        IslandRepository.upsert(
            IslandActivity(
                id = "demo:call", kind = Kind.CALL, packageName = ctx.packageName,
                title = "Rahul", text = "Incoming call", icon = phone, tintIcon = true,
                accent = Colors.GREEN, compactText = "Call", incoming = true,
                actions = listOf(
                    IslandAction("Decline", style = ActionStyle.NEGATIVE, onClick = { IslandRepository.remove("demo:call") }),
                    IslandAction("Answer", style = ActionStyle.POSITIVE, onClick = { ongoingCall(ctx) }),
                ),
                peek = true, peekMs = 0, contentHash = 1,
            )
        )
    }

    private fun ongoingCall(ctx: Context) {
        IslandRepository.upsert(
            IslandActivity(
                id = "demo:call", kind = Kind.CALL, packageName = ctx.packageName,
                title = "Rahul", text = "On call", icon = ContextCompat.getDrawable(ctx, R.drawable.ic_phone),
                tintIcon = true, accent = Colors.GREEN, chronoBase = System.currentTimeMillis(),
                actions = listOf(IslandAction("End", style = ActionStyle.NEGATIVE, onClick = { IslandRepository.remove("demo:call") })),
                contentHash = 2,
            )
        )
    }

    fun download(context: Context) {
        val ctx = context.applicationContext
        val icon = ContextCompat.getDrawable(ctx, R.drawable.ic_download)
        var p = 0
        val step = object : Runnable {
            override fun run() {
                if (p > 100) {
                    IslandRepository.remove("demo:dl")
                    val now = System.currentTimeMillis()
                    IslandRepository.upsert(
                        IslandActivity(
                            id = "sys", kind = Kind.SYSTEM, packageName = ctx.packageName, title = "Download complete",
                            text = "holiday-photos.zip", icon = icon, tintIcon = true, accent = Colors.GREEN,
                            compactText = "Done", expiresAt = now + 2500, contentHash = now.toInt(),
                        )
                    )
                    return
                }
                IslandRepository.upsert(
                    IslandActivity(
                        id = "demo:dl", kind = Kind.PROGRESS, packageName = ctx.packageName,
                        title = "Downloading", text = "holiday-photos.zip · $p%", icon = icon, tintIcon = true,
                        accent = Colors.BLUE, progress = p / 100f,
                        actions = listOf(IslandAction("Cancel", style = ActionStyle.NEGATIVE, onClick = {
                            handler.removeCallbacks(this); IslandRepository.remove("demo:dl")
                        })),
                        contentHash = p,
                    )
                )
                p += 2
                handler.postDelayed(this, 200)
            }
        }
        handler.post(step)
    }

    fun charging(context: Context) {
        val ctx = context.applicationContext
        val now = System.currentTimeMillis()
        IslandRepository.upsert(
            IslandActivity(
                id = "sys", kind = Kind.SYSTEM, packageName = ctx.packageName, title = "Charging",
                text = "Battery 64%", icon = ContextCompat.getDrawable(ctx, R.drawable.ic_bolt), tintIcon = true,
                accent = Colors.GREEN, compactText = "64%", expiresAt = now + 2800, contentHash = now.toInt(),
            )
        )
    }

    fun clear() {
        IslandRepository.removeWhere { it.id.startsWith("demo:") }
    }

    private fun sys(ctx: Context, id: String, kind: Kind, title: String, text: String, compact: String, icon: Int, accent: Int,
                    ms: Long, progress: Float = -1f, peek: Boolean = false, chrono: Long = 0L, actions: List<IslandAction> = emptyList()) {
        val now = System.currentTimeMillis()
        IslandRepository.upsert(
            IslandActivity(
                id = id, kind = kind, packageName = ctx.packageName, title = title, text = text,
                icon = ContextCompat.getDrawable(ctx, icon), tintIcon = true, accent = accent, compactText = compact,
                progress = progress, chronoBase = chrono, actions = actions, peek = peek, peekMs = 3500,
                expiresAt = if (ms > 0) now + ms else 0L, contentHash = now.toInt(),
            )
        )
    }

    fun payment(context: Context) = sys(context.applicationContext, "demo:pay", Kind.SYSTEM, "Paid to Blinkit",
        "₹349.00 · UPI payment successful", "₹349", R.drawable.ic_check, Colors.GREEN, 5000, peek = true)

    fun earbuds(context: Context) = sys(context.applicationContext, "sys", Kind.SYSTEM, "Redmi Buds 6",
        "Connected · battery 82%", "82%", R.drawable.ic_headphones, Colors.GREEN, 3200, progress = 0.82f)

    fun unlock(context: Context) = sys(context.applicationContext, "sys", Kind.SYSTEM, "Unlocked", "Welcome back",
        "", R.drawable.ic_lock_open, Colors.GREEN, 1300)

    fun recording(context: Context) {
        val ctx = context.applicationContext
        sys(ctx, "demo:rec", Kind.TIMER, "Screen recording", "Recording your screen", "REC", R.drawable.ic_record,
            Colors.RED, 0, chrono = System.currentTimeMillis(),
            actions = listOf(IslandAction("Stop", style = ActionStyle.NEGATIVE, onClick = { IslandRepository.remove("demo:rec") })))
    }

    fun liveScore(context: Context) {
        val ctx = context.applicationContext
        sys(ctx, "demo:live", Kind.LIVE, "IND 287/4 (42.3)", "vs AUS · Kohli 98*(91) · Need 34 off 45", "IND 287/4",
            R.drawable.ic_live, Colors.BLUE, 0, progress = 42.5f / 50f,
            actions = listOf(IslandAction("Hide", onClick = { IslandRepository.remove("demo:live") })))
    }

    /** 2.0: three activities at once. The island shows one, the circle the next, with a "+1" badge. */
    fun threeAtOnce(context: Context) {
        val ctx = context.applicationContext
        liveScore(ctx)
        sys(ctx, "demo:delivery", Kind.LIVE, "Order on the way", "Arriving in 12 min · Rider is 2 km away", "12 min",
            R.drawable.ic_live, Colors.ORANGE, 0, progress = 0.6f,
            actions = listOf(IslandAction("Hide", onClick = { IslandRepository.remove("demo:delivery") })))
        recording(ctx)
    }
}
