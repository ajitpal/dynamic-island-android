package com.ajit.dynamicisland.module.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguagePlanTest {
    private fun plan(l: Lang, a: AndroidHindi, spReady: Boolean = false, spInstalled: Boolean = spReady, prefer: Boolean = false) =
        Plan.choose(l, a, spReady, spInstalled, prefer)

    @Test fun englishAlwaysUsesMoonshine() {
        assertEquals(Plan.MoonshineLive, plan(Lang.EN, AndroidHindi.UNSUPPORTED))
        assertEquals(Plan.MoonshineLive, plan(Lang.EN, AndroidHindi.READY, spReady = true, prefer = true))
    }

    @Test fun hindiPrefersAndroidLiveThenSpeechPack() {
        assertEquals(Plan.AndroidLive, plan(Lang.HI, AndroidHindi.READY))
        assertEquals(Plan.AndroidLive, plan(Lang.HINGLISH, AndroidHindi.READY, spReady = true))
        assertEquals(Plan.SpeechPackAfter, plan(Lang.HI, AndroidHindi.UNSUPPORTED, spReady = true))
        assertEquals(Plan.SpeechPackAfter, plan(Lang.HI, AndroidHindi.DOWNLOADABLE, spReady = true))
    }

    @Test fun personCanChooseSpeechPackOverAndroid() {
        assertEquals(Plan.SpeechPackAfter, plan(Lang.HI, AndroidHindi.READY, spReady = true, prefer = true))
        // …but only when it's actually ready.
        assertEquals(Plan.AndroidLive, plan(Lang.HI, AndroidHindi.READY, spReady = false, spInstalled = true, prefer = true))
    }

    @Test fun explainsWhatToSetUp() {
        assertEquals(Plan.NeedsSetup(androidCanDownload = true, speechPackInstalled = false), plan(Lang.HI, AndroidHindi.DOWNLOADABLE))
        assertEquals(Plan.NeedsSetup(androidCanDownload = false, speechPackInstalled = true), plan(Lang.HI, AndroidHindi.UNSUPPORTED, spReady = false, spInstalled = true))
        assertEquals(Plan.NeedsSetup(androidCanDownload = false, speechPackInstalled = false), plan(Lang.HI, AndroidHindi.UNKNOWN))
    }

    @Test fun findsHindiAndHinglishToDos() {
        val lines = listOf(
            Line(0, "मैं कल तक रिपोर्ट भेज दूंगा।"),
            Line(5000, "बजट पर बात हुई और सब सहमत थे"),
            Line(9000, "Rahul ko presentation kal tak ready karni hai"),
            Line(14000, "हमें क्लाइंट को डेडलाइन के बारे में बताना है"),
        )
        val items = Notes.actionItems(lines).map { it.text }
        assertTrue(items.any { it.startsWith("मैं कल तक") })
        assertTrue(items.any { it.startsWith("Rahul ko") })
        assertTrue(items.any { it.contains("डेडलाइन") })
        assertTrue(items.none { it.startsWith("बजट") })
    }

    @Test fun splitsHindiSentencesOnDanda() {
        assertEquals(listOf("पहला वाक्य।", "दूसरा वाक्य।"), Notes.splitSentences("पहला वाक्य। दूसरा वाक्य।"))
    }
}
