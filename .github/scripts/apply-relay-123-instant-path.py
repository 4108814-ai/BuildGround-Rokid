from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: apply-relay-123-instant-path.py <Rokid-Nexus-root>")

ROOT = Path(sys.argv[1]).resolve()
SRC = ROOT / "plugins/relay/src/main/java/com/anezium/rokidbus/plugin/relay"
RUNTIME = SRC / "RelayNoticeRuntime.kt"
LISTENER = SRC / "RelayNotificationListener.kt"
CONTROL = SRC / "NotificationControl.kt"


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}: {old[:180]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


# This patch intentionally applies after BuildGround Relay 1.2.2 FIFO.
# 1.2.2 fixed ordering but still closed the Nexus client after every notice,
# forcing every phone notification through a cold Android bind + plugin
# registration path. Relay 1.2.3 keeps exactly one band client hot whenever
# Notification Access is live, Relay is enabled and the inbox does not own the
# plugin id. BusClient then keeps its own hub reconnect loop alive, so an arriving
# notification can use the already-registered path instead of creating it.
runtime = RUNTIME.read_text(encoding="utf-8")
for marker in (
    "private val showBacklog = RelayCoalescingQueue",
    "private fun finishCurrentAndAdvance(reason: String)",
    "private fun closeClient()",
    "NEXT_NOTICE_HANDOFF_MS = 600L",
):
    if marker not in runtime:
        raise SystemExit(f"Relay 1.2.2 marker missing before hot-path patch: {marker}")

replace_once(
    RUNTIME,
    "/** One bus connection per live notice/reply exchange; it closes when that band closes. */\n",
    "/** One prewarmed bus connection while Relay owns the plugin id; notices reuse it. */\n",
    "runtime class contract",
)

replace_once(
    RUNTIME,
    "    fun show(reply: ReplyRepository.PendingReply) = onMain {\n",
    "    /** Keep the phone-hub registration hot before the next notification exists. */\n"
    "    fun prewarm() = onMain {\n"
    "        if (NotificationControl.inboxOpen || !settings.enabled()) return@onMain\n"
    "        ensureClient()\n"
    "    }\n\n"
    "    private fun ensureClient() {\n"
    "        if (client != null) return\n"
    "        Log.i(TAG, \"hotPath connect reason=prewarm\")\n"
    "        client = NexusPluginClient.create(appContext, PLUGIN_ID, this).also(NexusPluginClient::connect)\n"
    "    }\n\n"
    "    fun show(reply: ReplyRepository.PendingReply) = onMain {\n",
    "runtime prewarm insertion",
)

replace_once(
    RUNTIME,
    "        if (client == null) {\n"
    "            client = NexusPluginClient.create(appContext, PLUGIN_ID, this).also(NexusPluginClient::connect)\n"
    "        }\n"
    "        tryShowPending()\n",
    "        ensureClient()\n"
    "        tryShowPending()\n",
    "startShow hot client",
)

replace_once(
    RUNTIME,
    "    private fun finishCurrentAndAdvance(reason: String) {\n"
    "        val finishedId = currentReply?.id?.take(8).orEmpty()\n"
    "        closeClient()\n"
    "        Log.i(TAG, \"showFinished reason=$reason id=$finishedId backlog=${showBacklog.size}\")\n"
    "        scheduleNextQueuedShow()\n"
    "    }\n",
    "    private fun finishCurrentAndAdvance(reason: String) {\n"
    "        val finishedId = currentReply?.id?.take(8).orEmpty()\n"
    "        resetExchangeState()\n"
    "        Log.i(\n"
    "            TAG,\n"
    "            \"showFinished reason=$reason id=$finishedId backlog=${showBacklog.size} hot=${client != null}\",\n"
    "        )\n"
    "        scheduleNextQueuedShow()\n"
    "    }\n",
    "keep client across notice handoff",
)

old_close = """    private fun closeClient() {
        cancelSendCountdown()
        showGeneration += 1
        invalidateSpeech()
        closeReadAloudSession()
        essentialUpdates.clear()
        pendingPartial = null
        updateDrainScheduled = false
        pendingShow = null
        pendingShowStartedAtMs = 0L
        pendingShowWasBlocked = false
        currentReply = null
        currentTranscript = null
        activeNotice = false
        client?.close()
        client = null
    }
"""
new_close = """    /** End one notice/reply exchange without throwing away the hot bus registration. */
    private fun resetExchangeState() {
        cancelSendCountdown()
        showGeneration += 1
        invalidateSpeech()
        closeReadAloudSession()
        essentialUpdates.clear()
        pendingPartial = null
        updateDrainScheduled = false
        pendingShow = null
        pendingShowStartedAtMs = 0L
        pendingShowWasBlocked = false
        currentReply = null
        currentTranscript = null
        activeNotice = false
    }

    /** Hard stop only: listener loss, Relay disabled, inbox takeover or terminal registration. */
    private fun closeClient() {
        resetExchangeState()
        client?.close()
        client = null
        Log.i(TAG, "hotPath disconnected")
    }
"""
replace_once(RUNTIME, old_close, new_close, "split exchange reset from client close")

# Start the hot connection as soon as Android confirms NotificationListener
# authority. This is the earliest reliable lifetime hook available to Relay.
replace_once(
    LISTENER,
    "        RelayGuardianService.requestImmediateHealthEvaluation()\n"
    "        rebuildFromActiveNotifications()\n",
    "        RelayGuardianService.requestImmediateHealthEvaluation()\n"
    "        runtime.prewarm()\n"
    "        rebuildFromActiveNotifications()\n",
    "listener-connected prewarm",
)

replace_once(
    LISTENER,
    "    internal fun suspendBand() {\n"
    "        runtime.shutdown()\n"
    "    }\n\n"
    "    internal fun refreshFromSettings() {\n",
    "    internal fun suspendBand() {\n"
    "        runtime.shutdown()\n"
    "    }\n\n"
    "    /** The menu inbox released the plugin id; reacquire the hot band path now. */\n"
    "    internal fun resumeBand() {\n"
    "        if (RelaySettings(this).enabled()) runtime.prewarm()\n"
    "    }\n\n"
    "    internal fun refreshFromSettings() {\n",
    "listener resume band",
)

replace_once(
    LISTENER,
    "        rebuildFromActiveNotifications()\n"
    "    }\n\n"
    "    private fun rebuildFromActiveNotifications() {\n",
    "        runtime.prewarm()\n"
    "        rebuildFromActiveNotifications()\n"
    "    }\n\n"
    "    private fun rebuildFromActiveNotifications() {\n",
    "settings-enabled prewarm",
)

# Relay deliberately permits only one registration for plugin id `relay`.
# The inbox still wins while open. As soon as it closes, restore the already
# proven band topology instead of waiting for the next notification to discover
# that it needs a connection.
replace_once(
    CONTROL,
    "    fun inboxClosed(service: RelayPluginService) {\n"
    "        if (inbox === service) inbox = null\n"
    "        inboxOpen = false\n"
    "    }\n",
    "    fun inboxClosed(service: RelayPluginService) {\n"
    "        if (inbox === service) inbox = null\n"
    "        inboxOpen = false\n"
    "        main.post { listener?.resumeBand() }\n"
    "    }\n",
    "inbox close prewarm",
)

# Defensive generated-source assertions: the release must not silently regress
# to one cold connection per notification.
final_runtime = RUNTIME.read_text(encoding="utf-8")
for marker in (
    "fun prewarm() = onMain",
    "private fun ensureClient()",
    "resetExchangeState()",
    "hot=${client != null}",
    "Hard stop only",
):
    if marker not in final_runtime:
        raise SystemExit(f"Missing Relay 1.2.3 hot-path marker: {marker}")
if "closeClient()\n        Log.i(TAG, \"showFinished" in final_runtime:
    raise SystemExit("Per-notice client close survived Relay 1.2.3 patch")

final_listener = LISTENER.read_text(encoding="utf-8")
for marker in ("runtime.prewarm()", "internal fun resumeBand()"):
    if marker not in final_listener:
        raise SystemExit(f"Missing Relay 1.2.3 listener marker: {marker}")

final_control = CONTROL.read_text(encoding="utf-8")
if "listener?.resumeBand()" not in final_control:
    raise SystemExit("Missing Relay 1.2.3 inbox-release prewarm marker")

print("Relay 1.2.3 instant-path patch applied")
