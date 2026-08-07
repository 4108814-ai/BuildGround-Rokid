package com.anezium.rokidbus.phone

import android.app.Activity
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi

/**
 * The Store list is an index: one dense row per plugin, everything else —
 * summary, screenshots, changelog, install — lives on the detail screen.
 */
class StoreActivity : Activity() {
    private lateinit var list: LinearLayout
    private lateinit var chipRow: LinearLayout
    private lateinit var headerSub: TextView
    private lateinit var registryClient: RegistryClient
    private lateinit var iconLoader: StoreIconLoader
    private var registrySnapshot: RegistrySnapshot? = null
    private var registryLoading = true
    private var registryFailure: Throwable? = null
    private var selectedCategory = ALL_CATEGORY
    private val hostVersionCode: Long by lazy {
        StoreScreens.installedVersionCodes(packageManager, setOf(packageName))[packageName] ?: 0L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registryClient = RegistryClient.create(applicationContext)
        iconLoader = StoreIconLoader(applicationContext)
        buildUi()
        refreshRegistry()
    }

    override fun onResume() {
        super.onResume()
        resumeRecoveredPluginInstall()
        renderCatalog()
    }

    private fun refreshRegistry() {
        registryLoading = true
        registryFailure = null
        renderCatalog()
        registryClient.refresh { result ->
            if (isFinishing || isDestroyed) return@refresh
            registryLoading = false
            when (result) {
                is RegistryLoadResult.Success -> registrySnapshot = result.snapshot
                is RegistryLoadResult.Failure -> {
                    registrySnapshot = null
                    registryFailure = result.error
                }
            }
            renderCatalog()
        }
    }

    private fun buildUi() {
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        list = NexusUi.contentColumn(this, topDp = 8)
        chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(
                NexusUi.dp(this@StoreActivity, 22),
                NexusUi.dp(this@StoreActivity, 16),
                NexusUi.dp(this@StoreActivity, 22),
                NexusUi.dp(this@StoreActivity, 4),
            )
        }

        val scroll = ScrollView(this).apply {
            setBackgroundColor(NexusUi.BG)
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(
                list,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        val categories = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipRow)
        }
        val root = NexusUi.fixedRoot(this).apply {
            addView(storeHeader(), NexusUi.block())
            addView(categories, NexusUi.block())
            addView(
                scroll,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }
        setContentView(root)
        renderCatalog()
    }

    private fun renderCatalog() {
        if (!::list.isInitialized) return
        val feed = registrySnapshot?.feed ?: RegistryFeed(RegistryClient.SUPPORTED_VERSION, emptyList())
        val catalog = StoreScreens.buildCatalog(this, feed, hostVersionCode) { Log.w(TAG, it) }

        renderHeaderSub(catalog)
        renderChips(feed)
        list.removeAllViews()
        renderCatalogueStatus(feed)
        val visible = catalog.entries.filter { entry ->
            selectedCategory == ALL_CATEGORY || entry.category == selectedCategory
        }
        if (visible.isEmpty() && feed.plugins.isNotEmpty()) {
            addEmptyCard("No plugins here", "Choose another category to see available plugins.")
        }
        visible.forEachIndexed { index, entry ->
            if (index > 0 || list.childCount > 0) list.addView(BusTheme.gap(this, 7))
            list.addView(storeRow(entry), NexusUi.block())
        }
    }

    private fun renderHeaderSub(catalog: StoreCatalog) {
        if (!::headerSub.isInitialized) return
        val total = catalog.entries.size
        if (total == 0) {
            headerSub.text = "Plugins for your glasses"
            return
        }
        val installed = catalog.entries.count {
            it.state == StoreEntryState.INSTALLED ||
                it.state == StoreEntryState.SIDELOADED ||
                it.state == StoreEntryState.UPDATE_AVAILABLE
        }
        val updates = catalog.entries.count { it.state == StoreEntryState.UPDATE_AVAILABLE }
        headerSub.text = buildString {
            append(total).append(" plugin").append(if (total == 1) "" else "s")
            append(" · ").append(installed).append(" installed")
            if (updates > 0) append(" · ").append(updates).append(" update").append(if (updates == 1) "" else "s")
        }
    }

