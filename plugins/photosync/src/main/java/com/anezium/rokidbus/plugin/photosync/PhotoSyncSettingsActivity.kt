package com.anezium.rokidbus.plugin.photosync

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi
import com.anezium.rokidbus.shared.MediaSyncBlocker
import com.anezium.rokidbus.shared.MediaSyncMode
import com.anezium.rokidbus.shared.MediaSyncState
import com.anezium.rokidbus.shared.MediaSyncStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One screen: what sync is doing right now, how it should run, and what it has done.
 *
 * Two rules shape the code. Everything that changes while you watch — the headline, the gauge, the
 * counters, the selected mode — is a field that gets patched in place, so a status arriving twice a
 * second never rebuilds the screen or throws away a scroll position. And the screen never waits
 * passively: it asks for status the moment it opens, on every resume, and on a slow heartbeat, so
 * a missed push can't leave it frozen on a stale line.
 */
class PhotoSyncSettingsActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var content: LinearLayout

    private var runtime: PhotoSyncRuntime? = null
    private var unobserveRuntime: (() -> Unit)? = null
    private var unobserveStatus: (() -> Unit)? = null
    private var status: MediaSyncStatus? = null
    private var bound = false
    private var visible = false

    // Live views, patched in place rather than rebuilt.
    private var dotView: View? = null
    private var headlineView: TextView? = null
    private var detailView: TextView? = null
    private var gaugeFill: View? = null
    private var gaugeRest: View? = null
    private var gaugeRow: LinearLayout? = null
    private var galleryValue: TextView? = null
    private var syncButton: Button? = null
    private var deleteSwitch: Switch? = null
    private var deleteNote: TextView? = null
    private val modeRows = LinkedHashMap<MediaSyncMode, ModeRow>()

    private var renderedKey: String? = null

    /** A tapped mode shows as selected immediately; the hub's next push confirms or corrects it. */
    private var pendingMode: MediaSyncMode? = null

    /** True only while controls are being set from a hub push, so echoes never travel back. */
    private var mirroringHubState = false

    private val heartbeat = object : Runnable {
        override fun run() {
            runtime?.refresh()
            if (visible) main.postDelayed(this, HEARTBEAT_MS)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) = attachRuntime()
        override fun onServiceDisconnected(name: ComponentName?) = attachRuntime()
    }

    private class ModeRow(
        val root: LinearLayout,
        val mark: View,
        val title: TextView,
        val subtitle: TextView,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        content = NexusUi.contentColumn(this)
        val root = NexusUi.fixedRoot(this).apply {
            addView(
                NexusUi.pluginHeader(
                    this@PhotoSyncSettingsActivity,
                    // Photos Sync's own glyph — a capture frame with the sync arrow leaving it —
                    // and the same file the hub shows in the plugin list. The shared vocabulary
                    // has nothing for "captures travelling", so falling back to its generic send
                    // icon left a paper plane here and the real mark everywhere else.
                    R.drawable.nexus_glyph_photosync,
                    "Photos Sync",
                    "Glasses captures to your gallery · v$versionLabel",
                ),
                NexusUi.block(),
            )
            addView(
                NexusUi.screen(this@PhotoSyncSettingsActivity, content),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }
        setContentView(root)
        render()
    }

    override fun onStart() {
        super.onStart()
        visible = true
        bound = bindService(
            Intent(this, PhotoSyncPluginService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        unobserveRuntime = PhotoSyncPluginService.observeRuntime { main.post(::attachRuntime) }
    }

    override fun onResume() {
        super.onResume()
        runtime?.refresh()
        main.removeCallbacks(heartbeat)
        main.postDelayed(heartbeat, HEARTBEAT_MS)
    }

    override fun onStop() {
        visible = false
        main.removeCallbacks(heartbeat)
        unobserveRuntime?.invoke()
        unobserveRuntime = null
        unobserveStatus?.invoke()
        unobserveStatus = null
        runtime = null
        if (bound) {
            runCatching { unbindService(connection) }
            bound = false
        }
        super.onStop()
    }

    private fun attachRuntime() {
        val next = PhotoSyncPluginService.runtime()
        if (next === runtime) return
        unobserveStatus?.invoke()
        runtime = next
        unobserveStatus = next?.observe { updated -> main.post { onStatus(updated) } }
        next?.refresh()
        if (next == null) onStatus(null)
    }

    private fun onStatus(updated: MediaSyncStatus?) {
        status = updated
        if (updated != null && updated.settings.mode == pendingMode) pendingMode = null
        val key = structureKey(updated)
        if (key == renderedKey) {
            applyStatus(updated)
            return
        }
        render()
    }

    /**
     * Only what changes the shape of the screen belongs here. Progress, headlines, counters and
     * the selected mode are all patched in place, so a transfer pushing status every couple of
     * seconds never rebuilds anything.
     */
    private fun structureKey(current: MediaSyncStatus?): String = when (current) {
        null -> "waiting"
        else -> listOf(
            current.deletionSupported == false,
            current.history.size,
            current.history.firstOrNull()?.finishedAtMillis ?: 0L,
        ).joinToString("|")
    }

    private fun render() {
        val current = status
        renderedKey = structureKey(current)
        modeRows.clear()
        content.removeAllViews()

        content.addView(
            NexusUi.cardBody(
                this,
                "Captures from the glasses land in your phone gallery, in the same Hi Rokid " +
                    "album. They travel over the glasses connection — no Wi-Fi needed.",
            ),
            NexusUi.block(),
        )
        content.addView(BusTheme.gap(this, 20))

        content.addView(NexusUi.sectionRow(this, "Status"), NexusUi.block())
        content.addView(BusTheme.gap(this, 10))
        content.addView(statusCard(), NexusUi.block())

        content.addView(BusTheme.gap(this, 24))
        content.addView(NexusUi.sectionRow(this, "When to sync"), NexusUi.block())
        content.addView(BusTheme.gap(this, 10))
        MODES.forEach { (mode, copy) ->
            val row = modeRow(mode, copy.first, copy.second)
            modeRows[mode] = row
            content.addView(row.root, NexusUi.block())
            content.addView(BusTheme.gap(this, 8))
        }

        content.addView(BusTheme.gap(this, 14))
        content.addView(NexusUi.sectionRow(this, "Glasses storage"), NexusUi.block())
        content.addView(BusTheme.gap(this, 10))
        content.addView(deleteCard(current), NexusUi.block())

        content.addView(BusTheme.gap(this, 24))
        content.addView(
            NexusUi.sectionRow(this, "Recent syncs", current?.history?.size?.takeIf { it > 0 }?.let { "$it" }),
            NexusUi.block(),
        )
        content.addView(BusTheme.gap(this, 10))
        content.addView(historyCard(current), NexusUi.block())

        content.addView(BusTheme.gap(this, 24))
        content.addView(NexusUi.sectionRow(this, "Plugin"), NexusUi.block())
        content.addView(BusTheme.gap(this, 10))
        content.addView(
            NexusUi.uninstallCard(this, "Photos Sync") {
                startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
            },
            NexusUi.block(),
        )

        applyStatus(current)
    }

    private fun statusCard(): LinearLayout = NexusUi.card(this).apply {
        val dot = NexusUi.dot(this@PhotoSyncSettingsActivity).also { dotView = it }
        val headline = NexusUi.statusLine(this@PhotoSyncSettingsActivity).apply {
            textSize = 15f
            setTextColor(NexusUi.INK)
            isSingleLine = true
        }.also { headlineView = it }

        addView(
            LinearLayout(this@PhotoSyncSettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                // A bare View measures wrap_content as match_parent, so the dot needs a real size
                // or it swallows the whole card and squeezes the headline to nothing.
                addView(
                    dot,
                    LinearLayout.LayoutParams(
                        NexusUi.dp(this@PhotoSyncSettingsActivity, 9),
                        NexusUi.dp(this@PhotoSyncSettingsActivity, 9),
                    ),
                )
                addView(
                    headline,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = NexusUi.dp(this@PhotoSyncSettingsActivity, 10)
                    },
                )
            },
            NexusUi.block(),
        )

        addView(BusTheme.gap(this@PhotoSyncSettingsActivity, 7))
        addView(
            NexusUi.rowSub(this@PhotoSyncSettingsActivity, "").also { detailView = it },
            NexusUi.block(),
        )

        // Gauge and its spacing hide as one, or an idle card keeps a hole where they were.
        addView(
            LinearLayout(this@PhotoSyncSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                gaugeRow = this
                addView(BusTheme.gap(this@PhotoSyncSettingsActivity, 12))
                addView(gauge(), NexusUi.block())
            },
            NexusUi.block(),
        )

        addView(BusTheme.gap(this@PhotoSyncSettingsActivity, 14))
        addView(NexusUi.divider(this@PhotoSyncSettingsActivity))
        addView(BusTheme.gap(this@PhotoSyncSettingsActivity, 14))

        addView(
            LinearLayout(this@PhotoSyncSettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    NexusUi.rowLabel(this@PhotoSyncSettingsActivity, "In your gallery"),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(
                    NexusUi.rowValue(this@PhotoSyncSettingsActivity).also { galleryValue = it },
                )
            },
            NexusUi.block(),
        )

        addView(BusTheme.gap(this@PhotoSyncSettingsActivity, 14))
        addView(
            NexusUi.pillButton(this@PhotoSyncSettingsActivity, "Sync now").apply {
                syncButton = this
                setOnClickListener {
                    runtime?.syncNow()
                    isEnabled = false
                    alpha = DISABLED_ALPHA
                    headlineView?.text = "Starting…"
                }
            },
            NexusUi.block(),
        )
    }

    /** A two-part weighted track: the fill grows, the remainder shrinks, no measuring needed. */
    private fun gauge(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = NexusUi.rounded(this@PhotoSyncSettingsActivity, NexusUi.LINE, 3)
        val height = NexusUi.dp(this@PhotoSyncSettingsActivity, 5)
        addView(
            View(this@PhotoSyncSettingsActivity).apply {
                background = NexusUi.rounded(this@PhotoSyncSettingsActivity, NexusUi.GREEN, 3)
                gaugeFill = this
            },
            LinearLayout.LayoutParams(0, height, 0f),
        )
        addView(
            View(this@PhotoSyncSettingsActivity).apply { gaugeRest = this },
            LinearLayout.LayoutParams(0, height, 1f),
        )
    }

    private fun modeRow(mode: MediaSyncMode, title: String, subtitle: String): ModeRow {
        val mark = View(this)
        val titleView = NexusUi.rowTitle(this, title)
        val subtitleView = wrappingSub(subtitle)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                NexusUi.dp(this@PhotoSyncSettingsActivity, 15),
                NexusUi.dp(this@PhotoSyncSettingsActivity, 13),
                NexusUi.dp(this@PhotoSyncSettingsActivity, 15),
                NexusUi.dp(this@PhotoSyncSettingsActivity, 13),
            )
            isClickable = true
            addView(
                mark,
                LinearLayout.LayoutParams(
                    NexusUi.dp(this@PhotoSyncSettingsActivity, 10),
                    NexusUi.dp(this@PhotoSyncSettingsActivity, 10),
                ),
            )
            addView(
                LinearLayout(this@PhotoSyncSettingsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(titleView)
                    addView(BusTheme.gap(this@PhotoSyncSettingsActivity, 3))
                    addView(subtitleView)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = NexusUi.dp(this@PhotoSyncSettingsActivity, 13)
                },
            )
            setOnClickListener {
                if (runtime == null) return@setOnClickListener
                pendingMode = mode
                applySelectedMode(mode)
                runtime?.setSyncMode(mode)
            }
        }
        return ModeRow(root, mark, titleView, subtitleView)
    }

    private fun deleteCard(current: MediaSyncStatus?): LinearLayout = NexusUi.card(this).apply {
        addView(
            LinearLayout(this@PhotoSyncSettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    LinearLayout(this@PhotoSyncSettingsActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(
                            NexusUi.rowTitle(
                                this@PhotoSyncSettingsActivity,
                                "Delete from glasses after sync",
                            ),
                        )
                        addView(BusTheme.gap(this@PhotoSyncSettingsActivity, 3))
                        addView(
                            wrappingSub(
                                "Frees space on the glasses. A capture is removed only once it " +
                                    "is safely in your gallery.",
                            ),
                        )
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = NexusUi.dp(this@PhotoSyncSettingsActivity, 12)
                    },
                )
                addView(
                    Switch(this@PhotoSyncSettingsActivity).apply {
                        deleteSwitch = this
                        thumbTintList = ColorStateList(
                            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                            intArrayOf(NexusUi.GREEN, NexusUi.INK3),
                        )
                        trackTintList = ColorStateList(
                            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                            intArrayOf(NexusUi.GREEN_DIM, NexusUi.LINE),
                        )
                        // Deleting the wearer's captures is the one destructive setting here, so
                        // it may only ever change from a real gesture. `isPressed` is too loose a
                        // guard for that — the flag below is set only while the screen mirrors a
                        // hub push, so an echo can never travel back as a fresh instruction.
                        setOnCheckedChangeListener { _, value ->
                            if (!mirroringHubState) runtime?.setDeleteAfterSync(value)
                        }
                    },
                )
            },
            NexusUi.block(),
        )
        if (current?.deletionSupported == false) {
            addView(BusTheme.gap(this@PhotoSyncSettingsActivity, 10))
            addView(
                wrappingSub("The glasses refused the last delete — captures stayed on the glasses.")
                    .apply {
                        setTextColor(NexusUi.AMBER)
                        deleteNote = this
                    },
                NexusUi.block(),
            )
        } else {
            deleteNote = null
        }
    }

    private fun historyCard(current: MediaSyncStatus?): LinearLayout = NexusUi.card(this).apply {
        val history = current?.history.orEmpty()
        if (history.isEmpty()) {
            addView(
                NexusUi.cardBody(
                    this@PhotoSyncSettingsActivity,
                    "Nothing yet. The first sync copies everything already on the glasses.",
                ),
                NexusUi.block(),
            )
            return@apply
        }
        history.take(MAX_HISTORY_ROWS).forEachIndexed { index, run ->
            if (index > 0) {
                addView(BusTheme.gap(this@PhotoSyncSettingsActivity, 11))
                addView(NexusUi.divider(this@PhotoSyncSettingsActivity))
                addView(BusTheme.gap(this@PhotoSyncSettingsActivity, 11))
            }
            addView(
                LinearLayout(this@PhotoSyncSettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        NexusUi.rowLabel(
                            this@PhotoSyncSettingsActivity,
                            PhotoSyncCopy.describe(run),
                        ),
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    addView(
                        NexusUi.metaLabel(
                            this@PhotoSyncSettingsActivity,
                            timestamp(run.finishedAtMillis),
                        ),
                    )
                },
                NexusUi.block(),
            )
        }
    }

    private fun applyStatus(current: MediaSyncStatus?) {
        mirroringHubState = true
        try {
            mirrorStatus(current)
        } finally {
            mirroringHubState = false
        }
    }

    private fun mirrorStatus(current: MediaSyncStatus?) {
        headlineView?.text = PhotoSyncCopy.headline(current)
        dotView?.let { NexusUi.setDotColor(it, dotColor(current)) }

        val detail = PhotoSyncCopy.detail(current)
        detailView?.apply {
            text = detail.orEmpty()
            visibility = if (detail == null) View.GONE else View.VISIBLE
        }

        val transferring = current?.state == MediaSyncState.TRANSFERRING
        val total = current?.progress?.bytesTotal ?: 0L
        val fraction = if (transferring && total > 0L) {
            (current!!.progress.bytesDone.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        gaugeRow?.visibility = if (transferring) View.VISIBLE else View.GONE
        setGauge(fraction)

        galleryValue?.text = current?.let {
            "${it.syncedTotal} ${if (it.syncedTotal == 1) "file" else "files"}"
        } ?: "—"

        syncButton?.let { button ->
            val ready = current != null && current.state == MediaSyncState.IDLE
            button.text = if (transferring) "Syncing…" else "Sync now"
            button.isEnabled = ready
            button.alpha = if (ready) 1f else DISABLED_ALPHA
        }

        applySelectedMode(pendingMode ?: current?.settings?.mode)
        deleteSwitch?.apply {
            isEnabled = current != null
            alpha = if (current != null) 1f else DISABLED_ALPHA
            val target = current?.settings?.deleteAfterSync ?: false
            if (isChecked != target) isChecked = target
        }
    }

    private fun applySelectedMode(selected: MediaSyncMode?) {
        modeRows.forEach { (mode, row) ->
            val active = mode == selected
            row.mark.background = NexusUi.rounded(
                this,
                if (active) NexusUi.GREEN else NexusUi.LINE,
                5,
            )
            row.title.setTextColor(if (active) NexusUi.INK else NexusUi.INK2)
            row.subtitle.setTextColor(if (active) NexusUi.INK2 else NexusUi.INK3)
            row.root.background = NexusUi.bordered(
                this,
                if (active) NexusUi.CARD else NexusUi.PANEL,
                if (active) NexusUi.GREEN_DIM else NexusUi.LINE,
                15,
            )
            row.root.alpha = if (runtime == null) DISABLED_ALPHA else 1f
        }
    }

    private fun setGauge(fraction: Float) {
        val fill = gaugeFill?.layoutParams as? LinearLayout.LayoutParams ?: return
        val rest = gaugeRest?.layoutParams as? LinearLayout.LayoutParams ?: return
        fill.weight = fraction
        rest.weight = 1f - fraction
        gaugeFill?.layoutParams = fill
        gaugeRest?.layoutParams = rest
    }

    private fun dotColor(current: MediaSyncStatus?): Int = when {
        current == null -> NexusUi.INK4
        current.state != MediaSyncState.IDLE -> NexusUi.GREEN
        current.blocker == null -> NexusUi.GREEN_DIM
        current.blocker in QUIET_BLOCKERS -> NexusUi.INK3
        else -> NexusUi.AMBER
    }

    /** [NexusUi.rowSub] is single-line by design; these explanations need to wrap. */
    private fun wrappingSub(label: String): TextView =
        NexusUi.rowSub(this, label).apply {
            isSingleLine = false
            maxLines = 3
            ellipsize = null
            setLineSpacing(NexusUi.dp(this@PhotoSyncSettingsActivity, 2).toFloat(), 1f)
        }

    private fun timestamp(millis: Long): String =
        if (millis <= 0L) "—" else timeFormat.format(Date(millis))

    private val versionLabel: String
        get() = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        }.getOrDefault("").ifBlank { "1.0" }

    private companion object {
        const val HEARTBEAT_MS = 4_000L
        const val DISABLED_ALPHA = 0.45f
        const val MAX_HISTORY_ROWS = 4

        val timeFormat = SimpleDateFormat("d MMM · HH:mm", Locale.getDefault())

        /** Nothing is wrong, sync simply has no reason to run — the dot stays quiet, not amber. */
        val QUIET_BLOCKERS = setOf(
            MediaSyncBlocker.NOTHING_PENDING,
            MediaSyncBlocker.NOT_CHARGING,
            MediaSyncBlocker.AUTO_SYNC_OFF,
            MediaSyncBlocker.LINK_DOWN,
            MediaSyncBlocker.CAMERA_ACTIVE,
        )

        val MODES = listOf(
            MediaSyncMode.ALWAYS to ("Always" to "Syncs as soon as you capture"),
            MediaSyncMode.CHARGING to ("While charging" to "Syncs when the glasses are on the charger"),
            MediaSyncMode.MANUAL to ("Manual only" to "Syncs only when you tap Sync now"),
        )
    }
}
