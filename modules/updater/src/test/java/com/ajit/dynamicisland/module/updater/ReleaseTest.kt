package com.ajit.dynamicisland.module.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseTest {
    private val sha = "a".repeat(64)
    private fun json(apps: String) = """{"schema":1,"release":"1.10.0","tag":"v1.10.0","notes":"## What's new\n- Updater","apps":[$apps]}"""
    private fun app(pkg: String, code: Int, file: String = "X-v1.apk") =
        """{"package":"$pkg","name":"$pkg","versionCode":$code,"versionName":"1.$code","file":"$file","sha256":"$sha","size":10}"""

    @Test fun parsesAndBuildsDownloadUrl() {
        val r = Release.parse(json(app("com.ajit.dynamicisland", 17, "DynamicIsland-v1.10.0.apk")))
        assertEquals("1.10.0", r.version)
        assertEquals("https://github.com/ajitpal/dynamic-island-android/releases/download/v1.10.0/DynamicIsland-v1.10.0.apk", r.downloadUrl(r.apps.single()))
    }

    @Test fun onlyInstalledAndOlderAppsAreOffered() {
        val r = Release.parse(json(listOf(app("com.ajit.dynamicisland", 17), app("m.voice", 5), app("m.calendar", 2), app("m.notinstalled", 9)).joinToString(",")))
        val installed = mapOf("com.ajit.dynamicisland" to (16L to "1.9.2"), "m.voice" to (4L to "1.3.0"), "m.calendar" to (2L to "1.1.0"))
        val ups = Repo.pending(r) { installed[it] }
        assertEquals(listOf("m.voice", "com.ajit.dynamicisland"), ups.map { it.app.pkg })
        assertEquals("1.9.2", ups.last().installedVersion)
    }

    @Test fun updaterUpdatesItselfLast() {
        val r = Release.parse(json(listOf(app(Repo.UPDATER_PACKAGE, 2), app(Repo.ISLAND_PACKAGE, 17), app("m.ai", 5)).joinToString(",")))
        val ups = Repo.pending(r) { 1L to "old" }
        assertEquals(listOf("m.ai", Repo.ISLAND_PACKAGE, Repo.UPDATER_PACKAGE), ups.map { it.app.pkg })
    }

    @Test fun rejectsUnsafeEntries() {
        assertThrows(IllegalArgumentException::class.java) { Release.parse(json(app("p", 1, "../evil.apk"))) }
        assertThrows(IllegalArgumentException::class.java) { Release.parse(json(app("p", 1).replace(sha, "nothex"))) }
        assertThrows(IllegalArgumentException::class.java) { Release.parse(json(app("p", 1)).replace("\"schema\":1", "\"schema\":2")) }
        assertTrue(Release.parse(json("")).apps.isEmpty())
    }

    /** The real file tools/collect_release.py wrote for 1.10.0: the release pipeline and the Updater agree. */
    @Test fun parsesTheRealReleaseFile() {
        val json = javaClass.classLoader!!.getResource("update-1.10.0.json")!!.readText()
        val r = Release.parse(json)
        assertEquals("v1.10.0", r.tag)
        assertEquals(6, r.apps.size)
        assertTrue(r.apps.any { it.pkg == Repo.UPDATER_PACKAGE })
        assertTrue(r.notes.contains("Updater"))
        val ups = Repo.pending(r) { pkg -> if (pkg == Repo.ISLAND_PACKAGE) 16L to "1.9.2" else null }
        assertEquals(listOf("DynamicIsland-v1.10.0.apk"), ups.map { it.app.file })
    }
}
