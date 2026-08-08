package com.anezium.rokidbus.plugin.agents

import android.content.Intent
import android.view.KeyEvent
import com.anezium.rokidbus.client.plugin.NexusCard
import com.anezium.rokidbus.client.plugin.NexusCardLine
import com.anezium.rokidbus.client.plugin.NexusNotice
import com.anezium.rokidbus.client.plugin.NexusPluginService
import com.anezium.rokidbus.client.plugin.NexusRowTone
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.client.plugin.NexusSurfaceSession
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.UUID

class AgentsPluginService : NexusPluginService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val configStore by lazy { AgentsConfigStore(applicationContext) }
    private val attention by lazy {
        AttentionDecisionEngine(
            readFingerprint = configStore::notificationFingerprint,
            writeFingerprint = configStore::saveNotificationFingerprint,
        )
    }
    private var surface: NexusSurfaceSession? = null
    private var surfaceShown = false

    /**
     * The wearer's place in the list is a session, not a row number: the board
     * re-sorts itself under them as agents work, so a positional cursor would
     * quietly drift onto a different session between looking and pressing.
     */
    private var selectedKey: String? = null

    /** The session the last band was about, so a tap lands on it. */
    private var noticeTargetKey: String? = null

    /** 0 keeps the conversation pinned to its newest message. */
    private var scrollBack = 0
    private var ageTicker: Job? = null

    /** The held tool call the wearer is answering, if they are answering one. */
    private var decidingRequestId: String? = null
    private var decisionChoice = ApprovalDecision.ALLOW
    private var decisionOpenedAt = 0L

    /**
     * The start-an-agent walk: computer, then project, then the project's
     * threads. These lists are stable while open, so a positional cursor is
     * safe here — unlike on the board.
     */
    private var launch: Launch? = null
    private var launchIndex = 0
    private var launchNote: String? = null
    private var launchRequestId: String? = null

    private sealed interface Launch {
        data object Computers : Launch
        data class Projects(val machineId: String, val machineName: String) : Launch
        data class Threads(
            val machineId: String,
            val machineName: String,
            val project: AgentProject,
        ) : Launch
    }

    override fun onCreate() {
        super.onCreate()
        serviceScope.launch {
            combine(
                AgentsRuntime.store.sessions,
                AgentsRuntime.store.connections,
                AgentsRuntime.store.conversation,
            ) { _, _, _ -> Unit }
                .collectLatest {
                    if (surfaceShown) render(show = false)
                    raiseAttention()
                }
        }
        serviceScope.launch {
            AgentsRuntime.store.threadStart.collectLatest { result ->
                if (result != null && result.requestId == launchRequestId) {
                    launchRequestId = null
                    launchNote = if (result.ok) {
                        "started · it joins the list as it reports in"
                    } else {
                        result.error ?: "the computer could not start it"
                    }
                    if (surfaceShown) render(show = false)
                }
            }
        }
        serviceScope.launch {
            AgentsRuntime.linkedMachines.collect { machineName ->
                val client = nexusClient ?: return@collect
                if (!client.supportsNoticeSurface) return@collect
                client.showNotice(
                    NexusNotice(
                        title = "Computer linked",
                        body = "$machineName · its sessions appear here".singleLine(
                            NOTICE_BODY_CHARS,
                        ),
                        ttlMs = SHORT_TTL_MS,
                    ),
                )
            }
        }
    }

    override fun onNexusOpen() {
        surface = nexusSurfaceSession(SURFACE_ID)
        surfaceShown = true
        AgentsRuntime.hudOpen = true
        render(show = true)
        ageTicker?.cancel()
        ageTicker = serviceScope.launch {
            while (isActive) {
                delay(AGE_TICK_MS)
                if (surfaceShown) render(show = false)
            }
        }
        if (configStore.load().shouldMonitor) {
            AgentsMonitorService.reconcile(applicationContext)
        }
    }

    override fun onNexusClose() {
        ageTicker?.cancel()
        ageTicker = null
        surfaceShown = false
        AgentsRuntime.hudOpen = false
        leaveConversation()
        launch = null
        launchNote = null
        surface?.hide()
        surface = null
    }

    override fun onNexusInput(event: NexusInputEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        if (decidingRequestId != null) {
            onDecisionInput(event)
            return
        }
        val conversation = AgentsRuntime.store.conversation.value
        if (conversation == null && launch != null) {
            onLaunchInput(event)
            return
        }
        // A touchpad swipe arrives as LEFT/RIGHT, the ring as UP/DOWN: both
        // walk the same list, exactly as the other boards treat them.
        when {
            event.keyCode in BACKWARD_KEYS ->
                if (conversation != null) scrollConversation(+1) else moveSelection(-1)
            event.keyCode in FORWARD_KEYS ->
                if (conversation != null) scrollConversation(-1) else moveSelection(+1)
            event.keyCode == KeyEvent.KEYCODE_ENTER ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER
            -> if (conversation == null) enterSelected()
            event.keyCode == KeyEvent.KEYCODE_BACK ->
                if (conversation != null) {
                    leaveConversation()
                    render(show = false)
                } else {
                    surface?.hide()
                }
        }
    }

    private fun onDecisionInput(event: NexusInputEvent) {
        when {
            event.keyCode in BACKWARD_KEYS || event.keyCode in FORWARD_KEYS -> {
                decisionChoice = when (decisionChoice) {
                    ApprovalDecision.ALLOW -> ApprovalDecision.DENY
                    ApprovalDecision.DENY -> ApprovalDecision.ALLOW
                }
                render(show = false)
            }
            event.keyCode == KeyEvent.KEYCODE_ENTER ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER
            -> confirmDecision()
            event.keyCode == KeyEvent.KEYCODE_BACK -> {
                decidingRequestId = null
                render(show = false)
            }
        }
    }

    /** A tap on the band means "show me": open the board on the session that rang. */
    override fun onNexusNoticeInput(event: NexusInputEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        if (event.keyCode != KeyEvent.KEYCODE_ENTER &&
            event.keyCode != KeyEvent.KEYCODE_DPAD_CENTER
        ) {
            return
        }
        noticeTargetKey?.let { selectedKey = it }
        if (!surfaceShown) attemptAdoption()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        surfaceShown = false
        AgentsRuntime.hudOpen = false
        surface = null
        super.onDestroy()
    }

    // ----------------------------------------------------------------- alerts

    /**
     * The phone stays silent by design, so this band is the only interruption
     * Agents ever makes. It is raised at most one at a time — several sessions
     * asking at once become one line, because a queue of bands is not an alert,
     * it is a nuisance — and the fingerprint is only committed once the hub has
     * actually taken the notice, so an alert that could not be delivered is
     * offered again on the next update instead of being lost.
     */
    private fun raiseAttention() {
        val client = nexusClient ?: return
        if (!client.supportsNoticeSurface) return
        val pending = attention.pending(AgentsRuntime.store.sessions.value)
        if (pending.isEmpty()) return
        val notice = if (pending.size == 1) singleNotice(pending.single()) else groupNotice(pending)
        if (client.showNotice(notice) != NexusSdkResult.SENT) return
        noticeTargetKey = pending.first().session.key
        pending.forEach(attention::commit)
    }

    private fun singleNotice(item: AgentAttention): NexusNotice {
        val session = item.session
        return NexusNotice(
            title = session.displayTitle.singleLine(NOTICE_TITLE_CHARS),
            body = when (session.status) {
                AgentStatus.ERROR ->
                    (session.statusDetail ?: "stopped with an error").singleLine(NOTICE_BODY_CHARS)
                else -> pendingSummary(session).singleLine(NOTICE_BODY_CHARS)
            },
            footer = "TAP open",
            interactive = true,
            ttlMs = if (session.status == AgentStatus.NEEDS_YOU) {
                ATTENTION_TTL_MS
            } else {
                SHORT_TTL_MS
            },
        )
    }

    private fun groupNotice(items: List<AgentAttention>): NexusNotice {
        val waiting = items.count { it.session.status == AgentStatus.NEEDS_YOU }
        val failed = items.count { it.session.status == AgentStatus.ERROR }
        val parts = listOfNotNull(
            waiting.takeIf { it > 0 }?.let { "$it waiting for you" },
            failed.takeIf { it > 0 }?.let { "$it failed" },
        )
        return NexusNotice(
            title = "Agents",
            body = parts.joinToString(" · ").singleLine(NOTICE_BODY_CHARS),
            footer = "TAP open",
            interactive = true,
            ttlMs = ATTENTION_TTL_MS,
        )
    }

    // -------------------------------------------------------------- selection

    private fun selectedIndexIn(sessions: List<AgentSession>): Int =
        sessions.indexOfFirst { it.key == selectedKey }.takeIf { it >= 0 } ?: 0

    private fun moveSelection(delta: Int) {
        val sessions = AgentsRuntime.store.sessions.value
        // One virtual row sits past the sessions: the start-an-agent door. The
        // ring walks a circle, so the door is one tap UP from the top instead
        // of the whole board away.
        val count = sessions.size + 1
        val current = if (selectedKey == START_KEY) sessions.size else selectedIndexIn(sessions)
        val next = ((current + delta) % count + count) % count
        selectedKey = if (next == sessions.size) START_KEY else sessions[next].key
        render(show = false)
    }

    private fun scrollConversation(delta: Int) {
        val total = AgentsRuntime.store.conversation.value
            ?.let { conversationRows(it).size } ?: 0
        val maxScroll = (total - 1).coerceAtLeast(0)
        scrollBack = (scrollBack + delta).coerceIn(0, maxScroll)
        render(show = false)
    }

    /** ENTER means "deal with this": answer what is waiting, or read the rest. */
    private fun enterSelected() {
        val sessions = AgentsRuntime.store.sessions.value
        if (selectedKey == START_KEY || sessions.isEmpty()) {
            launch = Launch.Computers
            launchIndex = 0
            launchNote = null
            render(show = false)
            return
        }
        val session = sessions.getOrNull(selectedIndexIn(sessions)) ?: return
        val approval = AgentsRuntime.store.approvalFor(session.key)
        if (approval != null) {
            openDecision(approval)
        } else {
            openConversation(session)
        }
    }

    private fun openConversation(session: AgentSession) {
        scrollBack = 0
        AgentsRuntime.store.openConversation(session)
        AgentsMonitorService.openDetail(applicationContext, session)
        render(show = false)
    }

    private fun openDecision(approval: AgentApproval) {
        decidingRequestId = approval.requestId
        decisionChoice = ApprovalDecision.ALLOW
        decisionOpenedAt = System.currentTimeMillis()
        render(show = false)
    }

    /**
     * The touchpad's double tap is two ENTER downs a few dozen milliseconds
     * apart, and the second one would land on a freshly opened decision. Nothing
     * an agent asked for gets approved by a gesture aimed at opening it.
     */
    private fun confirmDecision() {
        val requestId = decidingRequestId ?: return
        if (System.currentTimeMillis() - decisionOpenedAt < DECISION_GUARD_MS) return
        if (AgentsRuntime.store.approvals.value.none { it.requestId == requestId }) return
        AgentsMonitorService.decideApproval(applicationContext, requestId, decisionChoice)
        decidingRequestId = null
        render(show = false)
    }

    private fun leaveConversation() {
        if (AgentsRuntime.store.conversation.value == null) return
        AgentsRuntime.store.closeConversation()
        AgentsMonitorService.closeDetail(applicationContext)
        scrollBack = 0
    }

    private fun attemptAdoption() {
        val adoptionSurface = surface ?: nexusSurfaceSession(SURFACE_ID)?.also { surface = it }
        adoptionSurface?.showCard(buildCard())
    }

    private fun render(show: Boolean) {
        val activeSurface = surface ?: return
        val card = buildCard()
        if (show) activeSurface.showCard(card) else activeSurface.updateCard(card)
    }

    private fun buildCard(): NexusCard {
        decidingRequestId?.let { return decisionCard(it) }
        AgentsRuntime.store.conversation.value?.let { return conversationCard(it) }
        launch?.let { return launchCard(it) }
        return sessionsCard()
    }

    // ----------------------------------------------------------------- decide

    /**
     * The one screen in this product that does something rather than show
     * something. It says what will run, in the agent's own words, and offers
     * exactly two answers — no third path, no "always allow", nothing that turns
     * a glance into a standing permission.
     */
    private fun decisionCard(requestId: String): NexusCard {
        val approval = AgentsRuntime.store.approvals.value
            .firstOrNull { it.requestId == requestId }
            ?: return expiredDecisionCard()
        val session = AgentsRuntime.store.sessions.value
            .firstOrNull { it.key == approval.sessionKey }
        val rows = buildList {
            add(
                NexusCardLine(
                    text = approval.summary.singleLine(238),
                    badge = approval.tool.take(6).ifBlank { "ASK" },
                    tone = NexusRowTone.BODY,
                ),
            )
            approval.detail
                ?.takeIf { it.isNotBlank() && it != approval.summary }
                ?.let { add(NexusCardLine(text = it.singleLine(238), tone = NexusRowTone.BODY)) }
            add(choiceRow("Allow", "let it run", ApprovalDecision.ALLOW))
            add(choiceRow("Deny", "tell the agent no", ApprovalDecision.DENY))
        }
        return card(
            title = session?.displayTitle?.singleLine(110) ?: "Permission",
            subtitle = listOfNotNull(
                approval.tool.takeIf(String::isNotBlank),
                session?.project?.takeIf(String::isNotBlank),
                "waiting for you",
            ).joinToString(" · ").take(120),
            rows = rows,
            footer = "swipe choose · tap confirm · 2-tap later",
        )
    }

    private fun choiceRow(label: String, sub: String, choice: ApprovalDecision): NexusCardLine {
        val picked = decisionChoice == choice
        return NexusCardLine(
            text = label,
            sub = sub,
            selected = picked,
            tone = if (picked) NexusRowTone.ALERT else NexusRowTone.DIM,
        )
    }

    private fun expiredDecisionCard(): NexusCard = card(
        title = "Too late",
        subtitle = "the agent stopped waiting",
        rows = listOf(
            NexusCardLine(
                text = "This request is gone",
                sub = "it timed out, or it was answered on the computer",
                tone = NexusRowTone.DIM,
            ),
        ),
        footer = "2-tap back",
    )

    // ---------------------------------------------------------------- sessions

    private fun sessionsCard(): NexusCard {
        val now = System.currentTimeMillis()
        val sessions = AgentsRuntime.store.sessions.value
        val connections = AgentsRuntime.store.connections.value
        val config = configStore.load()
        val startSelected = selectedKey == START_KEY || sessions.isEmpty()
        val selectedIndex = if (startSelected) -1 else selectedIndexIn(sessions)
        if (!startSelected) selectedKey = sessions.getOrNull(selectedIndex)?.key

        val alerts = buildList {
            if (config.agentdEnabled &&
                connections[AgentProvider.CLAUDE]?.state == ConnectionState.AUTH_FAILED
            ) {
                add(problemRow("Claude pairing rejected", "re-pair in the phone app"))
            }
            if (config.openClawEnabled &&
                connections[AgentProvider.OPENCLAW]?.state == ConnectionState.AUTH_FAILED
            ) {
                add(problemRow("OpenClaw auth failed", "check the token in the phone app"))
            }
        }

        // The HUD does not scroll: keep a window around the selection.
        val windowSize = (VISIBLE_SESSION_ROWS - alerts.size).coerceAtLeast(MIN_SESSION_ROWS)
        val anchor = if (startSelected) sessions.lastIndex.coerceAtLeast(0) else selectedIndex
        val windowStart = (anchor - windowSize / 2)
            .coerceIn(0, (sessions.size - windowSize).coerceAtLeast(0))

        val rows = buildList {
            addAll(alerts)
            if (sessions.isEmpty()) {
                add(emptyRow(config))
            } else {
                sessions.drop(windowStart).take(windowSize).forEachIndexed { offset, session ->
                    add(sessionRow(session, windowStart + offset == selectedIndex, now))
                }
            }
            add(
                NexusCardLine(
                    text = "+ Start an agent",
                    sub = "a new thread in one of your projects",
                    tone = NexusRowTone.DIM,
                    selected = startSelected,
                ),
            )
        }

        return card(
            title = "Agents",
            subtitle = sessionsSubtitle(sessions, connections, config),
            rows = rows,
            footer = if (sessions.isEmpty()) {
                "tap start an agent · 2-tap exit"
            } else {
                "swipe move · tap open · 2-tap exit"
            },
        )
    }

    private fun sessionRow(session: AgentSession, selected: Boolean, now: Long): NexusCardLine {
        val age = age(now, session.lastActivityAt)
        val trail = when (session.status) {
            AgentStatus.NEEDS_YOU -> listOfNotNull("ASK", age)
            AgentStatus.WORKING -> listOfNotNull("RUN", age)
            AgentStatus.ERROR -> listOfNotNull("ERR", age)
            AgentStatus.DONE -> listOfNotNull("done", age)
            AgentStatus.IDLE -> listOfNotNull(age)
        }
        return NexusCardLine(
            text = session.displayTitle.singleLine(120),
            sub = sessionSub(session)?.singleLine(120),
            tone = when (session.status) {
                AgentStatus.NEEDS_YOU, AgentStatus.ERROR -> NexusRowTone.ALERT
                AgentStatus.WORKING -> NexusRowTone.NORMAL
                AgentStatus.IDLE, AgentStatus.DONE -> NexusRowTone.DIM
            },
            selected = selected,
            trail = trail,
        )
    }

    /** The line that says what this session is actually doing to you, or for you. */
    private fun sessionSub(session: AgentSession): String? = when (session.status) {
        AgentStatus.NEEDS_YOU -> pendingSummary(session)
        AgentStatus.ERROR -> session.statusDetail ?: "stopped with an error"
        AgentStatus.WORKING -> session.turn?.lastTool?.let { "running $it" }
            ?: session.lastAssistantText
        // Quiet sessions: where they live is the only thing worth a second line.
        AgentStatus.IDLE, AgentStatus.DONE -> session.project?.takeIf(String::isNotBlank)
    }

    /**
     * Rewrites the stock Claude notification wording into a board-sized phrase.
     * A live approval outranks it: that one is the agent's actual question,
     * where the other is what monitoring inferred about the session.
     */
    private fun pendingSummary(session: AgentSession): String {
        AgentsRuntime.store.approvalFor(session.key)?.let { return it.summary.singleLine(160) }
        val raw = session.pendingRequest?.summary?.singleLine(160) ?: return "needs you"
        PERMISSION_PATTERN.find(raw)?.let { return "wants to run ${it.groupValues[1]}" }
        if (raw.contains("waiting for your input", ignoreCase = true)) return "waiting for you"
        return raw
    }

    private fun sessionsSubtitle(
        sessions: List<AgentSession>,
        connections: Map<AgentProvider, ProviderConnectionState>,
        config: AgentsConfig,
    ): String {
        val counts = listOfNotNull(
            sessions.count { it.status == AgentStatus.NEEDS_YOU }
                .takeIf { it > 0 }?.let { "$it waiting" },
            sessions.count { it.status == AgentStatus.WORKING }
                .takeIf { it > 0 }?.let { "$it running" },
            sessions.count { it.status == AgentStatus.ERROR }
                .takeIf { it > 0 }?.let { "$it failed" },
            sessions.count { it.status == AgentStatus.IDLE }
                .takeIf { it > 0 }?.let { "$it idle" },
        )
        if (counts.isNotEmpty()) {
            return counts.joinToString(" · ").take(120)
        }
        val connecting = AgentProvider.values().any { provider ->
            provider.enabledIn(config) &&
                connections[provider]?.state == ConnectionState.CONNECTING
        }
        return if (connecting) "connecting…" else "no sessions"
    }

    private fun emptyRow(config: AgentsConfig): NexusCardLine = NexusCardLine(
        text = if (config.agentdEnabled || config.openClawEnabled) {
            "Nothing running"
        } else {
            "Set up a provider in the phone app"
        },
        sub = if (config.agentdEnabled || config.openClawEnabled) {
            "sessions appear here as soon as an agent starts"
        } else {
            null
        },
        tone = NexusRowTone.DIM,
    )

    private fun problemRow(text: String, sub: String): NexusCardLine =
        NexusCardLine(text = text, sub = sub, tone = NexusRowTone.ALERT, trail = listOf("!"))

    // -------------------------------------------------------------- launcher

    private fun onLaunchInput(event: NexusInputEvent) {
        when {
            event.keyCode in BACKWARD_KEYS -> {
                launchIndex -= 1
                render(show = false)
            }
            event.keyCode in FORWARD_KEYS -> {
                launchIndex += 1
                render(show = false)
            }
            event.keyCode == KeyEvent.KEYCODE_ENTER ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER
            -> launchEnter()
            event.keyCode == KeyEvent.KEYCODE_BACK -> {
                launch = when (val step = launch) {
                    is Launch.Threads -> Launch.Projects(step.machineId, step.machineName)
                    is Launch.Projects -> Launch.Computers
                    else -> null
                }
                launchIndex = 0
                launchNote = null
                render(show = false)
            }
        }
    }

    private fun launchEnter() {
        when (val step = launch) {
            Launch.Computers -> {
                val machines = configStore.trustedMachines()
                val machine = machines.getOrNull(launchIndex) ?: return
                launch = Launch.Projects(machine.machineId, machine.name)
                launchIndex = 0
                launchNote = null
                render(show = false)
            }
            is Launch.Projects -> {
                val project = configStore.projects(step.machineId).getOrNull(launchIndex) ?: return
                launch = Launch.Threads(step.machineId, step.machineName, project)
                launchIndex = 0
                launchNote = null
                render(show = false)
            }
            is Launch.Threads -> {
                if (launchIndex <= 0) {
                    startCodexThread(step)
                } else {
                    val session = projectThreads(step.project).getOrNull(launchIndex - 1) ?: return
                    launch = null
                    openConversation(session)
                }
            }
            null -> Unit
        }
    }

    /**
     * The glasses have no keyboard, so the thread starts empty: Codex sits
     * ready in the project until the wearer's first words reach it. Claude
     * Code cannot start without a prompt — that road stays on the phone until
     * the voice work lands.
     */
    private fun startCodexThread(step: Launch.Threads) {
        if (launchRequestId != null) return
        if (AgentsRuntime.store.linkMachine.value?.machineId != step.machineId) {
            launchNote = "${step.machineName} is not connected"
            render(show = false)
            return
        }
        val requestId = UUID.randomUUID().toString()
        launchRequestId = requestId
        launchNote = "starting on ${step.machineName}…"
        AgentsMonitorService.requestThreadStart(
            applicationContext,
            requestId,
            AgentProvider.CODEX,
            step.project.path,
            prompt = "",
        )
        render(show = false)
        serviceScope.launch {
            delay(LAUNCH_VERDICT_TIMEOUT_MS)
            if (launchRequestId == requestId) {
                launchRequestId = null
                launchNote = "no answer from ${step.machineName}"
                if (surfaceShown) render(show = false)
            }
        }
    }

    private fun projectThreads(project: AgentProject): List<AgentSession> =
        AgentsRuntime.store.sessions.value.filter { session ->
            session.provider in AgentProvider.AGENTD_PROVIDERS &&
                cwdInProject(session.cwd, project.path)
        }

    private fun launchCard(step: Launch): NexusCard = when (step) {
        Launch.Computers -> computersCard()
        is Launch.Projects -> projectsCard(step)
        is Launch.Threads -> threadsCard(step)
    }

    private fun computersCard(): NexusCard {
        val machines = configStore.trustedMachines()
        launchIndex = launchIndex.coerceIn(0, (machines.size - 1).coerceAtLeast(0))
        val link = AgentsRuntime.store.linkMachine.value
        val rows = if (machines.isEmpty()) {
            listOf(
                NexusCardLine(
                    text = "No computer linked yet",
                    sub = "link one in the phone app first",
                    tone = NexusRowTone.DIM,
                ),
            )
        } else {
            windowedRows(machines, launchIndex) { machine, selected ->
                val connected = link?.machineId == machine.machineId
                NexusCardLine(
                    text = machine.name.singleLine(120),
                    sub = when {
                        connected && link?.overTailnet == true -> "connected · over Tailscale"
                        connected -> "connected · same Wi-Fi"
                        else -> lastSeenText(machine.lastSeenAtMs).lowercase()
                    },
                    tone = if (connected) NexusRowTone.NORMAL else NexusRowTone.DIM,
                    selected = selected,
                )
            }
        }
        return card(
            title = "Start an agent",
            subtitle = "pick a computer",
            rows = rows,
            footer = "swipe move · tap pick · 2-tap board",
        )
    }

    private fun projectsCard(step: Launch.Projects): NexusCard {
        val projects = configStore.projects(step.machineId)
        launchIndex = launchIndex.coerceIn(0, (projects.size - 1).coerceAtLeast(0))
        val rows = if (projects.isEmpty()) {
            listOf(
                NexusCardLine(
                    text = "No project on ${step.machineName}".singleLine(120),
                    sub = "anchor a folder in the phone app first",
                    tone = NexusRowTone.DIM,
                ),
            )
        } else {
            windowedRows(projects, launchIndex) { project, selected ->
                NexusCardLine(
                    text = project.name.singleLine(120),
                    sub = project.path.singleLine(120),
                    tone = NexusRowTone.NORMAL,
                    selected = selected,
                )
            }
        }
        return card(
            title = "Start an agent",
            subtitle = "${step.machineName} · pick a project".take(120),
            rows = rows,
            footer = "swipe move · tap pick · 2-tap computers",
        )
    }

    private fun threadsCard(step: Launch.Threads): NexusCard {
        val now = System.currentTimeMillis()
        val threads = projectThreads(step.project)
        launchIndex = launchIndex.coerceIn(0, threads.size)
        val rows = buildList {
            add(
                NexusCardLine(
                    text = "+ New Codex thread",
                    sub = (
                        launchNote
                            ?: "starts empty here · Claude Code needs a prompt, use the phone"
                        ).singleLine(120),
                    badge = "CX",
                    tone = if (launchIndex == 0) NexusRowTone.NORMAL else NexusRowTone.DIM,
                    selected = launchIndex == 0,
                ),
            )
            addAll(
                windowedRows(threads, launchIndex - 1) { session, selected ->
                    sessionRow(session, selected, now)
                },
            )
        }
        return card(
            title = step.project.name.singleLine(110),
            subtitle = listOfNotNull(
                step.machineName,
                if (threads.isEmpty()) "no threads yet" else "${threads.size} threads",
            ).joinToString(" · ").take(120),
            rows = rows,
            footer = "swipe move · tap open or start · 2-tap projects",
        )
    }

    private fun <T> windowedRows(
        items: List<T>,
        selected: Int,
        row: (T, Boolean) -> NexusCardLine,
    ): List<NexusCardLine> {
        val start = (selected - VISIBLE_SESSION_ROWS / 2)
            .coerceIn(0, (items.size - VISIBLE_SESSION_ROWS).coerceAtLeast(0))
        return items.drop(start).take(VISIBLE_SESSION_ROWS).mapIndexed { offset, item ->
            row(item, start + offset == selected)
        }
    }

    private fun cwdInProject(cwd: String?, projectPath: String): Boolean {
        val where = cwd?.replace('\\', '/')?.trimEnd('/')?.lowercase() ?: return false
        val root = projectPath.replace('\\', '/').trimEnd('/').lowercase()
        return where == root || where.startsWith("$root/")
    }

    // ------------------------------------------------------------ conversation

    private fun conversationCard(conversation: AgentConversation): NexusCard {
        val session = AgentsRuntime.store.sessions.value
            .firstOrNull { it.key == conversation.sessionKey }
        val rows = when {
            conversation.loading -> listOf(
                NexusCardLine(text = "Reading the conversation…", tone = NexusRowTone.DIM),
            )
            conversation.messages.isEmpty() -> listOf(
                NexusCardLine(
                    text = "Nothing to show yet",
                    sub = "this session has no readable messages",
                    tone = NexusRowTone.DIM,
                ),
            )
            else -> {
                // The window is a budget of rendered lines, not a row count:
                // six one-line rows would strand the bottom half of the screen.
                val all = conversationRows(conversation)
                val end = (all.size - scrollBack).coerceIn(1, all.size)
                var start = end
                var lines = 0
                while (start > 0) {
                    val next = estimatedLines(all[start - 1])
                    if (start < end && lines + next > READER_LINE_BUDGET) break
                    lines += next
                    start--
                }
                all.subList(start, end)
            }
        }
        return card(
            title = session?.displayTitle?.singleLine(110) ?: "Conversation",
            subtitle = conversationSubtitle(session, conversation),
            rows = rows,
            footer = if (scrollBack > 0) {
                "swipe scroll · 2-tap back · $scrollBack older"
            } else {
                "swipe scroll · 2-tap back"
            },
        )
    }

    /**
     * A conversation is read as prose, not scanned as a table. Each message is
     * cut into chunks small enough that the HUD's three wrapped body lines show
     * a chunk whole, and the swipe walks chunks — so a long answer is scrolled
     * through, never ellipsized away. The badge marks where a message starts;
     * a tool call stays one dim line, a glance and no more.
     */
    private var readerCache: Pair<AgentConversation, List<NexusCardLine>>? = null

    /** How many wrapped lines a row will take on the HUD's mono body. */
    private fun estimatedLines(row: NexusCardLine): Int {
        // Only prose wraps; every other row is drawn as one ellipsized line.
        if (row.tone != NexusRowTone.BODY) return 1
        val cols = if (row.badge.isNullOrBlank()) READER_FULL_COLS else READER_BADGE_COLS
        return ((row.text.length + cols - 1) / cols).coerceAtLeast(1)
    }

    private fun conversationRows(conversation: AgentConversation): List<NexusCardLine> {
        readerCache?.let { (cached, rows) -> if (cached === conversation) return rows }
        val rows = mutableListOf<NexusCardLine>()
        val toolRun = mutableListOf<AgentMessage>()
        fun flushTools() {
            if (toolRun.isNotEmpty()) {
                rows += toolRunRow(toolRun.toList())
                toolRun.clear()
            }
        }
        for (message in conversation.messages) {
            if (message.role == MessageRole.TOOL) {
                toolRun += message
            } else {
                flushTools()
                rows += messageRows(message, conversation.provider)
            }
        }
        flushTools()
        readerCache = conversation to rows
        return rows
    }

    /**
     * An agent's work is long trains of tool calls between two paragraphs.
     * The reader is for the paragraphs, so a train collapses to one dim line:
     * the wearer sees "Edit ×4 · Bash" and moves on.
     */
    private fun toolRunRow(run: List<AgentMessage>): NexusCardLine {
        if (run.size == 1) {
            return NexusCardLine(
                text = run.single().text.singleLine(180),
                badge = MessageRole.TOOL.label,
                tone = NexusRowTone.DIM,
            )
        }
        val counts = LinkedHashMap<String, Int>()
        for (message in run) {
            val name = message.tool?.takeIf(String::isNotBlank)
                ?: message.text.substringBefore(" · ").ifBlank { "tool" }
            counts[name] = (counts[name] ?: 0) + 1
        }
        val label = counts.entries.joinToString(" · ") { (name, times) ->
            if (times > 1) "$name ×$times" else name
        }
        return NexusCardLine(
            text = label.singleLine(180),
            badge = MessageRole.TOOL.label,
            tone = NexusRowTone.DIM,
        )
    }

    private fun messageRows(
        message: AgentMessage,
        provider: AgentProvider,
    ): List<NexusCardLine> {
        val badge =
            if (message.role == MessageRole.USER) MessageRole.USER.label else provider.marker
        return proseChunks(message.text).mapIndexed { index, chunk ->
            NexusCardLine(
                text = chunk,
                badge = badge.takeIf { index == 0 },
                tone = NexusRowTone.BODY,
            )
        }
    }

    private fun proseChunks(text: String): List<String> {
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        // Where, inside [current], the last finished sentence ends. A chunk
        // that must break prefers that seam: a row ending mid-sentence reads
        // like a message of its own once the HUD spaces the rows apart.
        var sentenceEnd = 0
        // The first chunk shares its row with the role badge and loses that
        // column's width; every later chunk runs the full line.
        fun limit() = if (chunks.isEmpty()) READER_FIRST_CHUNK_CHARS else READER_CHUNK_CHARS
        fun flush() {
            if (current.isNotEmpty()) {
                chunks += current.toString()
                current.setLength(0)
            }
            sentenceEnd = 0
        }
        fun breakForNext(piece: String) {
            val seam = sentenceEnd
            if (seam >= limit() / 2 && seam < current.length) {
                val rest = current.substring(seam).trimStart()
                current.setLength(seam)
                flush()
                current.append(rest)
                if (current.length + 1 + piece.length <= limit()) {
                    current.append(' ').append(piece)
                    return
                }
            }
            flush()
            current.append(piece)
        }
        for (paragraph in text.split('\n')) {
            for (word in paragraph.trim().split(' ', '\t')) {
                var piece = word
                while (piece.length > limit()) {
                    flush()
                    val cut = limit()
                    chunks += piece.take(cut)
                    piece = piece.drop(cut)
                }
                when {
                    piece.isEmpty() -> {}
                    current.isEmpty() -> current.append(piece)
                    current.length + 1 + piece.length <= limit() ->
                        current.append(' ').append(piece)
                    else -> breakForNext(piece)
                }
                if (piece.isNotEmpty() && piece.last() in SENTENCE_ENDS) {
                    sentenceEnd = current.length
                }
            }
            // A paragraph break ends the chunk, so the message keeps its shape.
            flush()
        }
        return chunks.ifEmpty { listOf("…") }
    }

    private fun conversationSubtitle(
        session: AgentSession?,
        conversation: AgentConversation,
    ): String {
        val parts = listOfNotNull(
            session?.let { statusPhrase(it) },
            session?.project?.takeIf(String::isNotBlank),
            session?.machineName?.takeIf(String::isNotBlank),
            conversation.messages.size.takeIf { it > 0 }?.let { "$it messages" },
        )
        return parts.joinToString(" · ").take(120).ifBlank { "conversation" }
    }

    private fun statusPhrase(session: AgentSession): String = when (session.status) {
        AgentStatus.NEEDS_YOU -> pendingSummary(session)
        AgentStatus.WORKING -> "working"
        AgentStatus.IDLE -> "idle"
        AgentStatus.ERROR -> "error"
        AgentStatus.DONE -> "finished"
    }

    // ------------------------------------------------------------------ common

    private fun card(
        title: String,
        subtitle: String,
        rows: List<NexusCardLine>,
        footer: String,
    ): NexusCard {
        val keySource = buildString {
            append(title).append('\n').append(subtitle).append('\n').append(footer).append('\n')
            rows.forEach { row ->
                append(row.badge).append('|').append(row.text).append('|')
                append(row.sub).append('|').append(row.tone.wireValue).append('|')
                append(row.selected).append('|').append(row.trail.joinToString(",")).append('\n')
            }
        }
        return NexusCard(
            title = title,
            lines = emptyList(),
            subtitle = subtitle,
            footer = footer,
            contentKey = MessageDigest.getInstance("SHA-256")
                .digest(keySource.toByteArray())
                .joinToString("") { "%02x".format(it) },
            richLines = rows,
            handlesBack = true,
        )
    }

    private fun age(now: Long, at: Long?): String? {
        if (at == null) return null
        val elapsed = (now - at).coerceAtLeast(0)
        return when {
            elapsed < 60_000L -> "now"
            elapsed < 3_600_000L -> "${elapsed / 60_000L}m"
            elapsed < 86_400_000L -> "${elapsed / 3_600_000L}h"
            else -> "${elapsed / 86_400_000L}d"
        }
    }

    companion object {
        const val ACTION_MONITOR_ACTIVE =
            "com.anezium.rokidbus.plugin.agents.action.MONITOR_ACTIVE"
        private const val SURFACE_ID = "agents"
        private const val VISIBLE_SESSION_ROWS = 6
        private const val MIN_SESSION_ROWS = 4
        /**
         * The HUD's mono body wraps at about 29 characters a line and clips a
         * row after three; these sizes keep a chunk whole on the worst line.
         * The first chunk of a message also cedes a column to the role badge.
         */
        private const val READER_CHUNK_CHARS = 86
        private const val READER_FIRST_CHUNK_CHARS = 72
        private const val READER_FULL_COLS = 28
        private const val READER_BADGE_COLS = 23

        /** The screen takes ~20 mono lines; the slack absorbs row spacing. */
        private const val READER_LINE_BUDGET = 16

        /** A word ending in one of these closes a sentence a chunk can break after. */
        private val SENTENCE_ENDS = setOf('.', '!', '?', '…', ':', ';')
        private const val AGE_TICK_MS = 60_000L
        private const val NOTICE_TITLE_CHARS = 32
        private const val NOTICE_BODY_CHARS = 240
        private const val ATTENTION_TTL_MS = 12_000L
        private const val SHORT_TTL_MS = 8_000L

        /** Long enough to swallow a double tap, short enough to never be felt. */
        private const val DECISION_GUARD_MS = 600L
        private const val LAUNCH_VERDICT_TIMEOUT_MS = 35_000L

        /** Virtual board row: the door into the start-an-agent walk. */
        private const val START_KEY = "!start-an-agent"

        /** Touchpad swipes come in as LEFT/RIGHT, the ring as UP/DOWN. */
        private val FORWARD_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_MEDIA_NEXT,
        )
        private val BACKWARD_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        )
        private val PERMISSION_PATTERN =
            Regex("permission to use ([\\w.-]+)", RegexOption.IGNORE_CASE)
    }
}

private fun AgentProvider.enabledIn(config: AgentsConfig): Boolean = when (this) {
    // Codex rides the daemon link rather than a connection of its own, so it is
    // on exactly when the daemon is. It gets its own switch when the settings
    // screen learns to offer a choice of harness.
    AgentProvider.CLAUDE, AgentProvider.CODEX -> config.agentdEnabled
    AgentProvider.OPENCLAW -> config.openClawEnabled
}