    private fun renderCatalogueStatus(feed: RegistryFeed) {
        when {
            registryLoading && registrySnapshot == null -> addEmptyCard(
                "Refreshing catalogue",
                "Checking the Nexus plugin registry. Installed plugins remain available below.",
            )
            registryFailure != null && registrySnapshot == null -> addEmptyCard(
                "Catalogue unavailable",
                "Nexus could not reach the registry and no saved catalogue is available. Try again later.",
                action = "Retry" to ::refreshRegistry,
            )
            feed.plugins.isEmpty() -> addEmptyCard(
                "Catalogue is ready",
                "No registry plugins have been published yet. Installed plugins remain available below.",
            )
        }
    }

    private fun renderChips(feed: RegistryFeed) {
        val categories = listOf(ALL_CATEGORY) + feed.plugins
            .map(RegistryPlugin::category)
            .filter(String::isNotBlank)
            .distinct()
            .sorted()
        if (selectedCategory !in categories) selectedCategory = ALL_CATEGORY
        chipRow.removeAllViews()
        categories.forEachIndexed { index, label ->
            chipRow.addView(
                chip(label, selected = label == selectedCategory),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    if (index > 0) marginStart = NexusUi.dp(this@StoreActivity, 8)
                },
            )
        }
    }

    private fun addEmptyCard(
        title: String,
        body: String,
        action: Pair<String, () -> Unit>? = null,
    ) {
        if (list.childCount > 0) list.addView(BusTheme.gap(this, 10))
        list.addView(
            NexusUi.card(this).apply {
                addView(NexusUi.cardTitle(this@StoreActivity, title), NexusUi.block())
                addView(BusTheme.gap(this@StoreActivity, 7))
                addView(NexusUi.cardBody(this@StoreActivity, body), NexusUi.block())
                action?.let { (label, onClick) ->
                    addView(BusTheme.gap(this@StoreActivity, 12))
                    addView(
                        NexusUi.outlinePillButton(this@StoreActivity, label).apply {
                            setOnClickListener { onClick() }
                        },
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ),
                    )
                }
            },
            NexusUi.block(),
        )
    }

    private fun storeRow(entry: StoreEntry): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rowBackground(highlight = entry.state == StoreEntryState.UPDATE_AVAILABLE)
        setPadding(
            NexusUi.dp(this@StoreActivity, 12),
            NexusUi.dp(this@StoreActivity, 10),
            NexusUi.dp(this@StoreActivity, 13),
            NexusUi.dp(this@StoreActivity, 10),
        )
        isClickable = true
        isFocusable = true
        setOnClickListener {
            startActivity(StorePluginDetailActivity.intent(this@StoreActivity, entry.id))
        }
        addView(StoreScreens.iconView(this@StoreActivity, iconLoader, entry, 32))
        addView(
            LinearLayout(this@StoreActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    NexusUi.rowTitle(this@StoreActivity, entry.displayName).apply { maxLines = 1 },
                )
                addView(BusTheme.gap(this@StoreActivity, 3))
                addView(
                    NexusUi.metaLabel(this@StoreActivity, rowMeta(entry), NexusUi.INK3).apply {
                        textSize = 9f
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    },
                )
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = NexusUi.dp(this@StoreActivity, 11)
            },
        )
        val (stateLabel, stateColor) = rowState(entry)
        addView(
            NexusUi.metaLabel(this@StoreActivity, stateLabel, stateColor).apply {
                textSize = 9.5f
            },
        )
        addView(
            NexusUi.chevron(this@StoreActivity),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = NexusUi.dp(this@StoreActivity, 9) },
        )
    }

    private fun rowBackground(highlight: Boolean): StateListDrawable =
        StateListDrawable().apply {
            val stroke = if (highlight) UPDATE_STROKE else NexusUi.LINE2
            addState(
                intArrayOf(android.R.attr.state_pressed),
                NexusUi.bordered(this@StoreActivity, NexusUi.CARD_PRESSED, stroke, 12),
            )
            addState(intArrayOf(), NexusUi.bordered(this@StoreActivity, NexusUi.PANEL, stroke, 12))
        }

    private fun rowMeta(entry: StoreEntry): String {
        val author = entry.registryAuthor?.takeIf(String::isNotBlank)
        val plugin = entry.registryPlugin
        return when (entry.state) {
            StoreEntryState.AVAILABLE -> listOfNotNull(
                author,
                StoreScreens.formatSize(plugin?.artifact?.sizeBytes) ?: entry.category,
            ).joinToString(" · ")
            StoreEntryState.UPDATE_AVAILABLE -> listOfNotNull(
                author,
                plugin?.artifact?.versionName?.let { "$it available" },
            ).joinToString(" · ")
            StoreEntryState.INSTALLED -> listOfNotNull(
                author,
                if (entry.updateBlockedByHost) "update needs newer Nexus" else plugin?.artifact?.versionName,
            ).joinToString(" · ").ifBlank { StoreScreens.grantLabel(entry.localGrantState) }
            StoreEntryState.SIDELOADED -> "Local · ${StoreScreens.grantLabel(entry.localGrantState)}"
            StoreEntryState.REQUIRES_HOST -> listOfNotNull(author, "needs a Nexus update").joinToString(" · ")
        }
    }

    private fun rowState(entry: StoreEntry): Pair<String, Int> = when (entry.state) {
        StoreEntryState.AVAILABLE -> "Get" to NexusUi.GREEN
        StoreEntryState.UPDATE_AVAILABLE -> "Update" to NexusUi.AMBER
        StoreEntryState.REQUIRES_HOST -> "Held" to NexusUi.INK4
        StoreEntryState.INSTALLED,
        StoreEntryState.SIDELOADED,
        -> if (entry.localGrantState in REVIEW_STATES) {
            "Review" to NexusUi.AMBER
        } else {
            "✓" to NexusUi.INK4
        }
    }

    private fun storeHeader(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(
            NexusUi.dp(this@StoreActivity, 22),
            NexusUi.dp(this@StoreActivity, 16),
            NexusUi.dp(this@StoreActivity, 22),
            0,
        )
        addView(
            NexusUi.metaLabel(this@StoreActivity, "‹ Home", NexusUi.INK3).apply {
                letterSpacing = 0.2f
                isClickable = true
                isFocusable = true
                setOnClickListener { finish() }
                background = NexusUi.pressed(this@StoreActivity, android.graphics.Color.TRANSPARENT, 12)
                setPadding(0, NexusUi.dp(this@StoreActivity, 8), NexusUi.dp(this@StoreActivity, 8), NexusUi.dp(this@StoreActivity, 8))
            },
        )
        addView(BusTheme.gap(this@StoreActivity, 8))
        addView(
            NexusUi.hero(this@StoreActivity, 30f).apply {
                val label = "Store."
                text = SpannableString(label).apply {
                    setSpan(
                        ForegroundColorSpan(NexusUi.GREEN),
                        label.lastIndex,
                        label.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            },
        )
        addView(BusTheme.gap(this@StoreActivity, 5))
        headerSub = NexusUi.rowSub(this@StoreActivity, "Plugins for your glasses")
        addView(headerSub)
    }

    private fun chip(label: String, selected: Boolean): TextView =
        NexusUi.metaLabel(this, label, if (selected) NexusUi.ON_ACCENT else NexusUi.INK2).apply {
            setPadding(
                NexusUi.dp(this@StoreActivity, 13),
                NexusUi.dp(this@StoreActivity, 8),
                NexusUi.dp(this@StoreActivity, 13),
                NexusUi.dp(this@StoreActivity, 8),
            )
            background = if (selected) {
                NexusUi.rounded(this@StoreActivity, NexusUi.GREEN, 20)
            } else {
                NexusUi.bordered(this@StoreActivity, android.graphics.Color.TRANSPARENT, NexusUi.LINE2, 20)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                selectedCategory = label
                renderCatalog()
            }
        }

    companion object {
        private const val TAG = "NexusStore"
        private const val ALL_CATEGORY = "All"
        private const val UPDATE_STROKE = 0xFF3A2E16.toInt()
        private val REVIEW_STATES = setOf(
            PluginCatalogState.PENDING,
            PluginCatalogState.DENIED,
            PluginCatalogState.DISABLED,
            PluginCatalogState.MISSING_CAPABILITY,
            PluginCatalogState.INVALID,
        )
    }
}
