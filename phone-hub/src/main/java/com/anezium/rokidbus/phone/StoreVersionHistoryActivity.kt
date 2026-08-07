package com.anezium.rokidbus.phone

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi

/** Every published release of one plugin, rendered from the registry feed. */
class StoreVersionHistoryActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pluginId = intent.getStringExtra(EXTRA_PLUGIN_ID) ?: run {
            finish()
            return
        }
        val plugin = RegistryClient.create(applicationContext)
            .cachedSnapshot()
            ?.feed
            ?.plugins
            ?.singleOrNull { it.nexus.pluginId == pluginId }
        if (plugin == null) {
            finish()
            return
        }
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG

        val content = NexusUi.contentColumn(this, topDp = 0)
        content.addView(
            NexusUi.hero(this, 22f).apply { text = "Version history" },
            NexusUi.block(),
        )
        content.addView(BusTheme.gap(this, 5))
        content.addView(
            NexusUi.rowSub(
                this,
                "${plugin.name} · ${plugin.releases.size} release${if (plugin.releases.size == 1) "" else "s"}",
            ),
            NexusUi.block(),
        )
        content.addView(BusTheme.gap(this, 16))
        content.addView(releaseList(plugin), NexusUi.block())

        val scroll = ScrollView(this).apply {
            setBackgroundColor(NexusUi.BG)
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        val root = NexusUi.fixedRoot(this).apply {
            addView(backRow(plugin.name), NexusUi.block())
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
    }

    private fun backRow(pluginName: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(NexusUi.dp(context, 22), NexusUi.dp(context, 16), NexusUi.dp(context, 22), NexusUi.dp(context, 8))
        addView(
            NexusUi.metaLabel(this@StoreVersionHistoryActivity, "‹ $pluginName", NexusUi.INK3).apply {
                letterSpacing = 0.2f
                isClickable = true
                isFocusable = true
                setOnClickListener { finish() }
                background = NexusUi.pressed(this@StoreVersionHistoryActivity, Color.TRANSPARENT, 12)
                setPadding(0, NexusUi.dp(context, 8), NexusUi.dp(context, 8), NexusUi.dp(context, 8))
            },
        )
    }

    private fun releaseList(plugin: RegistryPlugin): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = NexusUi.bordered(this@StoreVersionHistoryActivity, Color.TRANSPARENT, NexusUi.LINE2, 13)
        plugin.releases.forEachIndexed { index, release ->
            if (index > 0) {
                addView(
                    View(this@StoreVersionHistoryActivity).apply { setBackgroundColor(NexusUi.LINE2) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, NexusUi.dp(context, 1)),
                )
            }
            addView(releaseRow(release, latest = index == 0), NexusUi.block())
        }
    }

    private fun releaseRow(release: RegistryRelease, latest: Boolean): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(NexusUi.dp(context, 14), NexusUi.dp(context, 12), NexusUi.dp(context, 14), NexusUi.dp(context, 12))
        addView(
            LinearLayout(this@StoreVersionHistoryActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    NexusUi.metaLabel(
                        this@StoreVersionHistoryActivity,
                        release.version,
                        if (latest) NexusUi.GREEN else NexusUi.INK2,
                    ).apply { textSize = 11f },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                StoreScreens.formatReleaseDate(release.date)?.let { date ->
                    addView(NexusUi.metaLabel(this@StoreVersionHistoryActivity, date, NexusUi.INK4))
                }
            },
            NexusUi.block(),
        )
        val notes = ReleaseNotesRenderer.render(this@StoreVersionHistoryActivity, release.notes)
        if (notes.isNotEmpty()) {
            addView(BusTheme.gap(this@StoreVersionHistoryActivity, 8))
            addView(
                NexusUi.cardBody(this@StoreVersionHistoryActivity, "").apply {
                    textSize = 12.5f
                    text = notes
                },
                NexusUi.block(),
            )
        }
    }

    companion object {
        private const val EXTRA_PLUGIN_ID = "plugin_id"

        fun intent(context: Context, pluginId: String): Intent =
            Intent(context, StoreVersionHistoryActivity::class.java).putExtra(EXTRA_PLUGIN_ID, pluginId)
    }
}
