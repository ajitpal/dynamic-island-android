package com.ajit.dynamicisland.core.modules

import android.content.ComponentName
import android.content.Context
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ajit.dynamicisland.sdk.IslandContract
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 1.9.0 regression: the Modules tab rescanned on every refresh and refreshed on every change, and each
 * rescan looked like a change (new icon objects), so the app stopped responding. A rescan with nothing
 * new installed must not publish a new module list.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ModuleRegistryLoopTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val pkg = "com.example.islandmodule"

    @Before fun setUp() {
        ModuleRegistry.resetForTest()
        val app = ApplicationInfo().apply { packageName = pkg; name = "Example"; flags = ApplicationInfo.FLAG_INSTALLED }
        val svc = ServiceInfo().apply { packageName = pkg; name = "$pkg.Module"; exported = true; applicationInfo = app }
        val spm = shadowOf(ctx.packageManager)
        spm.installPackage(PackageInfo().apply { packageName = pkg; versionName = "1.0.0"; applicationInfo = app })
        spm.addOrUpdateService(svc)
        spm.addIntentFilterForService(ComponentName(pkg, svc.name), IntentFilter(IslandContract.ACTION_MODULE))
    }

    @After fun tearDown() = ModuleRegistry.resetForTest()

    @Test fun rescanWithoutChangesPublishesNothingNew() {
        ModuleRegistry.start(ctx)
        val first = ModuleRegistry.modules.value
        assertEquals(listOf(pkg), first.map { it.pkg })
        repeat(5) {
            // A real PackageManager returns a new Drawable for every getApplicationIcon call.
            shadowOf(ctx.packageManager).setApplicationIcon(pkg, android.graphics.drawable.ColorDrawable(it))
            ModuleRegistry.rescan()
        }
        // StateFlow only emits when the value is not equal, so the same instance means no emission.
        assertSame(first, ModuleRegistry.modules.value)
    }

    @Test fun iconIsReusedAcrossScans() {
        ModuleRegistry.start(ctx)
        val icon = ModuleRegistry.modules.value.single().icon
        shadowOf(ctx.packageManager).setApplicationIcon(pkg, android.graphics.drawable.ColorDrawable(1))
        ModuleRegistry.rescan()
        assertSame(icon, ModuleRegistry.modules.value.single().icon)
    }
}
