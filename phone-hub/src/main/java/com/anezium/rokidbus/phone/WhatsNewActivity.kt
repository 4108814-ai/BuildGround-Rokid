package com.anezium.rokidbus.phone

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi

/**
 * The app's own changelog: the pending release in full when an update is
 * waiting, and every published release below it. Rendered from the GitHub
 * payload the update checker already keeps on disk.
 */
class WhatsNewActivity : Activity() {
    private lateinit var content: LinearLayout
    private lateinit var footer: LinearLayout
    private var history: List<NexusAppReleaseNote> = emptyList()
    private val stateListener: () -> Unit = {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                renderFooter()
                render()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG

        content = NexusUi.contentColumn(this, topDp = 0)
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
        footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(10), dp(22), dp(12))
        }
        val root = NexusUi.fixedRoot(this).apply {
            addView(header(), NexusUi.block())
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(footer, NexusUi.block())
        }
        setContentView(root)
        render()
        renderFooter()
        NexusPhoneState.addUpdateListener(stateListener)
        NexusUpdateChecker.create(applicationContext).releaseHistoryAsync { notes ->
            if (isFinishing || isDestroyed) return@releaseHistoryAsync
            history = notes
            render()
        }
        // A fresh install may open this screen before any check has run.
        NexusUpdateManager.checkForUpdates(applicationContext)
    }

    override fun onDestroy() {
        super.onDestroy()
        NexusPhoneState.removeUpdateListener(stateListener)
    }

    private fun header(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(16), dp(22), dp(14))
        addView(
            NexusUi.metaLabel(this@WhatsNewActivity, "‹ Back", NexusUi.INK3).apply {
                letterSpacing = 0.2f
                isClickable = true
                isFocusable = true
                setOnClickListener { finish() }
                background = NexusUi.pressed(this@WhatsNewActivity, Color.TRANSPARENT, 12)
                setPadding(0, dp(8), dp(8), dp(8))
            },
        )
        addView(BusTheme.gap(this@WhatsNewActivity, 8))
        addView(
            NexusUi.hero(this@WhatsNewActivity, 26f).apply {
                val label = "What's new."
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
        addView(BusTheme.gap(this@WhatsNewActivity, 5))
        addView(NexusUi.rowSub(this@WhatsNewActivity, versionLine()))
    }

    private fun versionLine(): String {
        val installed = installedVersionName()
        val pending = NexusPhoneState.availableRelease?.versionName
        return when {
            pending != null && installed != null -> "Rokid Nexus $installed → $pending"
            pending != null -> "Rokid Nexus $pending"
            installed != null -> "Rokid Nexus $installed"
            else -> "Rokid Nexus"
        }
    }

    private fun render() {
        if (!::content.isInitialized) return
        content.removeAllViews()
        val pending = NexusPhoneState.availableRelease
        val pendingNotes = pending?.let { release ->
            release.notes ?: history.firstOrNull { it.versionName == release.versionName }?.notes
        }
        val installed = installedVersionName()

        var earlier = history
        if (pending != null) {
            earlier = earlier.filter { it.versionName != pending.versionName }
            content.addView(
                releaseCard(
                    version = pending.versionName,
                    date = StoreScreens.formatReleaseDate(pending.publishedAt),
                    notes = pendingNotes,
                    highlight = true,
                ),
                NexusUi.block(),
            )
        } else {
            val current = history.firstOrNull { it.versionName == installed } ?: history.firstOrNull()
            if (current != null) {
                earlier = earlier.filter { it.versionName != current.versionName }
                content.addView(
                    releaseCard(
                        version = current.versionName,
                        date = StoreScreens.formatReleaseDate(current.publishedAt),
                        notes = current.notes,
                        highlight = true,
                        tag = if (current.versionName == installed) "You're on this version" else null,
                    ),
                    NexusUi.block(),
                )
            }
        }

        if (content.childCount == 0 && earlier.isEmpty()) {
            content.addView(
                NexusUi.card(this).apply {
                    addView(NexusUi.cardTitle(this@WhatsNewActivity, "No release notes yet"), NexusUi.block())
                    addView(BusTheme.gap(this@WhatsNewActivity, 7))
                    addView(
                        NexusUi.cardBody(
                            this@WhatsNewActivity,
                            "Nexus has not fetched its release feed yet. Check for updates and come back.",
                        ),
                        NexusUi.block(),
                    )
                },
                NexusUi.block(),
            )
            return
        }

        if (earlier.isNotEmpty()) {
            content.addView(BusTheme.gap(this, 22))
            content.addView(NexusUi.sectionLabel(this, "Earlier"), NexusUi.block())
            content.addView(BusTheme.gap(this, 9))
            content.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = NexusUi.bordered(this@WhatsNewActivity, Color.TRANSPARENT, NexusUi.LINE2, 13)
                    earlier.forEachIndexed { index, note ->
                        if (index > 0) {
                            addView(
                                View(this@WhatsNewActivity).apply { setBackgroundColor(NexusUi.LINE2) },
                                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)),
                            )
                        }
                        addView(earlierRow(note), NexusUi.block())
                    }
                },
                NexusUi.block(),
            )
        }
    }

    private fun releaseCard(
        version: String,
        date: String?,
        notes: String?,
        highlight: Boolean,
        tag: String? = null,
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = NexusUi.bordered(
            this@WhatsNewActivity,
            NexusUi.PANEL,
            if (highlight) HIGHLIGHT_STROKE else NexusUi.LINE2,
            13,
        )
        setPadding(dp(14), dp(13), dp(14), dp(13))
        addView(
            LinearLayout(this@WhatsNewActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    NexusUi.metaLabel(this@WhatsNewActivity, version, NexusUi.GREEN).apply { textSize = 11f },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                date?.let { addView(NexusUi.metaLabel(this@WhatsNewActivity, it, NexusUi.INK4)) }
            },
            NexusUi.block(),
        )
        tag?.let {
            addView(BusTheme.gap(this@WhatsNewActivity, 5))
            addView(NexusUi.metaLabel(this@WhatsNewActivity, it, NexusUi.INK3))
        }
        addView(BusTheme.gap(this@WhatsNewActivity, 9))
        addView(
            NexusUi.cardBody(this@WhatsNewActivity, "").apply {
                text = if (notes.isNullOrBlank()) {
                    "No notes were published for this release."
                } else {
                    ReleaseNotesRenderer.render(this@WhatsNewActivity, notes)
                }
            },
            NexusUi.block(),
        )
    }

    private fun earlierRow(note: NexusAppReleaseNote): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        addView(
            LinearLayout(this@WhatsNewActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    NexusUi.metaLabel(this@WhatsNewActivity, note.versionName, NexusUi.INK2).apply { textSize = 11f },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                StoreScreens.formatReleaseDate(note.publishedAt)?.let {
                    addView(NexusUi.metaLabel(this@WhatsNewActivity, it, NexusUi.INK4))
                }
            },
            NexusUi.block(),
        )
        addView(BusTheme.gap(this@WhatsNewActivity, 8))
        addView(
            NexusUi.cardBody(this@WhatsNewActivity, "").apply {
                textSize = 12.5f
                text = ReleaseNotesRenderer.render(this@WhatsNewActivity, note.notes)
            },
            NexusUi.block(),
        )
    }

    private fun renderFooter() {
        if (!::footer.isInitialized) return
        footer.removeAllViews()
        if (!NexusPhoneState.updateAvailable) return
        val label = NexusPhoneState.updateActionLabel()
        val enabled = NexusPhoneState.updateActionEnabled()
        footer.addView(
            Button(this).apply {
                text = label
                textSize = 11.5f
                typeface = Typeface.MONOSPACE
                letterSpacing = 0.1f
                setAllCaps(false)
                stateListAnimator = null
                minHeight = dp(48)
                minimumHeight = dp(48)
                includeFontPadding = false
                isEnabled = enabled
                setTextColor(if (enabled) NexusUi.ON_ACCENT else NexusUi.INK4)
                background = if (enabled) {
                    NexusUi.rounded(this@WhatsNewActivity, NexusUi.GREEN, 12)
                } else {
                    NexusUi.bordered(this@WhatsNewActivity, Color.TRANSPARENT, NexusUi.LINE2, 12)
                }
                setOnClickListener { NexusUpdateManager.performUpdateAction(applicationContext) }
            },
            NexusUi.block(),
        )
    }

    private fun installedVersionName(): String? = runCatching {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
        info.versionName
    }.getOrNull()

    private fun dp(value: Int): Int = NexusUi.dp(this, value)

    companion object {
        private const val HIGHLIGHT_STROKE = 0xFF1E3A29.toInt()

        fun intent(context: Context): Intent = Intent(context, WhatsNewActivity::class.java)
    }
}
