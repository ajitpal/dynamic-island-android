package com.ajit.dynamicisland

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.ajit.dynamicisland.screens.SettingsPages
import com.ajit.dynamicisland.ui.hig.Hig

/** A Settings sub-page: back button, large title, grouped rows. */
class PageActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_KEY = "key"
        const val LOOK = SettingsPages.PAGE_LOOK; const val SHOWS = SettingsPages.PAGE_SHOWS; const val BEHAVIOUR = SettingsPages.PAGE_BEHAVIOUR
        const val APPS = SettingsPages.PAGE_APPS; const val TOOLS = SettingsPages.PAGE_TOOLS; const val INBOX = SettingsPages.PAGE_INBOX
        const val SMART = SettingsPages.PAGE_SMART; const val POPUPS = SettingsPages.PAGE_POPUPS; const val GESTURES = SettingsPages.PAGE_GESTURES
        const val DIAGNOSTICS = SettingsPages.PAGE_DIAGNOSTICS
        fun open(ctx: Context, key: String) = ctx.startActivity(Intent(ctx, PageActivity::class.java).putExtra(EXTRA_KEY, key))
    }

    private lateinit var pages: SettingsPages
    private lateinit var key: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        key = intent.getStringExtra(EXTRA_KEY) ?: SettingsPages.PAGE_LOOK
        val hig = Hig(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(hig.bg) }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = hig.dp(48); setPadding(hig.dp(4), 0, 0, 0) }
        val back = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = hig.dp(48); setPadding(hig.dp(4), 0, hig.dp(12), 0)
            addView(ImageView(this@PageActivity).apply { setImageResource(R.drawable.ic_back); setColorFilter(hig.blue) }, LinearLayout.LayoutParams(hig.dp(22), hig.dp(22)))
            addView(hig.text("Settings", Hig.Style.BODY, hig.blue))
            isClickable = true; contentDescription = "Back"; setOnClickListener { finish() }
        }
        bar.addView(back)
        root.addView(bar, hig.mp())
        val page = hig.page(SettingsPages.TITLES[key] ?: "Settings", topPad = 0)
        root.addView(page.root, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        pages = SettingsPages(this, hig, LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also { page.column.addView(it, hig.mp()) })
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, i -> val b = i.getInsets(WindowInsetsCompat.Type.systemBars()); v.setPadding(0, b.top, 0, b.bottom); i }
        pages.build(key)
    }

    override fun onResume() {
        super.onResume()
        if (key == SettingsPages.PAGE_TOOLS || key == SettingsPages.PAGE_DIAGNOSTICS || key == SettingsPages.PAGE_SMART) pages.build(key)
    }
}
