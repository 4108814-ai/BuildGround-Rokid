package com.anezium.rokidbus.plugin.wirelessadb

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PersistableBundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi
import com.anezium.rokidbus.shared.WirelessAdbAction
import kotlin.math.ceil

class WirelessAdbSettingsActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var content: LinearLayout
    private var runtime: WirelessAdbRuntime? = null
    private var unobserveRuntime: (() -> Unit)? = null
    private var unobserveState: (() -> Unit)? = null
    private var state = WirelessAdbUiState()
    private var renderedState: WirelessAdbUiState? = null
    private var expiryText: TextView? = null
    private var bound = false
    private var visible = false

    private val heartbeat = object : Runnable {
        override fun run() {
            runtime?.refreshInBackground()
            render()
            if (visible) main.postDelayed(this, HEARTBEAT_MS)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) = attachRuntime()
        override fun onServiceDisconnected(name: ComponentName?) = attachRuntime()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        content = NexusUi.contentColumn(this)
        val root = NexusUi.fixedRoot(this).apply {
            addView(
                NexusUi.pluginHeader(
                    this@WirelessAdbSettingsActivity,
                    R.drawable.nexus_glyph_wireless_adb,
                    "Wireless ADB",
                    "Pair a computer with your glasses over the LAN",
                ),
                NexusUi.block(),
            )
            addView(
                NexusUi.screen(this@WirelessAdbSettingsActivity, content),
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
            Intent(this, WirelessAdbPluginService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        unobserveRuntime = WirelessAdbPluginService.observeRuntime { main.post(::attachRuntime) }
        main.post(heartbeat)
    }

    override fun onStop() {
        visible = false
        main.removeCallbacks(heartbeat)
        unobserveRuntime?.invoke()
        unobserveRuntime = null
        unobserveState?.invoke()
        unobserveState = null
        runtime = null
        if (bound) {
            runCatching { unbindService(connection) }
            bound = false
        }
        super.onStop()
    }

    private fun attachRuntime() {
        val next = WirelessAdbPluginService.runtime()
        if (next === runtime) return
        unobserveState?.invoke()
        runtime = next
        unobserveState = next?.observe { updated ->
            main.post {
                state = updated
                render()
            }
        }
        next?.refresh()
        if (next == null) {
            state = WirelessAdbUiState()
            render()
        }
    }

    private fun render() {
        state = runtime?.snapshot() ?: state
        if (state == renderedState) {
            updateExpiryText()
            return
        }
        renderedState = state
        expiryText = null
        content.removeAllViews()
        content.addView(
            NexusUi.cardBody(
                this,
                "No cable and no Settings automation. Nexus enables Android's real wireless " +
                    "debugging service, then creates a temporary pairing code for this Wi-Fi network.",
            ),
            NexusUi.block(),
        )
        content.addView(BusTheme.gap(this, 22))
        content.addView(NexusUi.sectionRow(this, "Glasses"), NexusUi.block())
        content.addView(BusTheme.gap(this, 10))
        content.addView(statusCard(), NexusUi.block())

        state.error?.let { error ->
            content.addView(BusTheme.gap(this, 10))
            content.addView(
                NexusUi.cardBody(this, error).apply { setTextColor(NexusUi.DANGER) },
                NexusUi.block(),
            )
        }

        state.commands?.let { commands ->
            content.addView(BusTheme.gap(this, 22))
            content.addView(NexusUi.sectionRow(this, "Pair this computer"), NexusUi.block())
            content.addView(BusTheme.gap(this, 10))
            content.addView(commandCard("1 · Pair once", commands.pair), NexusUi.block())
            content.addView(BusTheme.gap(this, 8))
            content.addView(commandCard("2 · Connect", commands.connect), NexusUi.block())
            content.addView(BusTheme.gap(this, 8))
            val countdown = NexusUi.cardBody(this, expiryMessage(commands.expiresAtMillis))
            expiryText = countdown
            content.addView(
                countdown,
                NexusUi.block(),
            )
        }

        content.addView(BusTheme.gap(this, 22))
        content.addView(NexusUi.sectionRow(this, "Controls"), NexusUi.block())
        content.addView(BusTheme.gap(this, 10))
        val busy = state.busyAction
        content.addView(
            NexusUi.pillButton(
                this,
                when {
                    busy == WirelessAdbAction.START_PAIRING -> "Creating pairing code…"
                    state.pairingActive -> "Create a new pairing code"
                    state.enabled -> "Pair a computer"
                    else -> "Enable & pair computer"
                },
            ).apply {
                isEnabled = state.connected && busy == null
                alpha = if (isEnabled) 1f else DISABLED_ALPHA
                setOnClickListener { runtime?.startPairing() }
            },
            NexusUi.block(),
        )
        if (state.pairingActive) {
            content.addView(BusTheme.gap(this, 8))
            content.addView(
                NexusUi.outlinePillButton(this, "Cancel pairing window").apply {
                    isEnabled = busy == null
                    alpha = if (isEnabled) 1f else DISABLED_ALPHA
                    setOnClickListener { runtime?.cancelPairing() }
                },
                NexusUi.block(),
            )
        }
        if (state.enabled) {
            content.addView(BusTheme.gap(this, 8))
            content.addView(
                NexusUi.pillButton(this, "Disable wireless debugging", danger = true).apply {
                    isEnabled = busy == null
                    alpha = if (isEnabled) 1f else DISABLED_ALPHA
                    setOnClickListener { runtime?.disable() }
                },
                NexusUi.block(),
            )
        }

        content.addView(BusTheme.gap(this, 22))
        content.addView(NexusUi.sectionRow(this, "Security"), NexusUi.block())
        content.addView(BusTheme.gap(this, 10))
        content.addView(
            NexusUi.cardBody(
                this,
                "Only approve computers you control. Wireless debugging stays reachable from the " +
                    "current LAN until you disable it here; the pairing code itself lasts two minutes.",
            ),
            NexusUi.block(),
        )

        content.addView(BusTheme.gap(this, 22))
        content.addView(NexusUi.sectionRow(this, "Plugin"), NexusUi.block())
        content.addView(BusTheme.gap(this, 10))
        content.addView(
            NexusUi.uninstallCard(this, "Wireless ADB") {
                startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
            },
            NexusUi.block(),
        )
    }

    private fun statusCard(): LinearLayout = NexusUi.card(this).apply {
        val status = when {
            !state.connected -> "Connecting to Rokid Nexus"
            state.busyAction != null -> busyLabel(state.busyAction)
            !state.wifiConnected -> "Glasses are not on Wi-Fi"
            state.enabled -> "Wireless debugging is on"
            else -> "Wireless debugging is off"
        }
        val detail = when {
            state.pairingActive -> "A temporary pairing window is open"
            state.enabled && state.host != null && state.connectPort != null ->
                "Available at ${state.host}:${state.connectPort}"
            state.wifiConnected -> "Ready to create a pairing code"
            else -> "Connect the glasses to the same LAN as your computer"
        }
        addView(
            LinearLayout(this@WirelessAdbSettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    NexusUi.dot(this@WirelessAdbSettingsActivity).apply {
                        NexusUi.setDotColor(this, if (state.enabled) NexusUi.GREEN else NexusUi.INK3)
                    },
                    LinearLayout.LayoutParams(
                        NexusUi.dp(this@WirelessAdbSettingsActivity, 9),
                        NexusUi.dp(this@WirelessAdbSettingsActivity, 9),
                    ),
                )
                addView(
                    NexusUi.rowTitle(this@WirelessAdbSettingsActivity, status),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = NexusUi.dp(this@WirelessAdbSettingsActivity, 10)
                    },
                )
            },
            NexusUi.block(),
        )
        addView(BusTheme.gap(this@WirelessAdbSettingsActivity, 7))
        addView(NexusUi.rowSub(this@WirelessAdbSettingsActivity, detail), NexusUi.block())
    }

    private fun commandCard(title: String, command: String): LinearLayout = NexusUi.card(this).apply {
        addView(NexusUi.rowTitle(this@WirelessAdbSettingsActivity, title), NexusUi.block())
        addView(BusTheme.gap(this@WirelessAdbSettingsActivity, 8))
        addView(
            TextView(this@WirelessAdbSettingsActivity).apply {
                text = command
                typeface = Typeface.MONOSPACE
                textSize = 12f
                setTextColor(NexusUi.INK)
                setTextIsSelectable(true)
            },
            NexusUi.block(),
        )
        addView(BusTheme.gap(this@WirelessAdbSettingsActivity, 12))
        addView(
            NexusUi.outlinePillButton(this@WirelessAdbSettingsActivity, "Copy command").apply {
                setOnClickListener { copyCommand(command) }
            },
            NexusUi.block(),
        )
    }

    private fun copyCommand(command: String) {
        val clip = ClipData.newPlainText("ADB command", command).apply {
            description.extras = PersistableBundle().apply {
                putBoolean(CLIPBOARD_IS_SENSITIVE, true)
            }
        }
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
        Toast.makeText(this, "Command copied", Toast.LENGTH_SHORT).show()
    }

    private fun remainingMinutes(expiresAtMillis: Long): String {
        val remaining = (expiresAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        return "${ceil(remaining / 60_000.0).toInt().coerceAtLeast(1)} min"
    }

    private fun expiryMessage(expiresAtMillis: Long): String =
        "The pairing code expires in ${remainingMinutes(expiresAtMillis)}. " +
            "Run both commands from a terminal on the same LAN."

    private fun updateExpiryText() {
        val commands = state.commands ?: return
        val updated = expiryMessage(commands.expiresAtMillis)
        if (expiryText?.text?.toString() != updated) expiryText?.text = updated
    }

    private fun busyLabel(action: WirelessAdbAction?): String = when (action) {
        WirelessAdbAction.STATUS -> "Checking glasses…"
        WirelessAdbAction.ENABLE -> "Enabling wireless debugging…"
        WirelessAdbAction.START_PAIRING -> "Creating pairing code…"
        WirelessAdbAction.CANCEL_PAIRING -> "Closing pairing window…"
        WirelessAdbAction.DISABLE -> "Disabling wireless debugging…"
        null -> "Working…"
    }

    private companion object {
        const val HEARTBEAT_MS = 5_000L
        const val DISABLED_ALPHA = 0.45f
        const val CLIPBOARD_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
    }
}
