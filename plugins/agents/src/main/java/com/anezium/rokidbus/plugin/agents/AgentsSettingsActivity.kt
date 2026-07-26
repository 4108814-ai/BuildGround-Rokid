package com.anezium.rokidbus.plugin.agents

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusPluginIcons
import com.anezium.rokidbus.client.ui.NexusUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AgentsSettingsActivity : Activity() {
    private val configStore by lazy { AgentsConfigStore(applicationContext) }
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var agentdEnabled: Switch
    private lateinit var agentdPairing: EditText
    private lateinit var agentdSummary: TextView
    private lateinit var agentdConnection: TextView
    private lateinit var agentdDot: View
    private lateinit var machinesLine: TextView
    private lateinit var openClawEnabled: Switch
    private lateinit var openClawHost: EditText
    private lateinit var openClawPort: EditText
    private lateinit var openClawToken: EditText
    private lateinit var openClawConnection: TextView
    private lateinit var openClawDot: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        loadConfig()
        observeConnections()
        if (configStore.load().shouldMonitor) {
            AgentsMonitorService.reconcile(applicationContext)
        }
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    private fun buildUi() {
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        agentdEnabled = Switch(this)
        openClawEnabled = Switch(this)
        agentdDot = NexusUi.dot(this)
        openClawDot = NexusUi.dot(this)
        agentdPairing = NexusUi.field(this, "Away from home: paste pairing JSON (optional)").apply {
            setSingleLine(false)
            minLines = 3
            maxLines = 6
            gravity = Gravity.TOP
            setPadding(paddingLeft, 14, paddingRight, 14)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    renderPairingPreview(s?.toString().orEmpty())
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        machinesLine = NexusUi.rowSub(this, "No computer linked yet")
        agentdSummary = NexusUi.cardBody(this, "No daemon paired.")
        agentdConnection = NexusUi.statusLine(this).apply { text = "DISCONNECTED" }
        openClawHost = NexusUi.field(this, "Host or ws(s)://host").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        openClawPort = NexusUi.field(this, "Port").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        openClawToken = NexusUi.field(this, "Gateway token").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            typeface = Typeface.DEFAULT
        }
        openClawConnection = NexusUi.statusLine(this).apply { text = "DISCONNECTED" }

        val content = NexusUi.contentColumn(this).apply {
            addView(
                NexusUi.cardBody(
                    this@AgentsSettingsActivity,
                    "Read-only mission control for Claude Code and OpenClaw sessions.",
                ),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@AgentsSettingsActivity, 18))
            addView(NexusUi.sectionRow(this@AgentsSettingsActivity, "Claude Code"), NexusUi.block())
            addView(BusTheme.gap(this@AgentsSettingsActivity, 10))
            addView(agentdCard(), NexusUi.block())
            addView(BusTheme.gap(this@AgentsSettingsActivity, 22))
            addView(NexusUi.sectionRow(this@AgentsSettingsActivity, "OpenClaw"), NexusUi.block())
            addView(BusTheme.gap(this@AgentsSettingsActivity, 10))
            addView(openClawCard(), NexusUi.block())
            addView(BusTheme.gap(this@AgentsSettingsActivity, 22))
            addView(NexusUi.sectionRow(this@AgentsSettingsActivity, "Notifications"), NexusUi.block())
            addView(BusTheme.gap(this@AgentsSettingsActivity, 10))
            addView(
                NexusUi.pressableCard(this@AgentsSettingsActivity).apply {
                    setOnClickListener { openNotificationSettings() }
                    addView(
                        LinearLayout(this@AgentsSettingsActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(NexusUi.rowTitle(this@AgentsSettingsActivity, "Notification settings"))
                            addView(BusTheme.gap(this@AgentsSettingsActivity, 4))
                            addView(
                                NexusUi.rowSub(
                                    this@AgentsSettingsActivity,
                                    "Allow Agent sessions alerts and control the monitor notification",
                                ),
                            )
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    addView(NexusUi.rowSub(this@AgentsSettingsActivity, "OPEN ›"))
                },
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@AgentsSettingsActivity, 24))
            addView(NexusUi.sectionRow(this@AgentsSettingsActivity, "Plugin"), NexusUi.block())
            addView(BusTheme.gap(this@AgentsSettingsActivity, 10))
            addView(
                NexusUi.uninstallCard(this@AgentsSettingsActivity, "Agents") {
                    startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
                },
                NexusUi.block(),
            )
        }
        val root = NexusUi.fixedRoot(this).apply {
            addView(
                NexusUi.pluginHeader(
                    this@AgentsSettingsActivity,
                    NexusPluginIcons.drawableFor("terminal"),
                    "Agents",
                    "Coding-agent mission control · v${BuildConfig.VERSION_NAME}",
                ),
                NexusUi.block(),
            )
            addView(
                NexusUi.screen(this@AgentsSettingsActivity, content),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }
        setContentView(root)
    }

    private fun agentdCard() = NexusUi.card(this).apply {
        addView(switchRow("Monitor sessions", agentdEnabled), NexusUi.block())
        addView(BusTheme.gap(this@AgentsSettingsActivity, 8))
        addView(connectionRow(agentdDot, agentdConnection), NexusUi.block())
        addView(BusTheme.gap(this@AgentsSettingsActivity, 8))
        addView(
            NexusUi.cardBody(
                this@AgentsSettingsActivity,
                "Run nexus-agentd on your computer and it finds this phone on the " +
                    "same Wi-Fi by itself — no address, no cable, nothing to open.",
            ),
            NexusUi.block(),
        )
        addView(BusTheme.gap(this@AgentsSettingsActivity, 8))
        addView(machinesLine, NexusUi.block())
        addView(NexusUi.divider(this@AgentsSettingsActivity))
        addView(agentdPairing, NexusUi.block())
        addView(BusTheme.gap(this@AgentsSettingsActivity, 10))
        addView(agentdSummary, NexusUi.block())
        addView(BusTheme.gap(this@AgentsSettingsActivity, 12))
        addView(
            actionRow(
                primary = "Save",
                onPrimary = { saveAgentd(test = false) },
                secondary = "Test connection",
                onSecondary = { saveAgentd(test = true) },
            ),
            NexusUi.block(),
        )
        addView(
            NexusUi.textButton(this@AgentsSettingsActivity, "Forget computers", danger = true).apply {
                setOnClickListener {
                    agentdPairing.text.clear()
                    configStore.saveAgentd(null, enabled = agentdEnabled.isChecked)
                    configStore.forgetMachines()
                    agentdSummary.text = "No daemon paired."
                    machinesLine.text = "No computer linked yet"
                    AgentsMonitorService.reconcile(applicationContext)
                    toast("Linked computers forgotten.")
                }
            },
            NexusUi.block(),
        )
    }

    private fun openClawCard() = NexusUi.card(this).apply {
        addView(switchRow("Monitor sessions", openClawEnabled), NexusUi.block())
        addView(BusTheme.gap(this@AgentsSettingsActivity, 8))
        addView(connectionRow(openClawDot, openClawConnection), NexusUi.block())
        addView(NexusUi.divider(this@AgentsSettingsActivity))
        addView(openClawHost, NexusUi.block())
        addView(BusTheme.gap(this@AgentsSettingsActivity, 8))
        addView(openClawPort, NexusUi.block())
        addView(BusTheme.gap(this@AgentsSettingsActivity, 8))
        addView(openClawToken, NexusUi.block())
        addView(BusTheme.gap(this@AgentsSettingsActivity, 12))
        addView(
            actionRow(
                primary = "Save",
                onPrimary = { saveOpenClaw(test = false) },
                secondary = "Test connection",
                onSecondary = { saveOpenClaw(test = true) },
            ),
            NexusUi.block(),
        )
    }

    private fun actionRow(
        primary: String,
        onPrimary: () -> Unit,
        secondary: String,
        onSecondary: () -> Unit,
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(
            NexusUi.pillButton(this@AgentsSettingsActivity, primary).apply {
                setOnClickListener { onPrimary() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 6
            },
        )
        addView(
            NexusUi.outlinePillButton(this@AgentsSettingsActivity, secondary).apply {
                setOnClickListener { onSecondary() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 6
            },
        )
    }

    private fun switchRow(label: String, switch: Switch) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            NexusUi.rowTitle(this@AgentsSettingsActivity, label),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(switch)
    }

    private fun connectionRow(dot: View, label: TextView) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val dotSize = (8 * resources.displayMetrics.density).toInt()
        addView(
            dot,
            LinearLayout.LayoutParams(dotSize, dotSize).apply {
                marginEnd = (8 * resources.displayMetrics.density).toInt()
            },
        )
        addView(label)
    }

    private fun loadConfig() {
        val config = configStore.load()
        val machines = configStore.trustedMachineNames()
        machinesLine.text = if (machines.isEmpty()) {
            "No computer linked yet"
        } else {
            "Linked: ${machines.joinToString(", ")}"
        }
        agentdEnabled.isChecked = config.agentdEnabled
        agentdSummary.text = config.agentd?.let {
            "${it.name} · ${it.host}:${it.port}"
        } ?: "No daemon paired."
        openClawEnabled.isChecked = config.openClawEnabled
        openClawHost.setText(config.openClaw?.host.orEmpty())
        openClawPort.setText(
            (config.openClaw?.port ?: OpenClawConfig.DEFAULT_PORT).toString(),
        )
        openClawToken.setText(config.openClaw?.token.orEmpty())
    }

    private fun renderPairingPreview(raw: String) {
        if (raw.isBlank()) {
            agentdSummary.text = configStore.load().agentd?.let {
                "Paired: ${it.name} · ${it.host}:${it.port}"
            } ?: "No daemon paired."
            return
        }
        agentdSummary.text = when (val parsed = AgentdPairingParser.parse(raw)) {
            is AgentdPairingParseResult.Valid ->
                "Parsed: ${parsed.config.name} · ${parsed.config.host}:${parsed.config.port}"
            is AgentdPairingParseResult.Invalid -> parsed.reason
        }
    }

    private fun saveAgentd(test: Boolean) {
        val raw = agentdPairing.text.toString()
        val config = if (raw.isBlank()) {
            configStore.load().agentd
        } else {
            when (val parsed = AgentdPairingParser.parse(raw)) {
                is AgentdPairingParseResult.Valid -> parsed.config
                is AgentdPairingParseResult.Invalid -> {
                    agentdSummary.text = parsed.reason
                    toast("Fix the pairing data first.")
                    return
                }
            }
        }
        if (config == null) {
            toast("Paste pairing data first.")
            return
        }
        configStore.saveAgentd(config, agentdEnabled.isChecked)
        agentdPairing.text.clear()
        agentdSummary.text = "Paired: ${config.name} · ${config.host}:${config.port}"
        requestNotificationPermissionIfNeeded()
        if (test) {
            AgentsMonitorService.test(applicationContext, AgentProvider.CLAUDE)
            toast("Testing Claude Code connection…")
        } else {
            AgentsMonitorService.reconcile(applicationContext)
            toast("Claude Code settings saved.")
        }
    }

    private fun saveOpenClaw(test: Boolean) {
        val host = openClawHost.text.toString().trim()
        val port = openClawPort.text.toString().toIntOrNull()
        val token = openClawToken.text.toString().trim()
        if (host.isBlank() || port !in 1..65535 || token.isBlank()) {
            toast("Enter a host, valid port, and token.")
            return
        }
        val config = OpenClawConfig(host, checkNotNull(port), token)
        configStore.saveOpenClaw(config, openClawEnabled.isChecked)
        requestNotificationPermissionIfNeeded()
        if (test) {
            AgentsMonitorService.test(applicationContext, AgentProvider.OPENCLAW)
            toast("Testing OpenClaw connection…")
        } else {
            AgentsMonitorService.reconcile(applicationContext)
            toast("OpenClaw settings saved.")
        }
    }

    private fun observeConnections() {
        uiScope.launch {
            AgentsRuntime.store.connections.collectLatest { states ->
                applyConnectionState(
                    agentdDot,
                    agentdConnection,
                    states.getValue(AgentProvider.CLAUDE),
                    authFailure = "PAIRING INVALID",
                )
                applyConnectionState(
                    openClawDot,
                    openClawConnection,
                    states.getValue(AgentProvider.OPENCLAW),
                    authFailure = "AUTH FAILED",
                )
            }
        }
    }

    private fun applyConnectionState(
        dot: View,
        label: TextView,
        state: ProviderConnectionState,
        authFailure: String,
    ) {
        label.text = state.displayText(authFailure)
        NexusUi.setDotColor(
            dot,
            when (state.state) {
                ConnectionState.CONNECTED -> NexusUi.GREEN
                ConnectionState.CONNECTING -> NexusUi.AMBER
                ConnectionState.AUTH_FAILED -> NexusUi.DANGER
                ConnectionState.DISCONNECTED -> NexusUi.INK3
            },
        )
    }

    private fun openNotificationSettings() {
        startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
        )
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST,
            )
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val NOTIFICATION_PERMISSION_REQUEST = 81
    }
}

private fun ProviderConnectionState.displayText(authFailure: String): String {
    val label = if (state == ConnectionState.AUTH_FAILED) authFailure else state.wireValue.uppercase()
    val visibleDetail = detail?.takeIf {
        it.isNotBlank() && !it.equals(label, ignoreCase = true)
    }
    return visibleDetail?.let { "$label · $it" } ?: label
}
