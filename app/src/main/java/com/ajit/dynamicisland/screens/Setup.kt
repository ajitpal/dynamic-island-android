package com.ajit.dynamicisland.screens

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ajit.dynamicisland.Brand
import com.ajit.dynamicisland.R
import com.ajit.dynamicisland.core.Prefs
import com.ajit.dynamicisland.service.IslandAccessibilityService
import com.ajit.dynamicisland.service.IslandNotificationListener

/** Permission checks and the system screens the app sends people to. Shared by onboarding and Settings. */
object Setup {
    fun notificationAccess(ctx: Context) = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    fun accessibility(ctx: Context): Boolean {
        val expected = ComponentName(ctx, IslandAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
        return splitter.any { it.equals(expected, ignoreCase = true) }
    }

    fun bluetooth(ctx: Context) = ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Both required permissions granted. */
    fun ready(ctx: Context) = notificationAccess(ctx) && accessibility(ctx)

    fun tryStart(a: Activity, vararg intents: Intent) {
        for (i in intents) {
            try { a.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Exception) {}
        }
        Toast.makeText(a, "Couldn't open that screen on this phone", Toast.LENGTH_LONG).show()
    }

    fun appDetails(ctx: Context, pkg: String = ctx.packageName) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))

    fun openNotificationAccess(a: Activity) = disclose(a) {
        tryStart(a,
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(a, IslandNotificationListener::class.java).flattenToString()),
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    fun openAccessibility(a: Activity) = disclose(a) {
        Toast.makeText(a, "Find \"Dynamic Island\" under Downloaded apps and switch it on", Toast.LENGTH_LONG).show()
        tryStart(a, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    fun openAppInfo(a: Activity, pkg: String = a.packageName) = tryStart(a, appDetails(a, pkg))

    // ---- keep it running (brand-specific) ----

    class KeepAliveStep(val title: String, val subtitle: String, val run: (Activity) -> Unit)

    fun keepAliveSteps(brand: Brand): List<KeepAliveStep> = if (brand == Brand.XIAOMI) listOf(
        KeepAliveStep("Autostart", "Allow Dynamic Island to start by itself") { a ->
            tryStart(a, Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")), appDetails(a))
        },
        KeepAliveStep("Battery saver", "Set to No restrictions") { a ->
            tryStart(a, Intent().setComponent(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
                .putExtra("package_name", a.packageName).putExtra("package_label", a.getString(R.string.app_name)),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), appDetails(a))
        },
        KeepAliveStep("Other permissions", "Allow Show on lock screen and pop-up windows") { a ->
            tryStart(a, Intent("miui.intent.action.APP_PERM_EDITOR").setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                .putExtra("extra_pkgname", a.packageName), appDetails(a))
        },
    ) else listOfNotNull(
        brand.autostart?.let { comp -> KeepAliveStep("Auto-launch", "Allow background start") { a -> tryStart(a, Intent().setComponent(comp), appDetails(a)) } },
        KeepAliveStep("Battery", "Set to Unrestricted / Don't optimise") { a -> tryStart(a, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), appDetails(a)) },
        KeepAliveStep("App info", "Battery and notification settings") { a -> openAppInfo(a) },
    )

    fun guideUrl(brand: Brand) = "https://dontkillmyapp.com/${brand.slug}"

    /** Prominent disclosure shown once before sending people to grant sensitive access. */
    fun disclose(a: Activity, then: () -> Unit) {
        val prefs = Prefs(a)
        if (prefs.sp.getBoolean("disclosure_ok", false)) { then(); return }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(a)
            .setTitle("How Dynamic Island uses this access")
            .setMessage(
                "• Notification access: reads notifications and media sessions on this phone only, to show them in the island and let you reply or control playback.\n\n" +
                    "• Accessibility service: used only to draw the island above the status bar and lock screen, and to know which app is open so it can hide that app's own activity. It does not read text on your screen.\n\n" +
                    "• Nothing leaves your phone: the app has no internet permission, no ads and no analytics."
            )
            .setPositiveButton("Agree and continue") { _, _ -> prefs.set("disclosure_ok", true); then() }
            .setNegativeButton("Not now", null)
            .show()
    }
}
