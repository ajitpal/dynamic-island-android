package com.ajit.dynamicisland.core.modules

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import com.ajit.dynamicisland.R
import com.ajit.dynamicisland.core.ActionStyle
import com.ajit.dynamicisland.core.Colors
import com.ajit.dynamicisland.core.IslandAction
import com.ajit.dynamicisland.core.IslandActivity
import com.ajit.dynamicisland.core.IslandRepository
import com.ajit.dynamicisland.core.Kind
import com.ajit.dynamicisland.core.Launcher
import com.ajit.dynamicisland.core.Prefs
import com.ajit.dynamicisland.sdk.IIslandHost
import com.ajit.dynamicisland.sdk.IIslandModule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.MessageDigest
import java.util.Objects
import com.ajit.dynamicisland.sdk.IslandContract as C

/** How much we trust a module, decided by its signing certificate. */
enum class Trust(val label: String) {
    SAME_DEVELOPER("Verified · same developer as Dynamic Island"),
    APPROVED_DEVELOPER("Verified · approved developer"),
    UNVERIFIED("Unverified developer"),
}

data class ModuleInfo(
    val pkg: String,
    val service: ComponentName,
    val label: String,
    val description: String,
    val developer: String?,
    val settings: ComponentName?,
    val trust: Trust,
    val certSha256: String,
    val approved: Boolean,
    val connected: Boolean,
    val permissions: List<Pair<String, Boolean>>,
)

data class ModuleTool(val fullId: String, val pkg: String, val toolId: String, val label: String, val icon: Drawable?)

/**
 * Finds installed modules, checks who signed them, connects to the ones the user approved,
 * and turns their cards into island activities. Cards are templates: modules never draw UI.
 */
object ModuleRegistry {
    const val TOOL_PREFIX = "mod:"

    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    private lateinit var prefs: Prefs
    private var started = false
    private val conns = HashMap<String, Conn>()

    private val _modules = MutableStateFlow<List<ModuleInfo>>(emptyList())
    val modules: StateFlow<List<ModuleInfo>> = _modules

