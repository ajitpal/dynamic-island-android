package com.ajit.dynamicisland.sdk

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.ajit.dynamicisland.sdk.IslandContract as C

/**
 * Base class for a Dynamic Island module. Declare it in your manifest:
 *
 * ```
 * <service android:name=".MyModule" android:exported="true" android:label="My module">
 *     <intent-filter><action android:name="com.ajit.dynamicisland.action.MODULE" /></intent-filter>
 *     <meta-data android:name="com.ajit.dynamicisland.module.DESCRIPTION" android:value="What it does" />
 *     <meta-data android:name="com.ajit.dynamicisland.module.SETTINGS_ACTIVITY" android:value=".SettingsActivity" />
 * </service>
 * ```
 *
 * Every incoming call is checked: only a genuine Dynamic Island app (signed with a trusted
 * certificate) can drive a module. Callbacks run on the main thread.
 */
abstract class IslandModuleService : Service() {

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var host: IIslandHost? = null

    /** Tools this module adds to the island's tools panel and to its card on the Modules tab. */
    open val tools: List<ModuleTool> get() = emptyList()

    /** API 2: the screen "Open" goes to on the Modules tab (defaults to the SETTINGS_ACTIVITY meta-data). */
    open val mainActivity: Class<*>? get() = null

    /** Show / update / remove cards in the island. Safe to call when not connected (no-op). */
    val island = object : IslandClient {
        override val isConnected: Boolean get() = host != null
        override fun post(card: IslandCard) {
            try { host?.postCard(card.bundle) } catch (e: Exception) { Log.w(TAG, "post failed", e) }
        }
        override fun remove(cardId: String) {
            try { host?.removeCard(cardId) } catch (e: Exception) { Log.w(TAG, "remove failed", e) }
        }
        override fun postTile(tile: IslandTile) {
            try { host?.postTile(tile.bundle) } catch (e: Exception) { Log.w(TAG, "tile failed (island older than API 2?)", e) }
        }
        override fun removeTile() {
            try { host?.removeTile() } catch (e: Exception) { Log.w(TAG, "removeTile failed", e) }
        }
    }

    open fun onIslandConnected() {}
    open fun onIslandDisconnected() {}
    open fun onToolTapped(toolId: String) {}
    open fun onAction(cardId: String, actionId: String, input: String?) {}

    override fun onCreate() { super.onCreate(); current = this }
    override fun onDestroy() { if (current === this) current = null; host = null; super.onDestroy() }

