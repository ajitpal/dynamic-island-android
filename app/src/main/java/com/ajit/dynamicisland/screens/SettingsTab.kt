package com.ajit.dynamicisland.screens

import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.ajit.dynamicisland.OnboardingActivity
import com.ajit.dynamicisland.PageActivity
import com.ajit.dynamicisland.R
import com.ajit.dynamicisland.core.Prefs
import com.ajit.dynamicisland.ui.Themes
import com.ajit.dynamicisland.ui.hig.Hig

/** Tab 3: every setting, grouped like iPhone Settings; each row opens its own page. */
class SettingsTab(private val a: AppCompatActivity, private val hig: Hig) : Tab {
    private val prefs = Prefs(a)
    private val page = hig.page("Settings")
    override val root: View get() = page.root
    private val dynamic = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }

    init { page.column.addView(dynamic, hig.mp()) }

    private fun open(key: String) = PageActivity.open(a, key)
    private fun web(url: String) = Setup.tryStart(a, Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    override fun refresh() {
        dynamic.removeAllViews()
        val island = hig.section(dynamic, "Island")
        island.add(hig.nav("Look and fit", R.drawable.ic_palette, Hig.Tile.PURPLE, value = Themes.byId(prefs.theme).name) { open(PageActivity.LOOK) })
        island.add(hig.nav("What shows", R.drawable.ic_eye, Hig.Tile.BLUE) { open(PageActivity.SHOWS) })
        island.add(hig.nav("Behaviour", R.drawable.ic_hand, Hig.Tile.ORANGE) { open(PageActivity.BEHAVIOUR) })
        island.add(hig.nav("Apps", R.drawable.ic_apps, Hig.Tile.INDIGO, value = appsValue()) { open(PageActivity.APPS) })
        island.add(hig.nav("Island tools", R.drawable.ic_tab_modules, Hig.Tile.TEAL, value = prefs.tools.size.toString()) { open(PageActivity.TOOLS) })

        val notif = hig.section(dynamic, "Notifications")
        notif.add(hig.nav("Inbox", R.drawable.ic_inbox, Hig.Tile.BLUE, value = if (prefs.inboxEnabled) "${prefs.inboxDays} days" else "Off") { open(PageActivity.INBOX) })
        notif.add(hig.nav("Smart Inbox", R.drawable.ic_spark, Hig.Tile.PURPLE, value = if (prefs.smartDigest || prefs.smartReplies) "On" else "Off") { open(PageActivity.SMART) })
        notif.add(hig.nav("Duplicate pop-ups", R.drawable.ic_bell_off, Hig.Tile.RED) { open(PageActivity.POPUPS) })

        val help = hig.section(dynamic, "Setup and help")
        help.add(hig.nav("Permissions", R.drawable.ic_shield, Hig.Tile.GREEN, value = if (Setup.ready(a)) "On" else "Needs setup") {
            a.startActivity(Intent(a, OnboardingActivity::class.java).putExtra(OnboardingActivity.EXTRA_STEP, 1))
        })
        help.add(hig.nav("Keep it running", R.drawable.ic_battery, Hig.Tile.GREEN) {
            a.startActivity(Intent(a, OnboardingActivity::class.java).putExtra(OnboardingActivity.EXTRA_STEP, 2))
        })
        help.add(hig.nav("Gestures", R.drawable.ic_hand, Hig.Tile.GRAY) { open(PageActivity.GESTURES) })
        help.add(hig.nav("Diagnostics", R.drawable.ic_info, Hig.Tile.GRAY) { open(PageActivity.DIAGNOSTICS) })

        val about = hig.section(dynamic, "About",
            "v${a.packageManager.getPackageInfo(a.packageName, 0).versionName} · Open source (MIT) · Android 12+ phones with a punch-hole or notch")
        about.add(hig.nav("Privacy", R.drawable.ic_shield, Hig.Tile.BLUE) { web("https://github.com/ajitpal/dynamic-island-android/blob/main/PRIVACY.md") })
        about.add(hig.nav("What's new", R.drawable.ic_stack, Hig.Tile.PINK) { web("https://ajitpal.github.io/dynamic-island-android/changelog.html") })
        about.add(hig.nav("Source code and help", R.drawable.ic_code, Hig.Tile.BLACK) { web("https://github.com/ajitpal/dynamic-island-android") })
    }

    private fun appsValue(): String {
        val seen = prefs.seenApps().size
        val blocked = prefs.blockedApps().size
        return if (seen == 0) "" else "${seen - blocked} of $seen"
    }
}
