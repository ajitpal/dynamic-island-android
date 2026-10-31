package com.ajit.dynamicisland.core.modules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleUpdatesTest {
    @Test fun readsTheUpdatersList() {
        val l = ModuleUpdates.parse("""[{"package":"com.ajit.dynamicisland.module.voice","name":"Meeting Notes","versionName":"1.5.0"},{"name":"no package"}]""")
        assertEquals(1, l.size)
        assertEquals(ModuleUpdates.Pending("com.ajit.dynamicisland.module.voice", "Meeting Notes", "1.5.0"), l[0])
    }

    @Test fun emptyListIsFine() = assertTrue(ModuleUpdates.parse("[]").isEmpty())
}