    private val binder = object : IIslandModule.Stub() {
        override fun apiVersion() = C.API_VERSION

        override fun describe(): Bundle {
            if (!trusted()) return Bundle()
            val list = ArrayList(tools.map {
                Bundle().apply {
                    putString(C.K_TOOL_ID, it.id); putString(C.K_TOOL_LABEL, it.label)
                    putParcelable(C.K_TOOL_ICON, Icon.createWithResource(this@IslandModuleService, it.iconRes))
                    it.activity?.let { a -> putString(C.K_TOOL_ACTIVITY, a.name) }
                }
            })
            return Bundle().apply {
                putParcelableArrayList(C.K_TOOLS, list)
                mainActivity?.let { putString(C.K_MAIN_ACTIVITY, it.name) }
                LauncherIcon.state(this@IslandModuleService)?.let { visible ->
                    putBoolean(C.K_LAUNCHER_TOGGLE, true); putBoolean(C.K_LAUNCHER_VISIBLE, visible)
                }
            }
        }

        override fun attach(h: IIslandHost?) {
            if (!trusted() || h == null) return
            host = h
            main.post { onIslandConnected() }
        }

        override fun detach() {
            if (!trusted()) return
            host = null
            main.post { onIslandDisconnected() }
        }

        override fun onToolTapped(toolId: String?) {
            if (!trusted() || toolId == null) return
            main.post { this@IslandModuleService.onToolTapped(toolId) }
        }

        override fun onAction(cardId: String?, actionId: String?, input: String?) {
            if (!trusted() || cardId == null || actionId == null) return
            if (cardId == C.MODULE_ID) {   // module-level commands from the island's Modules tab
                when (actionId) {
                    C.CMD_LAUNCHER_ON -> LauncherIcon.set(this@IslandModuleService, true)
                    C.CMD_LAUNCHER_OFF -> LauncherIcon.set(this@IslandModuleService, false)
                }
                return
            }
            main.post { this@IslandModuleService.onAction(cardId, actionId, input) }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /** Only the genuine Dynamic Island app may call a module. */
    private fun trusted(): Boolean {
        val ok = HostVerifier.isTrustedCaller(this, Binder.getCallingUid())
        if (!ok) Log.w(TAG, "Rejected call from uid ${Binder.getCallingUid()}")
        return ok
    }

    companion object {
        private const val TAG = "IslandModule"
        /** The running module service (if any), for receivers/activities in the same app. */
        @Volatile var current: IslandModuleService? = null
            private set
    }
}

interface IslandClient {
    val isConnected: Boolean
    fun post(card: IslandCard)
    fun remove(cardId: String)
    /** API 2: this module's tile on the app's Island tab. No-op on older islands. */
    fun postTile(tile: IslandTile)
    fun removeTile()
}

/**
 * Shows or hides the module's app-drawer icon. Declare the launcher entry as an activity-alias named
 * ".Launcher" (IslandContract.LAUNCHER_ALIAS) so the module stays reachable from the island when hidden.
 */
object LauncherIcon {
    private fun component(ctx: Context) = ComponentName(ctx.packageName, ctx.packageName + C.LAUNCHER_ALIAS)

    /** true/false = shown/hidden; null = this module has no ".Launcher" alias. */
    fun state(ctx: Context): Boolean? = try {
        val pm = ctx.packageManager
        val info = pm.getActivityInfo(component(ctx), PackageManager.MATCH_DISABLED_COMPONENTS)
        when (pm.getComponentEnabledSetting(component(ctx))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
            else -> info.enabled
        }
    } catch (e: Exception) { null }

    fun set(ctx: Context, visible: Boolean) {
        if (state(ctx) == null) return
        ctx.packageManager.setComponentEnabledSetting(component(ctx),
            if (visible) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP)
    }
}

/** Verifies that a caller is the real Dynamic Island app (package + signing certificate). */
object HostVerifier {
    fun isTrustedCaller(ctx: Context, uid: Int): Boolean {
        val pm = ctx.packageManager
        val pkgs = pm.getPackagesForUid(uid) ?: return false
        if (C.HOST_PACKAGE !in pkgs) return false
        if (trustAnyHost(ctx)) return true
        return C.TRUSTED_HOST_CERTS.any { hex ->
            try { pm.hasSigningCertificate(C.HOST_PACKAGE, hexToBytes(hex), PackageManager.CERT_INPUT_SHA256) } catch (e: Exception) { false }
        }
    }

    /** Is the Dynamic Island app installed (any build)? */
    fun isHostInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(C.HOST_PACKAGE, 0); true
    } catch (e: Exception) { false }

    /** Open the Dynamic Island app's Modules screen. */
    fun openHostModules(ctx: Context): Boolean = try {
        ctx.startActivity(Intent().setComponent(ComponentName(C.HOST_PACKAGE, "${C.HOST_PACKAGE}.ModulesActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) { false }

    private fun trustAnyHost(ctx: Context): Boolean = try {
        val pm = ctx.packageManager
        val svc = pm.queryIntentServices(Intent(C.ACTION_MODULE).setPackage(ctx.packageName), PackageManager.GET_META_DATA)
        svc.any { it.serviceInfo.metaData?.getBoolean(C.META_TRUST_ANY_HOST, false) == true }
    } catch (e: Exception) { false }

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(":", "").lowercase()
        return ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
