package com.ajit.dynamicisland

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.ajit.dynamicisland.core.IslandRepository
import com.ajit.dynamicisland.core.Prefs
import com.ajit.dynamicisland.core.modules.ModuleRegistry
import com.ajit.dynamicisland.screens.IslandTab
import com.ajit.dynamicisland.screens.ModulesTab
import com.ajit.dynamicisland.screens.SettingsTab
import com.ajit.dynamicisland.screens.Setup
import com.ajit.dynamicisland.screens.Tab
import com.ajit.dynamicisland.ui.hig.Hig
import com.ajit.dynamicisland.ui.hig.TabBar
import kotlinx.coroutines.launch

/** Three tabs (Apple HIG): Island · Modules · Settings. First run goes through OnboardingActivity. */
class MainActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_TAB = "tab"
        const val EXTRA_MODULE = "module"
        const val TAB_ISLAND = 0; const val TAB_MODULES = 1; const val TAB_SETTINGS = 2
    }

    private lateinit var hig: Hig
    private lateinit var content: FrameLayout
    private lateinit var bar: TabBar
    private lateinit var island: IslandTab
    private lateinit var modules: ModulesTab
    private lateinit var settings: SettingsTab
    private val tabs = mutableListOf<Tab>()
    private var current = TAB_ISLAND

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hig = Hig(this)
        // First run: existing users who already granted everything skip onboarding.
        if (!OnboardingActivity.done(this)) {
            if (Setup.ready(this)) Prefs(this).set(OnboardingActivity.PREF_DONE, true)
            else startActivity(Intent(this, OnboardingActivity::class.java))
        }
        ModuleRegistry.start(this)
        island = IslandTab(this, hig); modules = ModulesTab(this, hig); settings = SettingsTab(this, hig)
        tabs += listOf(island, modules, settings)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(hig.bg) }
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        bar = TabBar(hig, listOf("Island" to R.drawable.ic_tab_island, "Modules" to R.drawable.ic_tab_modules, "Settings" to R.drawable.ic_tab_settings)) { show(it) }
        root.addView(bar, hig.mp())
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, i ->
            val b = i.getInsets(WindowInsetsCompat.Type.systemBars())
            content.setPadding(0, b.top, 0, 0); bar.setPadding(0, 0, 0, b.bottom); i
        }
        current = savedInstanceState?.getInt(EXTRA_TAB) ?: intent.getIntExtra(EXTRA_TAB, TAB_ISLAND)
        intent.getStringExtra(EXTRA_MODULE)?.let { modules.focusPkg = it }
        show(current)

        // Keep the live preview and module cards current.
        lifecycleScope.launch { IslandRepository.activities.collect { if (current == TAB_ISLAND) island.refreshPreview() } }
        lifecycleScope.launch { ModuleRegistry.modules.collect { if (current == TAB_MODULES) modules.refresh() } }
        lifecycleScope.launch { ModuleRegistry.tools.collect { if (current == TAB_MODULES) modules.refresh() } }
        lifecycleScope.launch { ModuleRegistry.tiles.collect { if (current == TAB_ISLAND) island.refresh() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_MODULE)?.let { modules.focusPkg = it }
        if (intent.hasExtra(EXTRA_TAB)) show(intent.getIntExtra(EXTRA_TAB, current))
    }

    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); outState.putInt(EXTRA_TAB, current) }

    override fun onResume() {
        super.onResume()
        tabs[current].refresh()
        if (!Setup.ready(this)) bar.badge(TAB_ISLAND, "!")
    }

    private fun show(i: Int) {
        current = i.coerceIn(0, 2)
        content.removeAllViews()
        content.addView(tabs[current].root)
        bar.select(current)
        tabs[current].refresh()
    }

    /** Called by the Island tab once it knows the unread count. */
    fun setIslandBadge(unread: Int) = bar.badge(TAB_ISLAND, island.badge(unread))
}

/** Phone maker detection for keep-alive guidance. Component names are best-effort; we fall back to App info. */
enum class Brand(val label: String, val slug: String, val autostart: ComponentName?) {
    XIAOMI("Xiaomi / Redmi / POCO (HyperOS, MIUI)", "xiaomi", null),
    SAMSUNG("Samsung (One UI)", "samsung", ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity")),
    ONEPLUS("OnePlus (OxygenOS)", "oneplus", ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")),
    OPPO("OPPO / realme (ColorOS, realme UI)", "oppo", ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")),
    VIVO("vivo / iQOO (Funtouch OS, OriginOS)", "vivo", ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
    HUAWEI("Huawei / Honor", "huawei", null),
    MOTOROLA("Motorola", "motorola", null),
    GOOGLE("Google Pixel", "google", null),
    OTHER("Android", "", null);

    companion object {
        fun detect(): Brand = when (android.os.Build.MANUFACTURER.lowercase()) {
            "xiaomi", "redmi", "poco" -> XIAOMI
            "samsung" -> SAMSUNG
            "oneplus" -> ONEPLUS
            "oppo", "realme" -> OPPO
            "vivo", "iqoo" -> VIVO
            "huawei", "honor" -> HUAWEI
            "motorola" -> MOTOROLA
            "google" -> GOOGLE
            else -> OTHER
        }
    }
}
