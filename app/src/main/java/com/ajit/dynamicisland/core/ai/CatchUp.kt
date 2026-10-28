package com.ajit.dynamicisland.core.ai

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.ajit.dynamicisland.core.Prefs
import com.ajit.dynamicisland.core.inbox.InboxEntry
import com.ajit.dynamicisland.core.inbox.InboxFilter
import com.ajit.dynamicisland.core.inbox.InboxStore
import com.ajit.dynamicisland.sdk.ai.AiClient
import com.ajit.dynamicisland.sdk.ai.AiContract

/**
 * One AI request at a time for the whole app. Catch me up and reply suggestions both take the gate;
 * whoever finds it taken tells the person what's running instead of starting a second request.
 */
object AiGate {
    @Volatile var holder: String? = null
        private set

    @Synchronized fun tryAcquire(label: String): Boolean {
        if (holder != null) return false
        holder = label; return true
    }

    @Synchronized fun release(label: String) { if (holder == label) holder = null }

    /** Message for someone who asked for AI while something else is running. */
    fun busyMessage(): String = "${holder ?: "Another AI request"} is running. Try again when it's finished."
}

/**
 * "Catch me up": summarises unread (or the last day's) notifications with the on-device AI Engine.
 * The Inbox sheet and the island card both draw [state]; there is only ever one catch-up running.
 */
object CatchUp {
    const val LABEL = "Catch me up"

    enum class Step { INBOX, WAITING, LOADING, READING, WRITING }

    sealed class State {
        object Idle : State()
        /** [text] is the summary so far while writing. [ahead]/[busyWith] while waiting for the engine. */
        data class Working(
            val step: Step, val count: Int, val startedAt: Long,
            val model: String? = null, val text: String? = null, val ahead: Int = 0, val busyWith: String? = null,
        ) : State()
        data class Done(val text: String, val count: Int, val at: Long, val model: String?, val unchanged: Boolean = false) : State()
        /** Nothing new to summarise. */
        object Empty : State()
        data class Failed(val reason: Reason, val title: String, val message: String) : State()
    }

    enum class Reason { OFF, NO_ENGINE, NO_MODEL, BUSY, ERROR }

    enum class Start { STARTED, ALREADY_RUNNING, BLOCKED }

    /** The engine, swappable in tests. */
    interface Runner {
        fun generate(system: String, prompt: String, maxTokens: Int, temperature: Float,
                     onProgress: (Bundle) -> Unit, onPartial: (String) -> Unit, onDone: (String) -> Unit, onError: (Int, String) -> Unit): String
        fun cancel(id: String)
    }

    private class EngineRunner(ctx: Context) : Runner {
        private val client = AiClient(ctx)
        override fun generate(system: String, prompt: String, maxTokens: Int, temperature: Float,
                              onProgress: (Bundle) -> Unit, onPartial: (String) -> Unit, onDone: (String) -> Unit, onError: (Int, String) -> Unit) =
            client.generate(system, prompt, maxTokens, temperature, onPartial = onPartial, onDone = onDone, onError = onError,
                wait = false, onProgress = onProgress)
        override fun cancel(id: String) = client.cancel(id)
    }

    fun interface Listener { fun onState(s: State) }

