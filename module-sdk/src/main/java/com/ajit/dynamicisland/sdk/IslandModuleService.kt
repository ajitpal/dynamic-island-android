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

    /** Tools this module adds to the island's tools panel. */
    open val tools: List<ModuleTool> get() = emptyList()

    /** Show / update / remove cards in the island. Safe to call when not connected (no-op). */
    val island = object : IslandClient {
        override val isConnected: Boolean get() = host != null
        override fun post(card: IslandCard) {
            try { host?.postCard(card.bundle) } catch (e: Exception) { Log.w(TAG, "post failed", e) }
        }
        override fun remove(cardId: String) {
            try { host?.removeCard(cardId) } catch (e: Exception) { Log.w(TAG, "remove failed", e) }
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
                }
            })
            return Bundle().apply { putParcelableArrayList(C.K_TOOLS, list) }
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
