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
import com.anezium.rokidbus.client.ui.NexusPluginIcons
import com.anezium.rokidbus.client.ui.NexusUi
import com.anezium.rokidbus.shared.MediaSyncBlocker
import com.anezium.rokidbus.shared.MediaSyncMode
import com.anezium.rokidbus.shared.MediaSyncState
import com.anezium.rokidbus.shared.MediaSyncStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One screen: what sync is doing, the two switches that govern it, and the recent runs.
 *
 * Binding the plugin service while this screen is visible is what gives the screen a live bus
 * registration; the moment it closes the plugin goes dormant again while the hub keeps syncing.
 */
class PhotoSyncSettingsActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var content: LinearLayout

    private var runtime: PhotoSyncRuntime? = null
    private var unobserveRuntime: (() -> Unit)? = null
    private var unobserveStatus: (() -> Unit)? = null
    private var status: MediaSyncStatus? = null
    private var bound = false

    // Progress arrives once per file. Patching the status card in place stops the screen from
    // rebuilding — and throwing away the wearer's scroll position — several times a second.
    private var renderedKey: String? = null
    private var headlineView: TextView? = null
    private var detailView: TextView? = null
    private var dotView: View? = null
    private var syncedValue: TextView? = null
    private var syncButton: Button? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) = attachRuntime()
        override fun onServiceDisconnected(name: ComponentName?) = attachRuntime()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        content = NexusUi.contentColumn(this)
        val root = NexusUi.fixedRoot(this).apply {
            addView(
                NexusUi.pluginHeader(
                    this@PhotoSyncSettingsActivity,
                    NexusPluginIcons.drawableFor("send", "photosync"),
                    "Photo Sync",
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
        renderedKey = structureKey(null)
        render()
    }

    override fun onStart() {
        super.onStart()
        bound = bindService(
            Intent(this, PhotoSyncPluginService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        unobserveRuntime = PhotoSyncPluginService.observeRuntime { main.post(::attachRuntime) }
    }

    override fun onStop() {
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
        val key = structureKey(updated)
        if (key == renderedKey) {
            applyStatus(updated)
            return
        }
        renderedKey = key
        render()
    }

    /** Only these fields change the shape of the screen; everything else is a text swap. */
    private fun structureKey(current: MediaSyncStatus?): String = when (current) {
        null -> "disconnected"
        else -> listOf(
            current.settings.mode,
            current.settings.deleteAfterSync,
            current.deletionSupported,
            current.history.joinToString(",") { "${it.finishedAtMillis}/${it.result.wireValue}" },
        ).joinToString("|")
    }

    private fun render() {
        val current = status
        content.removeAllViews()
        content.addView(
            NexusUi.cardBody(
                this,
                "Photos and videos you capture on the glasses copy themselves into your phone " +
                    "gallery, in the same Hi Rokid album. They travel over the glasses " +
                    "connection, so no Wi-Fi is needed.",
            ),
            NexusUi.block(),
        )
        content.addView(BusTheme.gap(this, 18))

        content.addView(NexusUi.sectionRow(this, "Status"), NexusUi.block())
        content.addView(BusTheme.gap(this, 10))
        content.addView(statusCard(), NexusUi.block())

        content.addView(BusTheme.gap(this, 22))
        content.addView(NexusUi.sectionRow(this, "Automatic"), NexusUi.block())
        content.addView(BusTheme.gap(this, 10))
        val mode = current?.settings?.mode ?: MediaSyncMode.CHARGING
        listOf(
            Triple(MediaSyncMode.ALWAYS, "Always", "Syncs as soon as you capture"),
            Triple(MediaSyncMode.CHARGING, "While charging", "Syncs when the glasses are on the charger"),
            Triple(MediaSyncMode.MANUAL, "Manual only", "Only when you tap Sync now"),
        ).forEachIndexed { index, (option, title, subtitle) ->
            if (index > 0) content.addView(BusTheme.gap(this, 8))
            content.addView(
                choiceRow(
                    title = title,
                    subtitle = subtitle,
                    selected = option == mode,
                    enabled = current != null,
                    onSelected = { runtime?.setSyncMode(option) },
                ),
                NexusUi.block(),
            )
        }
        content.addView(BusTheme.gap(this, 8))
        content.addView(
            toggleRow(
                title = "Delete from glasses after sync",
                subtitle = "Frees glasses storage. A capture is removed only once it is safely " +
                    "in your phone gallery.",
                checked = current?.settings?.deleteAfterSync ?: false,
                enabled = current != null,
                onChanged = { enabled -> runtime?.setDeleteAfterSync(enabled) },
            ),
            NexusUi.block(),
        )
        if (current?.deletionSupported == false) {
            content.addView(BusTheme.gap(this, 8))
            content.addView(
                NexusUi.metaLabel(
                    this,
                    "The glasses refused the last delete — captures stay on the glasses.",
                    NexusUi.AMBER,
                ),
                NexusUi.block(),
            )
        }

        content.addView(BusTheme.gap(this, 22))
        content.addView(NexusUi.sectionRow(this, "Recent syncs"), NexusUi.block())
        content.addView(BusTheme.gap(this, 10))
        val history = current?.history.orEmpty()
        if (history.isEmpty()) {
            content.addView(
                NexusUi.card(this).apply {
                    addView(
                        NexusUi.cardBody(
                            this@PhotoSyncSettingsActivity,
                            "Nothing yet. The first sync copies everything already on the glasses.",
                        ),
                        NexusUi.block(),
                    )
                },
                NexusUi.block(),
            )
        } else {
            history.forEachIndexed { index, run ->
                if (index > 0) content.addView(BusTheme.gap(this, 8))
                content.addView(
                    historyRow(PhotoSyncCopy.describe(run), timestamp(run.finishedAtMillis)),
                    NexusUi.block(),
                )
            }
        }

        content.addView(BusTheme.gap(this, 24))
        content.addView(NexusUi.sectionRow(this, "Plugin"), NexusUi.block())
        content.addView(BusTheme.gap(this, 10))
        content.addView(
            NexusUi.uninstallCard(this, "Photo Sync") {
                startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
            },
            NexusUi.block(),
        )
        applyStatus(current)
    }

    private fun applyStatus(current: MediaSyncStatus?) {
        headlineView?.text = PhotoSyncCopy.headline(current)
        dotView?.let { NexusUi.setDotColor(it, dotColor(current)) }
        val detail = PhotoSyncCopy.detail(current)
        detailView?.apply {
            text = detail.orEmpty()
            visibility = if (detail == null) View.GONE else View.VISIBLE
        }
        syncedValue?.text = current
            ?.let { "${it.syncedTotal} ${if (it.syncedTotal == 1) "file" else "files"}" }
            ?: "—"
        syncButton?.let { button ->
            val ready = current != null && current.state == MediaSyncState.IDLE
            button.isEnabled = ready
            button.alpha = if (ready) 1f else 0.45f
        }
    }

    private fun statusCard(): LinearLayout = NexusUi.card(this).apply {
        val dot = NexusUi.dot(this@PhotoSyncSettingsActivity).also { dotView = it }
        val headline = NexusUi.statusLine(this@PhotoSyncSettingsActivity).also { headlineView = it }
        val detail = NexusUi.rowSub(this@PhotoSyncSettingsActivity, "").also { detailView = it }
        val synced = NexusUi.rowValue(this@PhotoSyncSettingsActivity).also { syncedValue = it }
        addView(
            LinearLayout(this@PhotoSyncSettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(dot)
                addView(
                    headline,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = NexusUi.dp(this@PhotoSyncSettingsActivity, 10)
                    },
                )
            },
            NexusUi.block(),
        )
        addView(BusTheme.gap(this@PhotoSyncSettingsActivity, 6))
        addView(detail, NexusUi.block())
        addView(BusTheme.gap(this@PhotoSyncSettingsActivity, 12))
        addView(NexusUi.divider(this@PhotoSyncSettingsActivity), NexusUi.block())
        addView(BusTheme.gap(this@PhotoSyncSettingsActivity, 12))
        addView(
            LinearLayout(this@PhotoSyncSettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    NexusUi.rowLabel(this@PhotoSyncSettingsActivity, "In your gallery"),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(synced)
            },
            NexusUi.block(),
        )
        addView(BusTheme.gap(this@PhotoSyncSettingsActivity, 14))
        addView(syncNowButton(), NexusUi.block())
    }

    private fun syncNowButton(): Button = NexusUi.pillButton(this, "Sync now").apply {
        syncButton = this
        setOnClickListener {
            runtime?.syncNow()
            isEnabled = false
            alpha = 0.45f
        }
    }

    private fun dotColor(current: MediaSyncStatus?): Int = when {
        current == null -> NexusUi.INK3
        current.state != MediaSyncState.IDLE -> NexusUi.GREEN
        current.blocker == null || current.blocker == MediaSyncBlocker.NOTHING_PENDING ->
            NexusUi.GREEN_DIM
        current.blocker == MediaSyncBlocker.NOT_CHARGING ||
            current.blocker == MediaSyncBlocker.LINK_DOWN ||
            current.blocker == MediaSyncBlocker.CAMERA_ACTIVE -> NexusUi.INK3
        else -> NexusUi.AMBER
    }

    private fun historyRow(title: String, subtitle: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = NexusUi.bordered(
                this@PhotoSyncSettingsActivity,
                NexusUi.PANEL,
                NexusUi.LINE,
                15,
            )
            setPadding(
                NexusUi.dp(this@PhotoSyncSettingsActivity, 15),
                NexusUi.dp(this@PhotoSyncSettingsActivity, 12),
                NexusUi.dp(this@PhotoSyncSettingsActivity, 15),
                NexusUi.dp(this@PhotoSyncSettingsActivity, 12),
            )
            addView(
                LinearLayout(this@PhotoSyncSettingsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(NexusUi.rowTitle(this@PhotoSyncSettingsActivity, title))
                    addView(BusTheme.gap(this@PhotoSyncSettingsActivity, 3))
                    addView(NexusUi.rowSub(this@PhotoSyncSettingsActivity, subtitle))
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
        }

    /**
     * Exclusive choice, in the kit's vocabulary: a pressable row with the same dot the status card
     * uses as its selection mark. Chips (the Store's precedent) cannot carry a sub-line, and each
     * of these three needs one to be understandable.
     */
    private fun choiceRow(
        title: String,
        subtitle: String,
        selected: Boolean,
        enabled: Boolean,
        onSelected: () -> Unit,
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = if (selected) {
            NexusUi.bordered(this@PhotoSyncSettingsActivity, NexusUi.PANEL, NexusUi.GREEN_DIM, 15)
        } else {
            NexusUi.pressedBordered(this@PhotoSyncSettingsActivity, NexusUi.PANEL, 15)
        }
        setPadding(
            NexusUi.dp(this@PhotoSyncSettingsActivity, 15),
            NexusUi.dp(this@PhotoSyncSettingsActivity, 12),
            NexusUi.dp(this@PhotoSyncSettingsActivity, 15),
            NexusUi.dp(this@PhotoSyncSettingsActivity, 12),
        )
        alpha = if (enabled) 1f else 0.5f
        isClickable = enabled
        isFocusable = enabled
        if (enabled) setOnClickListener { if (!selected) onSelected() }
        addView(
            NexusUi.dot(this@PhotoSyncSettingsActivity).also {
                NexusUi.setDotColor(it, if (selected) NexusUi.GREEN else NexusUi.LINE)
            },
        )
        addView(
            LinearLayout(this@PhotoSyncSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(NexusUi.rowTitle(this@PhotoSyncSettingsActivity, title))
                addView(BusTheme.gap(this@PhotoSyncSettingsActivity, 3))
                addView(wrappingSubtitle(subtitle))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = NexusUi.dp(this@PhotoSyncSettingsActivity, 12)
            },
        )
    }

    private fun toggleRow(
        title: String,
        subtitle: String,
        checked: Boolean,
        enabled: Boolean,
        onChanged: (Boolean) -> Unit,
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = NexusUi.bordered(this@PhotoSyncSettingsActivity, NexusUi.PANEL, NexusUi.LINE, 15)
        setPadding(
            NexusUi.dp(this@PhotoSyncSettingsActivity, 15),
            NexusUi.dp(this@PhotoSyncSettingsActivity, 10),
            NexusUi.dp(this@PhotoSyncSettingsActivity, 15),
            NexusUi.dp(this@PhotoSyncSettingsActivity, 10),
        )
        alpha = if (enabled) 1f else 0.5f
        addView(
            LinearLayout(this@PhotoSyncSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(NexusUi.rowTitle(this@PhotoSyncSettingsActivity, title))
                addView(BusTheme.gap(this@PhotoSyncSettingsActivity, 3))
                addView(wrappingSubtitle(subtitle))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = NexusUi.dp(this@PhotoSyncSettingsActivity, 12)
            },
        )
        addView(
            Switch(this@PhotoSyncSettingsActivity).apply {
                isChecked = checked
                isEnabled = enabled
                thumbTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(NexusUi.GREEN, NexusUi.INK3),
                )
                trackTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(NexusUi.GREEN_DIM, NexusUi.LINE),
                )
                // Only a real press sends: re-rendering from a hub push must not echo back.
                setOnCheckedChangeListener { view, value -> if (view.isPressed) onChanged(value) }
            },
        )
    }

    /** [NexusUi.rowSub] is single-line by design; the delete warning needs to wrap. */
    private fun wrappingSubtitle(label: String): TextView =
        NexusUi.rowSub(this, label).apply {
            isSingleLine = false
            maxLines = 3
            ellipsize = null
        }

    private fun timestamp(millis: Long): String =
        if (millis <= 0L) "—" else timeFormat.format(Date(millis))

    private val versionLabel: String
        get() = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        }.getOrDefault("").ifBlank { "1.0" }

    private companion object {
        val timeFormat = SimpleDateFormat("d MMM · HH:mm", Locale.getDefault())
    }
}
