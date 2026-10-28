package com.ajit.dynamicisland.core.ai

import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ajit.dynamicisland.core.Prefs
import com.ajit.dynamicisland.core.inbox.InboxEntry
import com.ajit.dynamicisland.sdk.ai.AiContract
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** Catch Me Up: one request at a time, honest progress, clear messages instead of silence. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CatchUpTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    /** A fake engine that records requests and lets the test answer them. */
    private class Fake : CatchUp.Runner {
        var calls = 0
        var cancelled = 0
        lateinit var progress: (Bundle) -> Unit
        lateinit var partial: (String) -> Unit
        lateinit var done: (String) -> Unit
        lateinit var error: (Int, String) -> Unit
        override fun generate(system: String, prompt: String, maxTokens: Int, temperature: Float, onProgress: (Bundle) -> Unit,
                              onPartial: (String) -> Unit, onDone: (String) -> Unit, onError: (Int, String) -> Unit): String {
            calls++; progress = onProgress; partial = onPartial; done = onDone; error = onError; return "req$calls"
        }
        override fun cancel(id: String) { cancelled++ }
    }

    private val fake = Fake()
    private var entries = listOf(InboxEntry(1, "k", "p", "WhatsApp", "Priya", "Dinner at 7?", 0, false, "msg"))

    private fun idle() { Thread.sleep(50); ShadowLooper.idleMainLooper() }

    @Before fun setUp() {
        CatchUp.resetForTest()
        Prefs(ctx).set(Prefs.SMART_DIGEST, true)
        CatchUp.runnerFactory = { fake }
        CatchUp.engineAvailable = { true }
        CatchUp.loadEntries = { entries }
    }

    @After fun tearDown() = CatchUp.resetForTest()

    @Test fun secondTapShowsTheRunningOneAndNeverStartsAnother() {
        assertEquals(CatchUp.Start.STARTED, CatchUp.start(ctx)); idle()
        assertEquals(CatchUp.Start.ALREADY_RUNNING, CatchUp.start(ctx)); idle()
        assertEquals(1, fake.calls)
    }

    @Test fun showsEachStepFromTheEngine() {
        CatchUp.start(ctx); idle()
        assertEquals(CatchUp.Step.READING, (CatchUp.state as CatchUp.State.Working).step)
        fake.progress(Bundle().apply { putString(AiContract.P_STAGE, AiContract.STAGE_LOADING); putString(AiContract.P_MODEL, "Qwen3 1.7B") })
        val w = CatchUp.state as CatchUp.State.Working
        assertEquals(CatchUp.Step.LOADING, w.step); assertEquals("Loading Qwen3 1.7B", CatchUp.stepText(w))
        fake.partial("## Needs attention\n- Priya asks about dinner")
        assertEquals(CatchUp.Step.WRITING, (CatchUp.state as CatchUp.State.Working).step)
        fake.done("## Needs attention\n- Priya asks about dinner at 7")
        assertTrue(CatchUp.state is CatchUp.State.Done)
        assertNull("the gate is free again", AiGate.holder)
    }

    @Test fun busyEngineGivesAMessageNotSilence() {
        CatchUp.start(ctx); idle()
        fake.error(AiContract.E_BUSY, "AI Engine is busy with Meeting Notes. Try again when it's finished.")
        val f = CatchUp.state as CatchUp.State.Failed
        assertEquals(CatchUp.Reason.BUSY, f.reason)
        assertTrue(f.message.contains("Meeting Notes"))
        assertNull(AiGate.holder)
    }

    @Test fun refusesWhileReplySuggestionsRun() {
        assertTrue(AiGate.tryAcquire("Suggest replies"))
        assertEquals(CatchUp.Start.BLOCKED, CatchUp.start(ctx))
        val f = CatchUp.state as CatchUp.State.Failed
        assertTrue(f.message.startsWith("Suggest replies is running"))
        assertEquals(0, fake.calls)
        AiGate.release("Suggest replies")
    }

    @Test fun nothingNewReusesTheLastSummaryInstantly() {
        CatchUp.start(ctx); idle(); fake.done("- Priya asks about dinner"); CatchUp.dismiss()
        CatchUp.start(ctx); idle()
        val d = CatchUp.state as CatchUp.State.Done
        assertTrue(d.unchanged); assertEquals(1, fake.calls)
    }

    @Test fun cancelStopsTheRequestAndFreesTheGate() {
        CatchUp.start(ctx); idle()
        CatchUp.cancel()
        assertEquals(1, fake.cancelled)
        assertTrue(CatchUp.state is CatchUp.State.Idle)
        assertNull(AiGate.holder)
    }

    @Test fun offAndMissingEngineExplainThemselves() {
        Prefs(ctx).set(Prefs.SMART_DIGEST, false)
        CatchUp.start(ctx)
        assertEquals(CatchUp.Reason.OFF, (CatchUp.state as CatchUp.State.Failed).reason)
        Prefs(ctx).set(Prefs.SMART_DIGEST, true); CatchUp.engineAvailable = { false }
        CatchUp.start(ctx)
        assertEquals(CatchUp.Reason.NO_ENGINE, (CatchUp.state as CatchUp.State.Failed).reason)
        assertEquals(0, fake.calls)
    }

    @Test fun emptyInboxSaysAllCaughtUp() {
        entries = emptyList()
        CatchUp.start(ctx); idle()
        assertTrue(CatchUp.state is CatchUp.State.Empty)
        assertNull(AiGate.holder)
    }
}
