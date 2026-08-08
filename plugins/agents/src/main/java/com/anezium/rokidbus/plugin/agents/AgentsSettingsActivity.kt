package com.anezium.rokidbus.plugin.agents

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
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
    private lateinit var linkTitle: TextView
    private lateinit var linkSub: TextView
    private lateinit var manualBlock: LinearLayout
    private lateinit var manualDisclosure: TextView
    private lateinit var tailscaleStatus: TextView
    private lateinit var tailscaleDot: View
    private lateinit var tailscaleGet: Button
    private lateinit var openClawEnabled: Switch
    private lateinit var openClawHost: EditText
    private lateinit var openClawPort: EditText
    private lateinit var openClawToken: EditText
    private lateinit var openClawConnection: TextView
    private lateinit var openClawDot: View
    private var linkCountdown: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        loadConfig()
        observeConnections()
        if (configStore.load().shouldMonitor) {
            AgentsMonitorService.reconcile(applicationContext)
        }
    }

    override fun onResume() {
        super.onResume()
        renderLinkState()
        renderTailscaleState()
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    private fun buildUi() {
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        agentdEnabled = NexusUi.switch(this)
        openClawEnabled = NexusUi.switch(this)
        agentdDot = NexusUi.dot(this)
        openClawDot = NexusUi.dot(this)
        agentdPairing = NexusUi.field(this, "Paste the pairing line from nexus-agentd").apply {
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
        linkTitle = NexusUi.rowTitle(this, "Link a computer")
        linkSub = NexusUi.rowSub(this, "Opens for two minutes while your computer says hello")
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
                    "Mission control for Claude Code, Codex, and OpenClaw sessions. " +
                        "Agents never notifies this phone — when a session needs you, " +
                        "it says so on your glasses.",
                ),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@AgentsSettingsActivity, 18))
            addView(NexusUi.sectionRow(this@AgentsSettingsActivity, "Claude Code & Codex"), NexusUi.block())
            addView(BusTheme.gap(this@AgentsSettingsActivity, 10))
            addView(agentdCard(), NexusUi.block())
            addView(BusTheme.gap(this@AgentsSettingsActivity, 22))
            addView(NexusUi.sectionRow(this@AgentsSettingsActivity, "Away from home"), NexusUi.block())
            addView(BusTheme.gap(this@AgentsSettingsActivity, 10))
            addView(awayCard(), NexusUi.block())
            addView(BusTheme.gap(this@AgentsSettingsActivity, 22))
            addView(NexusUi.sectionRow(this@AgentsSettingsActivity, "OpenClaw"), NexusUi.block())
            addView(BusTheme.gap(this@AgentsSettingsActivity, 10))
            addView(openClawCard(), NexusUi.block())
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
        addView(
            NexusUi.switchRow(
                this@AgentsSettingsActivity,
                "Monitor sessions",
                "Watch the sessions running on your computers",
                agentdEnabled,
            ),
            NexusUi.block(),
        )
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
        addView(linkCard(), NexusUi.block())
        addView(BusTheme.gap(this@AgentsSettingsActivity, 12))
        addView(
            NexusUi.textButton(this@AgentsSettingsActivity, "Forget computers", danger = true).apply {
                setOnClickListener {
                    agentdPairing.text.clear()
                    configStore.saveAgentd(null, enabled = agentdEnabled.isChecked)
                    configStore.forgetMachines()
                    agentdSummary.text = "No daemon paired."
                    machinesLine.text = "No computer linked yet"
                    AgentsMonitorService.reconcile(applicationContext)
                    renderLinkState()
                    toast("Linked computers forgotten.")
                }
            },
            NexusUi.block(),
        )
    }

    /**
     * The pairing act. The first computer ever is taken on trust — there is
     * nothing yet to impersonate — but every one after it has to arrive while
     * the wearer is holding this door open.
     */
    private fun linkCard() = NexusUi.pressableCard(this).apply {
        setOnClickListener {
            configStore.armLinkWindow()
            AgentsMonitorService.reconcile(applicationContext)
            renderLinkState()
        }
        addView(
            LinearLayout(this@AgentsSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(linkTitle)
                addView(BusTheme.gap(this@AgentsSettingsActivity, 4))
                addView(linkSub)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(NexusUi.chevron(this@AgentsSettingsActivity))
    }

    /**
     * Out of the house the link rides a tailnet. The guided road is Tailscale —
     * status first, one button if it is missing, one paragraph for the computer —
     * and the pasted pairing line stays behind a fold for whoever refuses it.
     */
    private fun awayCard(): LinearLayout = NexusUi.card(this).apply {
        tailscaleStatus = NexusUi.statusLine(this@AgentsSettingsActivity)
        tailscaleDot = NexusUi.dot(this@AgentsSettingsActivity)
        tailscaleGet = NexusUi.textButton(this@AgentsSettingsActivity, "Get Tailscale for this phone").apply {
            setOnClickListener { openTailscaleInstall() }
        }
        addView(
            NexusUi.cardBody(
                this@AgentsSettingsActivity,
                "Away from your Wi-Fi the link rides your Tailscale network: put " +
                    "Tailscale on this phone and on the computer, sign in with the " +
                    "same account on both, and the computer keeps finding this " +
                    "phone by itself — nothing to declare, nothing to type.",
            ),
            NexusUi.block(),
        )
        addView(BusTheme.gap(this@AgentsSettingsActivity, 10))
        addView(connectionRow(tailscaleDot, tailscaleStatus), NexusUi.block())
        addView(BusTheme.gap(this@AgentsSettingsActivity, 8))
        addView(tailscaleGet, NexusUi.block())
        addView(
            NexusUi.cardBody(
                this@AgentsSettingsActivity,
                "On the computer: Windows and macOS install it from " +
                    "tailscale.com/download; Linux runs " +
                    "“curl -fsSL https://tailscale.com/install.sh | sh” and then " +
                    "“sudo tailscale up”. Sign in, and that is the whole setup.",
            ),
            NexusUi.block(),
        )
        addView(BusTheme.gap(this@AgentsSettingsActivity, 12))
        addView(NexusUi.divider(this@AgentsSettingsActivity))
        addView(manualDisclosureRow(), NexusUi.block())
        addView(manualBlock(), NexusUi.block())
        renderTailscaleState()
    }

    private fun manualDisclosureRow(): TextView {
        manualDisclosure = NexusUi.rowSub(this, MANUAL_CLOSED).apply {
            setPadding(0, NexusUi.dp(this@AgentsSettingsActivity, 6), 0, 0)
            setOnClickListener {
                val opening = manualBlock.visibility != View.VISIBLE
                manualBlock.visibility = if (opening) View.VISIBLE else View.GONE
                text = if (opening) MANUAL_OPEN else MANUAL_CLOSED
            }
        }
        return manualDisclosure
    }

    /** Pasting the daemon's pairing line: the no-Tailscale fallback. */
    private fun manualBlock(): LinearLayout {
        manualBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(BusTheme.gap(this@AgentsSettingsActivity, 10))
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
        }
        return manualBlock
    }

    private fun tailscaleInstalled(): Boolean =
        runCatching { packageManager.getPackageInfo(TAILSCALE_PACKAGE, 0) }.isSuccess

    private fun renderTailscaleState() {
        if (!::tailscaleStatus.isInitialized) return
        val installed = tailscaleInstalled()
        tailscaleStatus.text = if (installed) "TAILSCALE IS ON THIS PHONE" else "TAILSCALE NOT INSTALLED YET"
        NexusUi.setDotColor(tailscaleDot, if (installed) NexusUi.GREEN else NexusUi.AMBER)
        tailscaleGet.visibility = if (installed) View.GONE else View.VISIBLE
    }

    private fun openTailscaleInstall() {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$TAILSCALE_PACKAGE"))
        runCatching { startActivity(market) }.onFailure {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=$TAILSCALE_PACKAGE"),
                ),
            )
        }
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

    private fun connectionRow(dot: View, label: TextView) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val dotSize = NexusUi.dp(this@AgentsSettingsActivity, 8)
        addView(
            dot,
            LinearLayout.LayoutParams(dotSize, dotSize).apply {
                marginEnd = NexusUi.dp(this@AgentsSettingsActivity, 8)
            },
        )
        addView(label)
    }

    private fun loadConfig() {
        val config = configStore.load()
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
        renderLinkState()
    }

    /** One line that answers "is my computer going to get in right now?". */
    private fun renderLinkState() {
        linkCountdown?.cancel()
        machinesLine.text = configStore.trustedMachineNames().let { machines ->
            if (machines.isEmpty()) "No computer linked yet" else "Linked: ${machines.joinToString(", ")}"
        }
        if (!configStore.isLinkWindowOpen()) {
            linkTitle.text = "Link a computer"
            linkSub.text = if (configStore.trustedMachineNames().isEmpty()) {
                "Or just start nexus-agentd — the first computer links itself"
            } else {
                "Opens for two minutes while your computer says hello"
            }
            return
        }
        linkTitle.text = "Waiting for a computer…"
        linkCountdown = uiScope.launch {
            while (isActive && configStore.isLinkWindowOpen()) {
                val left = configStore.linkWindowRemainingMs() / 1000L
                linkSub.text =
                    "Start nexus-agentd now · closes in %d:%02d".format(left / 60, left % 60)
                delay(1_000L)
            }
            renderLinkState()
        }
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
        if (test) {
            AgentsMonitorService.test(applicationContext, AgentProvider.CLAUDE)
            toast("Testing the computer link…")
        } else {
            AgentsMonitorService.reconcile(applicationContext)
            toast("Computer link settings saved.")
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
        uiScope.launch {
            AgentsRuntime.linkedMachines.collect { renderLinkState() }
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

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val MANUAL_CLOSED = "MANUAL PAIRING ›"
        const val MANUAL_OPEN = "MANUAL PAIRING ⌄"
        const val TAILSCALE_PACKAGE = "com.tailscale.ipn"
    }
}

private fun ProviderConnectionState.displayText(authFailure: String): String {
    val label = if (state == ConnectionState.AUTH_FAILED) authFailure else state.wireValue.uppercase()
    val visibleDetail = detail?.takeIf {
        it.isNotBlank() && !it.equals(label, ignoreCase = true)
    }
    return visibleDetail?.let { "$label · $it" } ?: label
}