    private val _tools = MutableStateFlow<List<ModuleTool>>(emptyList())
    val tools: StateFlow<List<ModuleTool>> = _tools

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { main.postDelayed({ rescan() }, 500) }
    }

    /** Idempotent. Called by the island service and by settings screens. */
    fun start(ctx: Context) {
        if (!started) {
            app = ctx.applicationContext
            prefs = Prefs(app)
            started = true
            val f = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED); addDataScheme("package")
            }
            ContextCompat.registerReceiver(app, packageReceiver, f, ContextCompat.RECEIVER_EXPORTED)
        }
        rescan()
    }

    fun stop() {
        if (!started) return
        conns.values.toList().forEach { disconnect(it) }
        conns.clear()
        publish()
    }

    // ------------------------------------------------------------------ discovery & trust

    fun rescan() {
        if (!started) return
        val found = discover()
        val wanted = found.filter { it.approved && (it.trust != Trust.UNVERIFIED || prefs.modulesDevMode) }.map { it.pkg }.toSet()
        conns.keys.filter { it !in wanted }.forEach { pkg -> conns.remove(pkg)?.let { disconnect(it) } }
        found.filter { it.pkg in wanted && it.pkg !in conns }.forEach { connect(it) }
        publish(found)
    }

    private fun discover(): List<ModuleInfo> {
        val pm = app.packageManager
        val services = pm.queryIntentServices(Intent(C.ACTION_MODULE), PackageManager.GET_META_DATA)
        return services.mapNotNull { ri ->
            val si = ri.serviceInfo ?: return@mapNotNull null
            if (!si.exported || si.packageName == app.packageName) return@mapNotNull null
            val meta = si.metaData
            val settings = meta?.getString(C.META_SETTINGS_ACTIVITY)?.let {
                ComponentName(si.packageName, if (it.startsWith(".")) si.packageName + it else it)
            }
            val cert = certSha256(si.packageName)
            ModuleInfo(
                pkg = si.packageName,
                service = ComponentName(si.packageName, si.name),
                label = si.loadLabel(pm).toString(),
                description = meta?.getString(C.META_DESCRIPTION).orEmpty(),
                developer = meta?.getString(C.META_DEVELOPER),
                settings = settings,
                trust = trustOf(si.packageName, cert),
                certSha256 = cert,
                approved = si.packageName in prefs.approvedModules(),
                connected = conns[si.packageName]?.module != null,
                permissions = permissionsOf(si.packageName),
            )
        }.sortedBy { it.label.lowercase() }
    }

    private fun trustOf(pkg: String, cert: String): Trust {
        val pm = app.packageManager
        return when {
            pm.checkSignatures(app.packageName, pkg) == PackageManager.SIGNATURE_MATCH -> Trust.SAME_DEVELOPER
            // Platform-enforced allow-list: granted only to apps signed by a certificate in knownCerts.
            pm.checkPermission(C.PERMISSION_MODULE_API, pkg) == PackageManager.PERMISSION_GRANTED -> Trust.APPROVED_DEVELOPER
            // Same allow-list checked in code (also covers modules installed before the app).
            cert in approvedCerts() -> Trust.APPROVED_DEVELOPER
            else -> Trust.UNVERIFIED
        }
    }

    private fun approvedCerts(): Set<String> =
        app.resources.getStringArray(R.array.approved_module_certs).map { it.replace(":", "").lowercase() }.toSet()

    private fun certSha256(pkg: String): String = try {
        val info = app.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        val signer = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return ""
        MessageDigest.getInstance("SHA-256").digest(signer.toByteArray()).joinToString("") { "%02x".format(it) }
    } catch (e: Exception) { "" }

    private fun permissionsOf(pkg: String): List<Pair<String, Boolean>> = try {
        val pm = app.packageManager
        val info = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
        val req = info.requestedPermissions ?: emptyArray()
        val flags = info.requestedPermissionsFlags ?: IntArray(0)
        req.indices.mapNotNull { i ->
            val name = req[i]
            if (name == C.PERMISSION_MODULE_API || name.endsWith("DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")) return@mapNotNull null
            val granted = (flags.getOrNull(i) ?: 0) and android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED != 0
            val label = try { pm.getPermissionInfo(name, 0).loadLabel(pm).toString() } catch (e: Exception) { name.substringAfterLast('.') }
            label to granted
        }
    } catch (e: Exception) { emptyList() }

    fun setApproved(pkg: String, approved: Boolean) {
        prefs.setApprovedModules(if (approved) prefs.approvedModules() + pkg else prefs.approvedModules() - pkg)
        rescan()
    }

    // ------------------------------------------------------------------ connections

    private class Conn(val info: ModuleInfo) : ServiceConnection {
        var module: IIslandModule? = null
        var uid: Int = -1
        val cards = LinkedHashSet<String>()
        var tools: List<ModuleTool> = emptyList()
        lateinit var host: IIslandHost.Stub

        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val m = IIslandModule.Stub.asInterface(binder) ?: return
            module = m
            uid = try { app.packageManager.getPackageUid(info.pkg, 0) } catch (e: Exception) { -1 }
            Thread {
                val described = try { m.describe() } catch (e: Exception) { null }
                main.post {
                    tools = parseTools(info.pkg, described)
                    try { m.attach(host) } catch (_: Exception) {}
                    publish()
                }
            }.start()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            module = null
            clearCards(this)
            publish()
        }

        override fun onBindingDied(name: ComponentName?) {
            module = null
            clearCards(this)
            main.postDelayed({ rescan() }, 2000)
        }
    }

    private fun connect(info: ModuleInfo) {
        val c = Conn(info)
        c.host = hostFor(c)
        val ok = try {
            app.bindService(Intent(C.ACTION_MODULE).setComponent(info.service), c, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) { false }
        if (ok) conns[info.pkg] = c
    }

    private fun disconnect(c: Conn) {
        try { c.module?.detach() } catch (_: Exception) {}
        try { app.unbindService(c) } catch (_: Exception) {}
        c.module = null
        clearCards(c)
    }

    private fun clearCards(c: Conn) {
        c.cards.toList().forEach { IslandRepository.remove(activityId(c.info.pkg, it)) }
        c.cards.clear()
    }

    /** The host object handed to one module. Every call is checked to come from that module. */
    private fun hostFor(c: Conn) = object : IIslandHost.Stub() {
        override fun apiVersion() = C.API_VERSION
        override fun postCard(card: Bundle?) {
            if (card == null || Binder.getCallingUid() != c.uid) return
            main.post { onCard(c, card) }
        }
        override fun removeCard(cardId: String?) {
            if (cardId == null || Binder.getCallingUid() != c.uid) return
            main.post { c.cards.remove(cardId); IslandRepository.remove(activityId(c.info.pkg, cardId)) }
        }
    }

    private fun activityId(pkg: String, cardId: String) = "mod:$pkg:$cardId"

    // ------------------------------------------------------------------ cards → island activities

    private fun onCard(c: Conn, b: Bundle) {
        if (c.module == null) return
        val cardId = b.getString(C.K_ID)?.take(40) ?: return
        val title = b.getCharSequence(C.K_TITLE)?.toString()?.take(C.MAX_TITLE) ?: return
        // Cap how many cards one module can show at once.
        if (cardId !in c.cards && c.cards.size >= C.MAX_CARDS_PER_MODULE) {
            val oldest = c.cards.first(); c.cards.remove(oldest); IslandRepository.remove(activityId(c.info.pkg, oldest))
        }
        c.cards += cardId
        val pkg = c.info.pkg
        val text = b.getCharSequence(C.K_TEXT)?.toString()?.take(C.MAX_TEXT)
        val style = b.getString(C.K_STYLE) ?: C.STYLE_LIVE
        val kind = when (style) {
            C.STYLE_TIMER -> Kind.TIMER
            C.STYLE_PROGRESS -> Kind.PROGRESS
            C.STYLE_ALERT -> Kind.SYSTEM
            else -> Kind.LIVE
        }
        @Suppress("DEPRECATION") val icon = (b.getParcelable(C.K_ICON) as? Icon)?.let { try { it.loadDrawable(app) } catch (e: Exception) { null } }
        val countdown = b.getLong(C.K_COUNTDOWN_TO, 0L)
        val countUp = b.getLong(C.K_COUNT_UP_FROM, 0L)
        val progress = if (b.containsKey(C.K_PROGRESS)) b.getFloat(C.K_PROGRESS).coerceIn(0f, 1f) else -1f
        val tapUrl = b.getString(C.K_TAP_URL)
        val tapCallback = b.getBoolean(C.K_TAP_CALLBACK, false)
        @Suppress("DEPRECATION") val actions = (b.getParcelableArrayList<Bundle>(C.K_ACTIONS) ?: arrayListOf()).take(3).mapNotNull { ab ->
            val aid = ab.getString(C.A_ID) ?: return@mapNotNull null
            val at = ab.getString(C.A_TITLE)?.take(20) ?: return@mapNotNull null
            val st = when (ab.getInt(C.A_STYLE, 0)) { 1 -> ActionStyle.POSITIVE; 2 -> ActionStyle.NEGATIVE; else -> ActionStyle.NORMAL }
            val url = ab.getString(C.A_URL)
            if (ab.getBoolean(C.A_INPUT, false)) {
                IslandAction(at, style = st, inputHint = ab.getString(C.A_HINT) ?: "Type…", onInput = { txt -> sendAction(pkg, cardId, aid, txt) })
            } else {
                IslandAction(at, style = st, onClick = { if (url != null) Launcher.openUrl(app, url) else sendAction(pkg, cardId, aid, null) })
            }
        }
        val onTap: (() -> Unit)? = when {
            tapUrl != null -> { { Launcher.openUrl(app, tapUrl) } }
            tapCallback -> { { sendAction(pkg, cardId, C.ACTION_TAP, null) } }
            else -> null
        }
        val version = b.getInt(C.K_VERSION, 0)
        IslandRepository.upsert(
            IslandActivity(
                id = activityId(pkg, cardId), kind = kind, packageName = pkg,
                title = title, text = text, icon = icon,
                tintIcon = b.getBoolean(C.K_TINT_ICON, true) && icon != null,
                accent = b.getInt(C.K_ACCENT, Colors.BLUE) or 0xFF000000.toInt(),
                compactText = b.getCharSequence(C.K_COMPACT)?.toString()?.take(24),
                progress = progress,
                chronoBase = if (countdown > 0) countdown else countUp,
                countDown = countdown > 0,
                onTap = onTap,
                actions = actions,
                appLabel = c.info.label,
                peek = b.getBoolean(C.K_PEEK, false),
                peekMs = b.getLong(C.K_PEEK_MS, 5000L).coerceIn(1500L, 15_000L),
                expiresAt = b.getLong(C.K_EXPIRES_AT, 0L),
                // Never outrank a call; module alerts sit just below system glimpses.
                priority = kind.basePriority.coerceAtMost(Kind.SYSTEM.basePriority - 5),
                contentHash = Objects.hash(title, text, style, version),
            )
        )
    }

    private fun sendAction(pkg: String, cardId: String, actionId: String, input: String?) {
        try { conns[pkg]?.module?.onAction(cardId, actionId, input) } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ tools

    private fun parseTools(pkg: String, b: Bundle?): List<ModuleTool> {
        @Suppress("DEPRECATION") val list = b?.getParcelableArrayList<Bundle>(C.K_TOOLS) ?: return emptyList()
        return list.take(8).mapNotNull { t ->
            val id = t.getString(C.K_TOOL_ID) ?: return@mapNotNull null
            val label = t.getString(C.K_TOOL_LABEL)?.take(18) ?: return@mapNotNull null
            @Suppress("DEPRECATION") val icon = (t.getParcelable(C.K_TOOL_ICON) as? Icon)?.let { try { it.loadDrawable(app) } catch (e: Exception) { null } }
            ModuleTool("$TOOL_PREFIX$pkg/$id", pkg, id, label, icon)
        }
    }

    fun tool(fullId: String): ModuleTool? = _tools.value.firstOrNull { it.fullId == fullId }

    fun toolTapped(fullId: String) {
        val t = tool(fullId) ?: return
        try { conns[t.pkg]?.module?.onToolTapped(t.toolId) } catch (_: Exception) {}
    }

    fun openSettings(ctx: Context, info: ModuleInfo) {
        val comp = info.settings ?: return
        try { ctx.startActivity(Intent().setComponent(comp).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
    }

    private fun publish(found: List<ModuleInfo>? = null) {
        val list = (found ?: _modules.value).map { it.copy(connected = conns[it.pkg]?.module != null, approved = it.pkg in prefs.approvedModules()) }
        _modules.value = list
        _tools.value = conns.values.flatMap { it.tools }
    }

    /** For the Diagnostics card. */
    fun summary(): String = if (!started) "not started" else
        _modules.value.joinToString { "${it.label}${if (it.connected) "✓" else if (it.approved) "…" else "✗"}" }.ifEmpty { "none installed" }
}
