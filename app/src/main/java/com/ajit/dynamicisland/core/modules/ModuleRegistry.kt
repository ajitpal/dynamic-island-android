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
    val version: String = "",
    val icon: Drawable? = null,
    /** From the module's describe() (API 2), once connected. */
    val mainActivity: String? = null,
    val launcherToggle: Boolean = false,
    val launcherVisible: Boolean = true,
)

/** A module's tile on the Island tab (API 2). */
class ModuleTile(val pkg: String, val title: String, val text: String?, val icon: Drawable?,
                 val onTap: (() -> Unit)?, val actions: List<Pair<String, () -> Unit>>)

data class ModuleTool(val fullId: String, val pkg: String, val toolId: String, val label: String, val icon: Drawable?, val activity: String? = null)

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

    private val _tiles = MutableStateFlow<List<ModuleTile>>(emptyList())
    val tiles: StateFlow<List<ModuleTile>> = _tiles

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
        if (!started || preview) return
        var found = discover()
        if (autoEnable(found)) found = discover()
        val wanted = found.filter { it.approved && (it.trust != Trust.UNVERIFIED || prefs.modulesDevMode) }.map { it.pkg }.toSet()
        conns.keys.filter { it !in wanted }.forEach { pkg -> conns.remove(pkg)?.let { disconnect(it) } }
        found.filter { it.pkg in wanted && it.pkg !in conns }.forEach { connect(it) }
        publish(found)
    }

    /**
     * Icons are cached per install (package + last update time). A Drawable has no value equality, so a
     * fresh icon on every scan made each rescan look like a change, and screens that refresh on change and
     * rescan on refresh looped forever on the main thread (1.9.0 "isn't responding" on the Modules tab).
     */
    private val iconCache = HashMap<String, Pair<Long, android.graphics.drawable.Drawable?>>()

    private fun iconOf(pm: PackageManager, pkg: String, updated: Long): android.graphics.drawable.Drawable? {
        iconCache[pkg]?.let { (t, d) -> if (t == updated) return d }
        val d = try { pm.getApplicationIcon(pkg) } catch (e: Exception) { null }
        iconCache[pkg] = updated to d
        return d
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
            val pi = try { pm.getPackageInfo(si.packageName, 0) } catch (e: Exception) { null }
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
                version = pi?.versionName.orEmpty(),
                icon = iconOf(pm, si.packageName, pi?.lastUpdateTime ?: 0L),
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

    /**
     * The first time a module appears: one from the same developer as this app (signed with the same
     * key) is switched on automatically, and the user is told; any other module gets a "review" glimpse
     * and stays off until approved. Returns true if something was approved.
     */
    private fun autoEnable(found: List<ModuleInfo>): Boolean {
        val seen = prefs.seenModules()
        val fresh = found.filter { it.pkg !in seen }
        if (fresh.isEmpty()) return false
        prefs.setSeenModules(seen + fresh.map { it.pkg })
        var changed = false
        fresh.forEach { m ->
            val auto = prefs.modulesAuto && m.trust == Trust.SAME_DEVELOPER && !m.approved
            if (auto) { prefs.setApprovedModules(prefs.approvedModules() + m.pkg); changed = true }
            if (!m.approved) announce(m, auto)
        }
        return changed
    }

    private fun announce(m: ModuleInfo, enabled: Boolean) {
        val open = { Launcher.start(app, Intent(app, com.ajit.dynamicisland.ModulesActivity::class.java)) }
        IslandRepository.upsert(IslandActivity(
            id = "sys:module:${m.pkg}", kind = Kind.SYSTEM, packageName = app.packageName,
            title = if (enabled) "${m.label} added" else "New module: ${m.label}",
            text = if (enabled) "Switched on. Its tools are in your island tools; turn it off in Modules." else "Review and approve it in Modules before it can show anything.",
            icon = ContextCompat.getDrawable(app, R.drawable.ic_add), tintIcon = true, accent = if (enabled) Colors.GREEN else Colors.ORANGE,
            compactText = if (enabled) "Added" else "Review", peek = true, peekMs = 5000,
            onTap = open,
            actions = listOf(IslandAction(if (enabled) "Manage" else "Review", onClick = { open() })),
            expiresAt = System.currentTimeMillis() + 30_000, contentHash = m.pkg.hashCode(),
        ))
    }

    /** Put a module's tools in the island tools the first time they appear (unless auto-add is off). */
    private fun autoAddTools(tools: List<ModuleTool>) {
        val seen = prefs.seenModuleTools()
        val fresh = tools.filter { it.fullId !in seen }
        if (fresh.isEmpty()) return
        prefs.setSeenModuleTools(seen + fresh.map { it.fullId })
        if (!prefs.modulesAuto) return
        val layout = prefs.tools
        val add = fresh.map { it.fullId }.filter { it !in layout }
        // Put new module tools first, so they're on the first page of the tools panel.
        if (add.isNotEmpty()) prefs.tools = add + layout
    }

    /** Is this module tool in the island tools panel? */
    fun isToolShown(fullId: String) = fullId in prefs.tools
    fun setToolShown(fullId: String, shown: Boolean) {
        val layout = prefs.tools
        prefs.tools = if (shown) (if (fullId in layout) layout else layout + fullId) else layout - fullId
        prefs.setSeenModuleTools(prefs.seenModuleTools() + fullId)
        publish()
    }

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
        var described: Bundle? = null
        var tile: ModuleTile? = null
        lateinit var host: IIslandHost.Stub

        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val m = IIslandModule.Stub.asInterface(binder) ?: return
            module = m
            uid = try { app.packageManager.getPackageUid(info.pkg, 0) } catch (e: Exception) { -1 }
            Thread {
                val described = try { m.describe() } catch (e: Exception) { null }
                main.post {
                    this.described = described
                    tools = parseTools(info.pkg, described)
                    autoAddTools(tools)
                    try { m.attach(host) } catch (_: Exception) {}
                    publish()
                }
            }.start()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            module = null
            tile = null
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

    private fun parseTile(pkg: String, b: Bundle): ModuleTile? {
        val title = b.getCharSequence(C.T_TITLE)?.toString()?.take(C.MAX_TITLE) ?: return null
        @Suppress("DEPRECATION") val icon = (b.getParcelable(C.T_ICON) as? Icon)?.let { try { it.loadDrawable(app) } catch (e: Exception) { null } }
            ?: try { app.packageManager.getApplicationIcon(pkg) } catch (e: Exception) { null }
        val tap = b.getString(C.T_TAP_ACTIVITY)?.let { cls -> { openModuleActivity(pkg, cls, b.getString(C.T_TAP_ARG)) } }
        @Suppress("DEPRECATION") val acts = (b.getParcelableArrayList<Bundle>(C.T_ACTIONS) ?: arrayListOf()).take(2).mapNotNull { ab ->
            val t = ab.getString(C.A_TITLE)?.take(20) ?: return@mapNotNull null
            val id = ab.getString(C.A_ID) ?: return@mapNotNull null
            val cls = ab.getString(C.A_ACTIVITY)
            t to { if (cls != null) openModuleActivity(pkg, cls, ab.getString(C.A_ARG)) else sendAction(pkg, C.TILE_ID, id, null) }
        }
        return ModuleTile(pkg, title, b.getCharSequence(C.T_TEXT)?.toString()?.take(120), icon, tap, acts)
    }

    /** Open a module's main screen: its declared main activity, else its settings, else its launcher. */
    fun openModule(ctx: Context, info: ModuleInfo) {
        when {
            info.mainActivity != null -> openModuleActivity(info.pkg, info.mainActivity, null)
            info.settings != null -> openSettings(ctx, info)
            else -> ctx.packageManager.getLaunchIntentForPackage(info.pkg)?.let { Launcher.start(app, it) }
        }
    }

    /** Ask a module (API 2) to show or hide its app-drawer icon, then refresh what it reports. */
    fun setLauncherVisible(pkg: String, visible: Boolean) {
        val c = conns[pkg] ?: return
        sendAction(pkg, C.MODULE_ID, if (visible) C.CMD_LAUNCHER_ON else C.CMD_LAUNCHER_OFF, null)
        main.postDelayed({
            Thread { val d = try { c.module?.describe() } catch (e: Exception) { null }; main.post { if (d != null) c.described = d; publish() } }.start()
        }, 600)
    }

    private fun disconnect(c: Conn) {
        try { c.module?.detach() } catch (_: Exception) {}
        try { app.unbindService(c) } catch (_: Exception) {}
        c.module = null
        clearCards(c)
    }

    private fun clearCards(c: Conn) {
        c.tile = null
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
        override fun postTile(tile: Bundle?) {
            if (tile == null || Binder.getCallingUid() != c.uid) return
            main.post { c.tile = parseTile(c.info.pkg, tile); publish() }
        }
        override fun removeTile() {
            if (Binder.getCallingUid() != c.uid) return
            main.post { c.tile = null; publish() }
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
        val tapActivity = b.getString(C.K_TAP_ACTIVITY)
        val tapArg = b.getString(C.K_TAP_ARG)
        @Suppress("DEPRECATION") val actions = (b.getParcelableArrayList<Bundle>(C.K_ACTIONS) ?: arrayListOf()).take(3).mapNotNull { ab ->
            val aid = ab.getString(C.A_ID) ?: return@mapNotNull null
            val at = ab.getString(C.A_TITLE)?.take(20) ?: return@mapNotNull null
            val st = when (ab.getInt(C.A_STYLE, 0)) { 1 -> ActionStyle.POSITIVE; 2 -> ActionStyle.NEGATIVE; else -> ActionStyle.NORMAL }
            val url = ab.getString(C.A_URL)
            val act = ab.getString(C.A_ACTIVITY)
            val arg = ab.getString(C.A_ARG)
            if (ab.getBoolean(C.A_INPUT, false)) {
                IslandAction(at, style = st, inputHint = ab.getString(C.A_HINT) ?: "Type…", onInput = { txt -> sendAction(pkg, cardId, aid, txt) })
            } else {
                IslandAction(at, style = st, onClick = {
                    when {
                        act != null -> openModuleActivity(pkg, act, arg)
                        url != null -> Launcher.openUrl(app, url)
                        else -> sendAction(pkg, cardId, aid, null)
                    }
                })
            }
        }
        val onTap: (() -> Unit)? = when {
            tapActivity != null -> { { openModuleActivity(pkg, tapActivity, tapArg) } }
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
            ModuleTool("$TOOL_PREFIX$pkg/$id", pkg, id, label, icon, t.getString(C.K_TOOL_ACTIVITY))
        }
    }

    fun tool(fullId: String): ModuleTool? = _tools.value.firstOrNull { it.fullId == fullId }

    fun toolTapped(fullId: String) {
        val t = tool(fullId) ?: return
        if (t.activity != null) { openModuleActivity(t.pkg, t.activity, null); return }
        try { conns[t.pkg]?.module?.onToolTapped(t.toolId) } catch (_: Exception) {}
    }

    /** Open an exported activity that belongs to the module itself (never another app). */
    private fun openModuleActivity(pkg: String, cls: String, arg: String?) {
        val intent = Intent().setComponent(ComponentName(pkg, if (cls.startsWith(".")) pkg + cls else cls))
        arg?.let { intent.putExtra(C.EXTRA_ARG, it.take(200)) }
        Launcher.start(app, intent)
    }

    fun openSettings(ctx: Context, info: ModuleInfo) {
        val comp = info.settings ?: return
        try { ctx.startActivity(Intent().setComponent(comp).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
    }

    private fun publish(found: List<ModuleInfo>? = null) {
        val list = (found ?: _modules.value).map { m ->
            val c = conns[m.pkg]; val d = c?.described
            m.copy(connected = c?.module != null, approved = m.pkg in prefs.approvedModules(),
                mainActivity = d?.getString(C.K_MAIN_ACTIVITY) ?: m.mainActivity,
                launcherToggle = d?.getBoolean(C.K_LAUNCHER_TOGGLE, false) ?: m.launcherToggle,
                launcherVisible = d?.getBoolean(C.K_LAUNCHER_VISIBLE, true) ?: m.launcherVisible)
        }
        _modules.value = list
        _tiles.value = conns.values.mapNotNull { it.tile }
        _tools.value = conns.values.flatMap { it.tools }
    }

    @Volatile private var preview = false

    /** Screenshot tests only: show sample modules instead of scanning the phone. */
    @androidx.annotation.VisibleForTesting
    fun showPreview(modules: List<ModuleInfo>, tools: List<ModuleTool>, tiles: List<ModuleTile>) {
        preview = true
        _modules.value = modules; _tools.value = tools; _tiles.value = tiles
    }

    /** Tests only: forget preview data and start from scratch. */
    @androidx.annotation.VisibleForTesting
    fun resetForTest() {
        preview = false; started = false; conns.clear(); iconCache.clear()
        _modules.value = emptyList(); _tools.value = emptyList(); _tiles.value = emptyList()
    }

    /** For the Diagnostics card. */
    fun summary(): String = if (!started) "not started" else
        _modules.value.joinToString { "${it.label}${if (it.connected) "✓" else if (it.approved) "…" else "✗"}" }.ifEmpty { "none installed" }
}
