package com.anezium.rokidbus.plugin.agents

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
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
    private lateinit var agentdConnection: TextView
    private lateinit var agentdDot: View
    private lateinit var computersList: LinearLayout
    private lateinit var openClawFold: TextView
    private lateinit var openClawBlock: LinearLayout
    private lateinit var openClawEnabled: Switch
    private lateinit var openClawHost: EditText
    private lateinit var openClawPort: EditText
    private lateinit var openClawToken: EditText
    private lateinit var openClawConnection: TextView
    private lateinit var openClawDot: View

    /** The machine whose row the wearer tapped open to reach its Forget. */
    private var expandedMachineId: String? = null
    private var liveLink: LinkMachine? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        loadConfig()
        observeState()
        if (configStore.load().shouldMonitor) {
            AgentsMonitorService.reconcile(applicationContext)
        }
    }

    override fun onResume() {
        super.onResume()
        renderComputers()
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    private fun buildUi() {
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        agentdEnabled = NexusUi.switch(this).apply {
            setOnCheckedChangeListener { _, checked ->
                configStore.saveAgentd(configStore.load().agentd, checked)
                AgentsMonitorService.reconcile(applicationContext)
            }
        }
        openClawEnabled = NexusUi.switch(this).apply {
            setOnCheckedChangeListener { _, checked ->
                configStore.saveOpenClaw(configStore.load().openClaw, checked)
                AgentsMonitorService.reconcile(applicationContext)
            }
        }
        agentdDot = NexusUi.dot(this)
        openClawDot = NexusUi.dot(this)
        agentdConnection = NexusUi.statusLine(this).apply { text = "DISCONNECTED" }
        computersList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
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
                    "Mission control for Claude Code, Codex, and OpenClaw sessions. " +
                        "Agents never notifies this phone — when a session needs you, " +
                        "it says so on your glasses.",
                ),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@AgentsSettingsActivity, 18))
            addView(NexusUi.sectionRow(this@AgentsSettingsActivity, "Monitoring"), NexusUi.block())
            addView(BusTheme.gap(this@AgentsSettingsActivity, 10))
            addView(monitoringCard(), NexusUi.block())
            addView(BusTheme.gap(this@AgentsSettingsActivity, 22))
            addView(NexusUi.sectionRow(this@AgentsSettingsActivity, "Computers"), NexusUi.block())
            addView(BusTheme.gap(this@AgentsSettingsActivity, 10))
            addView(computersCard(), NexusUi.block())
            addView(BusTheme.gap(this@AgentsSettingsActivity, 22))
            addView(openClawFoldRow(), NexusUi.block())
            addView(openClawFoldBlock(), NexusUi.block())
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

    private fun monitoringCard() = NexusUi.card(this).apply {
        addView(
            NexusUi.switchRow(
                this@AgentsSettingsActivity,
                "Monitor sessions",
                "Claude Code, Codex, and OpenClaw",
                agentdEnabled,
            ),
            NexusUi.block(),
        )
        addView(BusTheme.gap(this@AgentsSettingsActivity, 8))
        addView(connectionRow(this@AgentsSettingsActivity, agentdDot, agentdConnection), NexusUi.block())
    }

    private fun computersCard() = NexusUi.card(this).apply {
        addView(computersList, NexusUi.block())
        addView(NexusUi.divider(this@AgentsSettingsActivity))
        addView(
            NexusUi.rowTitle(this@AgentsSettingsActivity, "+ Add a computer").apply {
                setTextColor(NexusUi.GREEN)
                setPadding(0, NexusUi.dp(this@AgentsSettingsActivity, 10), 0, NexusUi.dp(this@AgentsSettingsActivity, 4))
                setOnClickListener {
                    startActivity(Intent(this@AgentsSettingsActivity, AddComputerActivity::class.java))
                }
            },
            NexusUi.block(),
        )
    }

    /**
     * OpenClaw is the odd one out — its own gateway, its own credentials — and
     * most wearers never touch it, so it stays folded unless it is switched on.
     */
    private fun openClawFoldRow(): TextView {
        openClawFold = NexusUi.rowSub(this, OPENCLAW_CLOSED).apply {
            setPadding(0, NexusUi.dp(this@AgentsSettingsActivity, 6), 0, 0)
            setOnClickListener { setOpenClawFold(open = openClawBlock.visibility != View.VISIBLE) }
        }
        return openClawFold
    }

    private fun openClawFoldBlock(): LinearLayout {
        openClawBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(BusTheme.gap(this@AgentsSettingsActivity, 10))
            addView(openClawCard(), NexusUi.block())
        }
        return openClawBlock
    }

    private fun setOpenClawFold(open: Boolean) {
        openClawBlock.visibility = if (open) View.VISIBLE else View.GONE
        openClawFold.text = if (open) OPENCLAW_OPEN else OPENCLAW_CLOSED
    }

    private fun openClawCard() = NexusUi.card(this).apply {
        addView(
            NexusUi.switchRow(
                this@AgentsSettingsActivity,
                "Monitor sessions",
                "Watch the sessions on your OpenClaw gateway",
                openClawEnabled,
            ),
            NexusUi.block(),
        )
        addView(BusTheme.gap(this@AgentsSettingsActivity, 8))
        addView(connectionRow(this@AgentsSettingsActivity, openClawDot, openClawConnection), NexusUi.block())
        addView(NexusUi.divider(this@AgentsSettingsActivity))
        addView(openClawHost, NexusUi.block())
        addView(BusTheme.gap(this@AgentsSettingsActivity, 8))
        addView(openClawPort, NexusUi.block())
        addView(BusTheme.gap(this@AgentsSettingsActivity, 8))
        addView(openClawToken, NexusUi.block())
        addView(BusTheme.gap(this@AgentsSettingsActivity, 12))
        addView(
            actionRow(
                this@AgentsSettingsActivity,
                primary = "Save",
                onPrimary = { saveOpenClaw(test = false) },
                secondary = "Test connection",
                onSecondary = { saveOpenClaw(test = true) },
            ),
            NexusUi.block(),
        )
    }

    /**
     * One row per computer this phone trusts. Tapping a row trades its chevron
     * for Forget, so removing a machine takes two deliberate taps and a
     * misplaced one costs nothing.
     */
    private fun renderComputers() {
        if (!::computersList.isInitialized) return
        computersList.removeAllViews()
        val machines = configStore.trustedMachines()
        val paired = configStore.load().agentd
        if (machines.isEmpty() && paired == null) {
            computersList.addView(
                NexusUi.rowSub(this, "No computer linked yet — add one below"),
                NexusUi.block(),
            )
            return
        }
        machines.forEachIndexed { index, machine ->
            if (index > 0) computersList.addView(BusTheme.gap(this, 4))
            val connected = liveLink?.machineId == machine.machineId
            val sub = when {
                connected && liveLink?.overTailnet == true -> "Connected · over Tailscale"
                connected -> "Connected · same Wi-Fi"
                else -> lastSeenText(machine.lastSeenAtMs)
            }
            computersList.addView(
                machineRow(
                    title = machine.name,
                    sub = sub,
                    dotColor = if (connected) NexusUi.GREEN else NexusUi.INK3,
                    expanded = expandedMachineId == machine.machineId,
                    onToggle = {
                        expandedMachineId =
                            if (expandedMachineId == machine.machineId) null else machine.machineId
                        renderComputers()
                    },
                    onForget = {
                        AgentsMonitorService.forgetMachine(applicationContext, machine.machineId)
                        expandedMachineId = null
                        renderComputers()
                        toast("${machine.name} forgotten.")
                    },
                ),
                NexusUi.block(),
            )
        }
        if (paired != null) {
            if (machines.isNotEmpty()) computersList.addView(BusTheme.gap(this, 4))
            computersList.addView(
                machineRow(
                    title = paired.name,
                    sub = "Paired by hand · ${paired.host}:${paired.port}",
                    dotColor = NexusUi.INK3,
                    expanded = expandedMachineId == PAIRED_ROW_ID,
                    onToggle = {
                        expandedMachineId = if (expandedMachineId == PAIRED_ROW_ID) null else PAIRED_ROW_ID
                        renderComputers()
                    },
                    onForget = {
                        configStore.saveAgentd(null, enabled = agentdEnabled.isChecked)
                        AgentsMonitorService.reconcile(applicationContext)
                        expandedMachineId = null
                        renderComputers()
                        toast("${paired.name} forgotten.")
                    },
                ),
                NexusUi.block(),
            )
        }
    }

    private fun machineRow(
        title: String,
        sub: String,
        dotColor: Int,
        expanded: Boolean,
        onToggle: () -> Unit,
        onForget: () -> Unit,
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, NexusUi.dp(this@AgentsSettingsActivity, 6), 0, NexusUi.dp(this@AgentsSettingsActivity, 6))
        setOnClickListener { onToggle() }
        val dot = NexusUi.dot(this@AgentsSettingsActivity)
        NexusUi.setDotColor(dot, dotColor)
        val dotSize = NexusUi.dp(this@AgentsSettingsActivity, 8)
        addView(
            dot,
            LinearLayout.LayoutParams(dotSize, dotSize).apply {
                marginEnd = NexusUi.dp(this@AgentsSettingsActivity, 10)
            },
        )
        addView(
            LinearLayout(this@AgentsSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(NexusUi.rowTitle(this@AgentsSettingsActivity, title))
                addView(NexusUi.rowSub(this@AgentsSettingsActivity, sub))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (expanded) {
            addView(
                NexusUi.rowTitle(this@AgentsSettingsActivity, "Forget").apply {
                    setTextColor(NexusUi.DANGER)
                    setPadding(NexusUi.dp(this@AgentsSettingsActivity, 12), 0, 0, 0)
                    setOnClickListener { onForget() }
                },
            )
        } else {
            addView(NexusUi.chevron(this@AgentsSettingsActivity))
        }
    }

    private fun loadConfig() {
        val config = configStore.load()
        agentdEnabled.isChecked = config.agentdEnabled
        openClawEnabled.isChecked = config.openClawEnabled
        openClawHost.setText(config.openClaw?.host.orEmpty())
        openClawPort.setText(
            (config.openClaw?.port ?: OpenClawConfig.DEFAULT_PORT).toString(),
        )
        openClawToken.setText(config.openClaw?.token.orEmpty())
        setOpenClawFold(open = config.openClawEnabled)
        renderComputers()
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
        if (test) {
            AgentsMonitorService.test(applicationContext, AgentProvider.OPENCLAW)
            toast("Testing OpenClaw connection…")
        } else {
            AgentsMonitorService.reconcile(applicationContext)
            toast("OpenClaw settings saved.")
        }
    }

    private fun observeState() {
        uiScope.launch {
            AgentsRuntime.store.connections.collectLatest { states ->
                renderMonitoring(states.getValue(AgentProvider.CLAUDE))
                applyConnectionState(
                    openClawDot,
                    openClawConnection,
                    states.getValue(AgentProvider.OPENCLAW),
                    authFailure = "AUTH FAILED",
                )
            }
        }
        uiScope.launch {
            AgentsRuntime.store.sessions.collectLatest {
                renderMonitoring(AgentsRuntime.store.connections.value.getValue(AgentProvider.CLAUDE))
            }
        }
        uiScope.launch {
            AgentsRuntime.store.linkMachine.collectLatest {
                liveLink = it
                renderComputers()
            }
        }
        uiScope.launch {
            AgentsRuntime.linkedMachines.collect { renderComputers() }
        }
    }

    /**
     * The status answers what the glasses are seeing, not just whether a socket
     * is up: session counts while the link works, the link's own state while it
     * does not.
     */
    private fun renderMonitoring(state: ProviderConnectionState) {
        if (state.state != ConnectionState.CONNECTED) {
            applyConnectionState(agentdDot, agentdConnection, state, authFailure = "LINK REFUSED")
            return
        }
        val sessions = AgentsRuntime.store.sessions.value
            .filter { it.provider in AgentProvider.AGENTD_PROVIDERS }
        val needsYou = sessions.count { it.status == AgentStatus.NEEDS_YOU }
        val running = sessions.count { it.status == AgentStatus.WORKING }
        val idle = sessions.count { it.status == AgentStatus.IDLE }
        val parts = buildList {
            if (needsYou > 0) add(if (needsYou == 1) "1 NEEDS YOU" else "$needsYou NEED YOU")
            add("$running RUNNING")
            add("$idle IDLE")
        }
        agentdConnection.text = "WATCHING · ${parts.joinToString(" · ")}"
        NexusUi.setDotColor(agentdDot, if (needsYou > 0) NexusUi.AMBER else NexusUi.GREEN)
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

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        /** List-row id for the hand-paired daemon entry, which has no machineId. */
        const val PAIRED_ROW_ID = "paired-daemon"
        const val OPENCLAW_CLOSED = "OPENCLAW ›"
        const val OPENCLAW_OPEN = "OPENCLAW ⌄"
    }
}

internal fun ProviderConnectionState.displayText(authFailure: String): String {
    val label = if (state == ConnectionState.AUTH_FAILED) authFailure else state.wireValue.uppercase()
    val visibleDetail = detail?.takeIf {
        it.isNotBlank() && !it.equals(label, ignoreCase = true)
    }
    return visibleDetail?.let { "$label · $it" } ?: label
}
