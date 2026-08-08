package com.anezium.rokidbus.plugin.agents

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The three roads to a linked computer, as equals: automatic on the home
 * Wi-Fi, Tailscale for everywhere else, and a pasted pairing line for whoever
 * wants neither.
 */
class AddComputerActivity : Activity() {
    private val configStore by lazy { AgentsConfigStore(applicationContext) }
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var doorStatus: TextView
    private lateinit var doorDot: View
    private lateinit var doorOpen: Button
    private lateinit var doorArmed: LinearLayout
    private lateinit var tailscaleStatus: TextView
    private lateinit var tailscaleDot: View
    private lateinit var tailscaleGet: Button
    private lateinit var pairingField: EditText
    private lateinit var pairingSummary: TextView
    private var countdown: Job? = null
    private var knownMachines = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        knownMachines = configStore.trustedMachines().size
        buildUi()
        uiScope.launch {
            AgentsRuntime.linkedMachines.collect { machineName ->
                // Replay hands us the last machine that ever linked; only a
                // growth in the trusted list means it happened just now.
                val count = configStore.trustedMachines().size
                if (count > knownMachines) {
                    knownMachines = count
                    toast("$machineName is linked.")
                    renderDoorState()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        renderDoorState()
        renderTailscaleState()
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    private fun buildUi() {
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        doorDot = NexusUi.dot(this)
        doorStatus = NexusUi.statusLine(this)
        doorOpen = NexusUi.pillButton(this, "Open the door for two minutes").apply {
            setOnClickListener {
                configStore.armLinkWindow()
                AgentsMonitorService.reconcile(applicationContext)
                renderDoorState()
            }
        }
        doorArmed = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(connectionRow(this@AddComputerActivity, doorDot, doorStatus), NexusUi.block())
            addView(BusTheme.gap(this@AddComputerActivity, 10))
            addView(
                NexusUi.outlinePillButton(this@AddComputerActivity, "Cancel").apply {
                    setOnClickListener {
                        configStore.cancelLinkWindow()
                        renderDoorState()
                    }
                },
                NexusUi.block(),
            )
        }
        tailscaleDot = NexusUi.dot(this)
        tailscaleStatus = NexusUi.statusLine(this)
        tailscaleGet = NexusUi.textButton(this, "Get Tailscale for this phone").apply {
            setOnClickListener { openTailscaleInstall(this@AddComputerActivity) }
        }
        pairingField = NexusUi.field(this, "Paste the pairing line from nexus-agentd").apply {
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
        pairingSummary = NexusUi.cardBody(this, "No daemon paired.")

        val content = NexusUi.contentColumn(this).apply {
            addView(wifiCard(), NexusUi.block())
            addView(BusTheme.gap(this@AddComputerActivity, 14))
            addView(tailscaleCard(), NexusUi.block())
            addView(BusTheme.gap(this@AddComputerActivity, 14))
            addView(pairingCard(), NexusUi.block())
        }
        val root = NexusUi.fixedRoot(this).apply {
            addView(backHeader(), NexusUi.block())
            addView(
                NexusUi.screen(this@AddComputerActivity, content),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }
        setContentView(root)
    }

    private fun backHeader() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad = NexusUi.dp(this@AddComputerActivity, 16)
        setPadding(pad, pad, pad, NexusUi.dp(this@AddComputerActivity, 8))
        addView(
            TextView(this@AddComputerActivity).apply {
                text = "‹"
                setTextColor(NexusUi.GREEN)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                setPadding(0, 0, NexusUi.dp(this@AddComputerActivity, 14), NexusUi.dp(this@AddComputerActivity, 2))
                setOnClickListener { finish() }
            },
        )
        addView(
            TextView(this@AddComputerActivity).apply {
                text = "Add a computer"
                setTextColor(NexusUi.INK)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            },
        )
    }

    private fun wifiCard() = NexusUi.card(this).apply {
        addView(NexusUi.rowTitle(this@AddComputerActivity, "On your Wi-Fi — automatic"), NexusUi.block())
        addView(BusTheme.gap(this@AddComputerActivity, 6))
        addView(
            NexusUi.cardBody(
                this@AddComputerActivity,
                "Run nexus-agentd on the computer. Your first computer links " +
                    "itself the moment it finds this phone; every one after it " +
                    "can only get in while the door below is open.",
            ),
            NexusUi.block(),
        )
        addView(BusTheme.gap(this@AddComputerActivity, 12))
        addView(doorOpen, NexusUi.block())
        addView(doorArmed, NexusUi.block())
    }

    private fun tailscaleCard() = NexusUi.card(this).apply {
        addView(NexusUi.rowTitle(this@AddComputerActivity, "Anywhere — Tailscale"), NexusUi.block())
        addView(BusTheme.gap(this@AddComputerActivity, 8))
        addView(connectionRow(this@AddComputerActivity, tailscaleDot, tailscaleStatus), NexusUi.block())
        addView(BusTheme.gap(this@AddComputerActivity, 8))
        addView(tailscaleGet, NexusUi.block())
        addView(
            NexusUi.cardBody(
                this@AddComputerActivity,
                "Install Tailscale on the computer — Windows and macOS from " +
                    "tailscale.com/download, Linux with " +
                    "“curl -fsSL https://tailscale.com/install.sh | sh” and then " +
                    "“sudo tailscale up” — and sign in with the same account as " +
                    "this phone. The computer then reaches this phone from any network.",
            ),
            NexusUi.block(),
        )
    }

    private fun pairingCard() = NexusUi.card(this).apply {
        addView(NexusUi.rowTitle(this@AddComputerActivity, "By hand — pairing line"), NexusUi.block())
        addView(BusTheme.gap(this@AddComputerActivity, 6))
        addView(
            NexusUi.cardBody(
                this@AddComputerActivity,
                "If you would rather use neither, paste the pairing line that " +
                    "“nexus-agentd pair” prints and this phone dials the computer.",
            ),
            NexusUi.block(),
        )
        addView(BusTheme.gap(this@AddComputerActivity, 10))
        addView(pairingField, NexusUi.block())
        addView(BusTheme.gap(this@AddComputerActivity, 10))
        addView(pairingSummary, NexusUi.block())
        addView(BusTheme.gap(this@AddComputerActivity, 12))
        addView(
            actionRow(
                this@AddComputerActivity,
                primary = "Save",
                onPrimary = { savePairing(test = false) },
                secondary = "Test connection",
                onSecondary = { savePairing(test = true) },
            ),
            NexusUi.block(),
        )
    }

    private fun renderDoorState() {
        countdown?.cancel()
        if (!configStore.isLinkWindowOpen()) {
            doorOpen.visibility = View.VISIBLE
            doorArmed.visibility = View.GONE
            return
        }
        doorOpen.visibility = View.GONE
        doorArmed.visibility = View.VISIBLE
        NexusUi.setDotColor(doorDot, NexusUi.AMBER)
        countdown = uiScope.launch {
            while (isActive && configStore.isLinkWindowOpen()) {
                val left = configStore.linkWindowRemainingMs() / 1000L
                doorStatus.text = "DOOR OPEN · %d:%02d".format(left / 60, left % 60)
                delay(1_000L)
            }
            renderDoorState()
        }
    }

    private fun renderTailscaleState() {
        val installed = tailscaleInstalled(this)
        tailscaleStatus.text = if (installed) "READY ON THIS PHONE" else "NOT INSTALLED ON THIS PHONE"
        NexusUi.setDotColor(tailscaleDot, if (installed) NexusUi.GREEN else NexusUi.AMBER)
        tailscaleGet.visibility = if (installed) View.GONE else View.VISIBLE
    }

    private fun renderPairingPreview(raw: String) {
        if (raw.isBlank()) {
            pairingSummary.text = configStore.load().agentd?.let {
                "Paired: ${it.name} · ${it.host}:${it.port}"
            } ?: "No daemon paired."
            return
        }
        pairingSummary.text = when (val parsed = AgentdPairingParser.parse(raw)) {
            is AgentdPairingParseResult.Valid ->
                "Parsed: ${parsed.config.name} · ${parsed.config.host}:${parsed.config.port}"
            is AgentdPairingParseResult.Invalid -> parsed.reason
        }
    }

    private fun savePairing(test: Boolean) {
        val raw = pairingField.text.toString()
        val config = if (raw.isBlank()) {
            configStore.load().agentd
        } else {
            when (val parsed = AgentdPairingParser.parse(raw)) {
                is AgentdPairingParseResult.Valid -> parsed.config
                is AgentdPairingParseResult.Invalid -> {
                    pairingSummary.text = parsed.reason
                    toast("Fix the pairing data first.")
                    return
                }
            }
        }
        if (config == null) {
            toast("Paste pairing data first.")
            return
        }
        configStore.saveAgentd(config, configStore.load().agentdEnabled)
        pairingField.text.clear()
        pairingSummary.text = "Paired: ${config.name} · ${config.host}:${config.port}"
        if (test) {
            AgentsMonitorService.test(applicationContext, AgentProvider.CLAUDE)
            toast("Testing the computer link…")
        } else {
            AgentsMonitorService.reconcile(applicationContext)
            toast("Computer link settings saved.")
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