    /** Tests replace these. */
    @Volatile var runnerFactory: (Context) -> Runner = { EngineRunner(it) }
    @Volatile var engineAvailable: (Context) -> Boolean = { AiClient.isTrusted(it) }
    @Volatile var loadEntries: (Context) -> List<InboxEntry> = { ctx ->
        val dayAgo = System.currentTimeMillis() - 24 * 3600_000L
        InboxStore.query(ctx, InboxFilter.UNREAD, null, 80).ifEmpty {
            InboxStore.query(ctx, InboxFilter.ALL, null, 80).filter { it.posted >= dayAgo }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<Listener>()
    private var runner: Runner? = null
    private var requestId: String? = null
    /** Fingerprint of the notifications behind the last summary, to reuse it when nothing changed. */
    private var lastKey: String? = null
    private var lastDone: State.Done? = null

    var state: State = State.Idle
        private set

    val running get() = state is State.Working

    /** Main thread. Listeners get the current state at once, then every change and a tick each second while working. */
    fun addListener(l: Listener) { listeners += l; l.onState(state) }
    fun removeListener(l: Listener) { listeners -= l }

    private fun set(s: State) {
        state = s
        listeners.toList().forEach { it.onState(s) }
        main.removeCallbacks(tick)
        if (s is State.Working) main.postDelayed(tick, 1000)
    }

    private val tick = object : Runnable {
        override fun run() { if (state is State.Working) { listeners.toList().forEach { it.onState(state) }; main.postDelayed(this, 1000) } }
    }

    /** Main thread. */
    fun start(ctx: Context): Start {
        val app = ctx.applicationContext
        if (running) return Start.ALREADY_RUNNING
        if (!Prefs(app).smartDigest) {
            set(State.Failed(Reason.OFF, "Catch Me Up Is Off", "Turn it on in Settings → Smart Inbox. It summarises your notifications with the AI Engine on this phone."))
            return Start.STARTED
        }
        if (!engineAvailable(app)) {
            set(State.Failed(Reason.NO_ENGINE, "AI Engine Needed", "Install the AI Engine module to summarise your notifications on this phone."))
            return Start.STARTED
        }
        if (!AiGate.tryAcquire(LABEL)) {
            set(State.Failed(Reason.BUSY, "AI Is Busy", AiGate.busyMessage()))
            return Start.BLOCKED
        }
        val startedAt = SystemClock.elapsedRealtime()
        set(State.Working(Step.INBOX, 0, startedAt))
        Thread {
            val entries = try { loadEntries(app) } catch (e: Exception) { emptyList() }
            main.post { if (state is State.Working) summarise(app, entries, startedAt) }
        }.start()
        return Start.STARTED
    }

    private fun summarise(ctx: Context, entries: List<InboxEntry>, startedAt: Long) {
        if (entries.isEmpty()) { finish(State.Empty); return }
        val key = entries.joinToString(",") { "${it.id}:${it.read}" }
        val prev = lastDone
        if (key == lastKey && prev != null) { finish(prev.copy(unchanged = true)); return }
        val r = runnerFactory(ctx).also { runner = it }
        val n = entries.size
        var model: String? = null
        set(State.Working(Step.READING, n, startedAt))
        requestId = r.generate(
            SmartInbox.DIGEST_SYSTEM, SmartInbox.digestPrompt(entries), SmartInbox.DIGEST_TOKENS, 0.2f,
            onProgress = { p ->
                val w = state as? State.Working ?: return@generate
                model = p.getString(AiContract.P_MODEL) ?: model
                val step = when (p.getString(AiContract.P_STAGE)) {
                    AiContract.STAGE_QUEUED -> Step.WAITING
                    AiContract.STAGE_LOADING -> Step.LOADING
                    AiContract.STAGE_WRITING -> Step.WRITING
                    else -> Step.READING
                }
                set(w.copy(step = step, model = model, ahead = p.getInt(AiContract.P_AHEAD, 0), busyWith = p.getString(AiContract.P_BUSY_WITH)))
            },
            onPartial = { t ->
                val w = state as? State.Working ?: return@generate
                if (t.isNotBlank()) set(w.copy(step = Step.WRITING, text = t))
            },
            onDone = { t ->
                if (t.isBlank()) { finish(State.Failed(Reason.ERROR, "Couldn't Catch Up", "The AI Engine returned nothing. Try again.")); return@generate }
                lastKey = key
                val d = State.Done(t, n, System.currentTimeMillis(), model).also { lastDone = it }
                finish(d)
            },
            onError = { code, msg ->
                when (code) {
                    AiContract.E_CANCELLED -> finish(State.Idle)
                    AiContract.E_BUSY -> finish(State.Failed(Reason.BUSY, "AI Engine Is Busy", msg))
                    AiContract.E_NO_MODEL -> finish(State.Failed(Reason.NO_MODEL, "No AI Model Yet", "Open AI Engine and download a model. After that, everything works offline."))
                    AiContract.E_UNAVAILABLE -> finish(State.Failed(Reason.NO_ENGINE, "AI Engine Didn't Respond", msg))
                    else -> finish(State.Failed(Reason.ERROR, "Couldn't Catch Up", msg))
                }
            },
        )
    }

    private fun finish(s: State) {
        requestId = null; runner = null
        AiGate.release(LABEL)
        set(s)
    }

    /** Stop a running catch-up. Main thread. */
    fun cancel() {
        val id = requestId
        val r = runner
        if (id != null && r != null) r.cancel(id)
        if (running) finish(State.Idle)
    }

    /** Forget the result (after "Done" or "Mark All as Read"). Main thread. */
    fun dismiss() { if (!running) set(State.Idle) }

    /** Tests only. */
    fun resetForTest() { cancel(); lastKey = null; lastDone = null; listeners.clear(); set(State.Idle) }

    /** One line for what's happening now, for the island and for TalkBack. */
    fun stepText(w: State.Working): String = when (w.step) {
        Step.INBOX -> "Gathering your notifications"
        Step.WAITING -> if (w.busyWith != null) "Waiting for ${w.busyWith} to finish" else "Waiting for AI Engine"
        Step.LOADING -> "Loading ${w.model ?: "the AI model"}"
        Step.READING -> "Reading ${w.count} notification${if (w.count == 1) "" else "s"}"
        Step.WRITING -> "Writing the summary"
    }

    fun elapsed(w: State.Working) = ((SystemClock.elapsedRealtime() - w.startedAt) / 1000).toInt()
}
