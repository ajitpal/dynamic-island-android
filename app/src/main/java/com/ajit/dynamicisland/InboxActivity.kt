package com.ajit.dynamicisland

import android.app.ActivityOptions
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ajit.dynamicisland.core.NotificationParser
import com.ajit.dynamicisland.core.inbox.InboxEntry
import com.ajit.dynamicisland.core.inbox.InboxFilter
import com.ajit.dynamicisland.core.inbox.InboxStore
import com.ajit.dynamicisland.ui.dp
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/** Every notification you received, until you clear it. Search, filter, swipe to clear, tap to open. */
class InboxActivity : AppCompatActivity() {

    private var filter = InboxFilter.ALL
    private var search = ""
    private val adapter = Adapter()
    private lateinit var empty: TextView
    private lateinit var count: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), 0) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(dp(16), bars.top + dp(12), dp(16), bars.bottom); insets
        }
        root.addView(TextView(this).apply { text = "Inbox"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f); setTypeface(typeface, android.graphics.Typeface.BOLD) })
        count = TextView(this).apply { alpha = 0.7f; setPadding(0, dp(2), 0, dp(10)) }
        root.addView(count)

        val til = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply { hint = "Search notifications" }
        val et = TextInputEditText(til.context)
        til.addView(et); root.addView(til)
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { search = s?.toString().orEmpty(); reload() }
        })

        val chips = ChipGroup(this).apply { isSingleSelection = true; isSelectionRequired = true }
        InboxFilter.entries.forEach { f ->
            chips.addView(Chip(this).apply {
                text = f.label; isCheckable = true; isChecked = f == filter
                setOnClickListener { filter = f; reload() }
            })
        }
        root.addView(chips)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Mark all read"; setOnClickListener { InboxStore.markAllRead(this@InboxActivity) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        actions.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Clear all"
            setOnClickListener {
                MaterialAlertDialogBuilder(this@InboxActivity).setTitle("Clear the whole inbox?")
                    .setMessage("This deletes every saved notification from Dynamic Island. Your apps' own notifications are not affected.")
                    .setPositiveButton("Clear") { _, _ -> InboxStore.clearAll(this@InboxActivity) }
                    .setNegativeButton("Cancel", null).show()
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(actions)
        val prefs = com.ajit.dynamicisland.core.Prefs(this)
        if (prefs.smartDigest && com.ajit.dynamicisland.sdk.ai.AiClient.isTrusted(this)) {
            root.addView(MaterialButton(this).apply {
                text = "✨ Catch me up"; setOnClickListener { catchUp() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        empty = TextView(this).apply {
            text = "Nothing here yet.\nNotifications you receive will be kept here until you clear them."
            gravity = Gravity.CENTER; alpha = 0.6f; setPadding(0, dp(60), 0, 0); visibility = View.GONE
        }
        root.addView(empty)

        val list = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@InboxActivity); adapter = this@InboxActivity.adapter; clipToPadding = false }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun getSwipeDirs(rv: RecyclerView, vh: RecyclerView.ViewHolder) =
                if (adapter.rows.getOrNull(vh.bindingAdapterPosition) is Row.Item) super.getSwipeDirs(rv, vh) else 0
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false
            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {
                (adapter.rows.getOrNull(vh.bindingAdapterPosition) as? Row.Item)?.let { InboxStore.delete(this@InboxActivity, it.e.id) }
            }
        }).attachToRecyclerView(list)

        setContentView(root)
        lifecycleScope.launch { InboxStore.version.collect { reload() } }
    }

    private var ai: com.ajit.dynamicisland.sdk.ai.AiClient? = null

    /** Summarise unread (or today's) notifications with the on-device AI Engine. */
    private fun catchUp() {
        val out = TextView(this).apply { setPadding(dp(24), dp(12), dp(24), dp(8)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); setTextIsSelectable(true) }
        val scroll = android.widget.ScrollView(this).apply { addView(out) }
        val dialog = MaterialAlertDialogBuilder(this).setTitle("Catch me up").setView(scroll)
            .setPositiveButton("Done", null).show()
        out.text = "Reading your notifications…"
        lifecycleScope.launch {
            val dayAgo = System.currentTimeMillis() - 24 * 3600_000L
            val entries = withContext(Dispatchers.IO) {
                InboxStore.query(this@InboxActivity, InboxFilter.UNREAD, null, 80).ifEmpty {
                    InboxStore.query(this@InboxActivity, InboxFilter.ALL, null, 80).filter { it.posted >= dayAgo }
                }
            }
            if (entries.isEmpty()) { out.text = "Nothing new in the last day."; return@launch }
            val client = ai ?: com.ajit.dynamicisland.sdk.ai.AiClient(this@InboxActivity).also { ai = it }
            out.text = "Summarising ${entries.size} notifications on this phone… (the first run loads the model, which takes a few seconds)"
            val id = client.generate(
                system = com.ajit.dynamicisland.core.ai.SmartInbox.DIGEST_SYSTEM,
                prompt = com.ajit.dynamicisland.core.ai.SmartInbox.digestPrompt(entries),
                maxTokens = com.ajit.dynamicisland.core.ai.SmartInbox.DIGEST_TOKENS, temperature = 0.2f,
                onPartial = { if (it.isNotBlank()) out.text = pretty(it) },
                onDone = { out.text = pretty(it) + "\n\nWritten on this phone by the AI Engine. It can make mistakes." },
                onError = { _, msg -> out.text = "⚠ $msg" },
            )
            dialog.setOnDismissListener { client.cancel(id) }
        }
    }

    private fun pretty(s: String) = s.replace(Regex("(?m)^#{1,4}\\s*"), "").replace(Regex("(?m)^\\s*[-*]\\s+"), "• ").trim()

    override fun onDestroy() { ai?.close(); super.onDestroy() }

    private fun reload() {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { InboxStore.query(this@InboxActivity, filter, search) }
            val unread = items.count { !it.read }
            count.text = "${items.size} notification${if (items.size == 1) "" else "s"} · $unread unread · kept for ${com.ajit.dynamicisland.core.Prefs(this@InboxActivity).inboxDays} days"
            adapter.submit(items)
            empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun open(e: InboxEntry) {
        InboxStore.markRead(this, e.id)
        val pi = InboxStore.liveIntents[e.key]
        if (pi != null) {
            try {
                val opts = ActivityOptions.makeBasic().apply {
                    if (Build.VERSION.SDK_INT >= 34) {
                        @Suppress("DEPRECATION")
                        setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    }
                }.toBundle()
                pi.send(this, 0, null, null, null, null, opts); return
            } catch (_: Exception) {}
        }
        packageManager.getLaunchIntentForPackage(e.pkg)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            ?: Toast.makeText(this, "Can't open ${e.app}", Toast.LENGTH_SHORT).show()
    }

    private fun copy(e: InboxEntry) {
        val txt = e.otp ?: "${e.title}\n${e.text}"
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("notification", txt))
        Toast.makeText(this, if (e.otp != null) "OTP copied" else "Copied", Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------ list

    private sealed class Row {
        data class Header(val label: String) : Row()
        data class Item(val e: InboxEntry) : Row()
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var rows: List<Row> = emptyList()

        fun submit(items: List<InboxEntry>) {
            val out = ArrayList<Row>(); var lastDay = ""
            items.forEach { e ->
                val day = dayLabel(e.posted)
                if (day != lastDay) { out += Row.Header(day); lastDay = day }
                out += Row.Item(e)
            }
            rows = out
            @Suppress("NotifyDataSetChanged") notifyDataSetChanged()
        }

        override fun getItemCount() = rows.size
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val v = if (viewType == 0) TextView(parent.context).apply {
                setPadding(dp(4), dp(18), 0, dp(6)); alpha = 0.6f; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            } else ItemView(parent)
            v.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            return object : RecyclerView.ViewHolder(v) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val r = rows[position]) {
                is Row.Header -> (holder.itemView as TextView).text = r.label.uppercase()
                is Row.Item -> (holder.itemView as ItemView).bind(r.e)
            }
        }
    }

    private inner class ItemView(parent: ViewGroup) : LinearLayout(parent.context) {
        private val icon = ImageView(context)
        private val title = TextView(context).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); setTypeface(typeface, android.graphics.Typeface.BOLD); maxLines = 1 }
        private val body = TextView(context).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); alpha = 0.8f; maxLines = 3 }
        private val meta = TextView(context).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); alpha = 0.55f }
        private val dot = View(context).apply { background = com.ajit.dynamicisland.ui.circle(0xFF0A84FF.toInt()) }

        init {
            orientation = HORIZONTAL; gravity = Gravity.TOP
            setPadding(dp(4), dp(10), dp(4), dp(10))
            val tv = TypedValue(); context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true); setBackgroundResource(tv.resourceId)
            addView(icon, LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12); topMargin = dp(2) })
            val col = LinearLayout(context).apply { orientation = VERTICAL }
            col.addView(meta); col.addView(title); col.addView(body)
            addView(col, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(dot, LayoutParams(dp(8), dp(8)).apply { marginStart = dp(8); topMargin = dp(8) })
        }

        fun bind(e: InboxEntry) {
            icon.setImageDrawable(NotificationParser.appIcon(context, e.pkg))
            meta.text = "${e.app} · ${DateUtils.getRelativeTimeSpanString(e.posted, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)}" +
                (e.otp?.let { " · OTP $it" } ?: "")
            title.text = e.title.ifBlank { e.app }
            body.text = e.text
            body.visibility = if (e.text.isBlank()) View.GONE else View.VISIBLE
            dot.visibility = if (e.read) View.INVISIBLE else View.VISIBLE
            setOnClickListener { open(e) }
            setOnLongClickListener { copy(e); true }
        }
    }

    private fun dayLabel(t: Long): String {
        val now = Calendar.getInstance(); val c = Calendar.getInstance().apply { timeInMillis = t }
        val sameYear = now.get(Calendar.YEAR) == c.get(Calendar.YEAR)
        return when {
            sameYear && now.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR) -> "Today"
            sameYear && now.get(Calendar.DAY_OF_YEAR) - c.get(Calendar.DAY_OF_YEAR) == 1 -> "Yesterday"
            else -> DateUtils.formatDateTime(this, t, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY)
        }
    }
}
