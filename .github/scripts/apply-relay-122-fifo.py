from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: apply-relay-122-fifo.py <Rokid-Nexus-root>")

ROOT = Path(sys.argv[1]).resolve()
SRC = ROOT / "plugins/relay/src/main/java/com/anezium/rokidbus/plugin/relay"
TEST = ROOT / "plugins/relay/src/test/java/com/anezium/rokidbus/plugin/relay"
RUNTIME = SRC / "RelayNoticeRuntime.kt"


def replace_once(old: str, new: str) -> None:
    text = RUNTIME.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected one Relay runtime match, found {count}: {old[:160]!r}")
    RUNTIME.write_text(text.replace(old, new, 1), encoding="utf-8")


baseline = RUNTIME.read_text(encoding="utf-8")
for marker in (
    "private var pendingShow: ReplyRepository.PendingReply? = null",
    "fun show(reply: ReplyRepository.PendingReply) = onMain",
    "REPLAY_WINDOW_MS",
    "private fun closeClient()",
):
    if marker not in baseline:
        raise SystemExit(f"Relay 1.2.1 baseline marker missing: {marker}")

replace_once(
    "    private val essentialUpdates = ArrayDeque<NexusNoticeUpdate>()\n"
    "    private val settings = RelaySettings(appContext)\n",
    "    private val essentialUpdates = ArrayDeque<NexusNoticeUpdate>()\n"
    "    private val showBacklog = RelayCoalescingQueue<ReplyRepository.PendingReply>()\n"
    "    private val settings = RelaySettings(appContext)\n",
)

replace_once(
    "    private var sendDeadlineMs: Long? = null\n",
    "    private var sendDeadlineMs: Long? = null\n"
    "    private var transitioningReply: ReplyRepository.PendingReply? = null\n"
    "    private var transitionGeneration = 0\n",
)

old_show_start = "    fun show(reply: ReplyRepository.PendingReply) = onMain {\n"
old_show_end = "\n    fun shutdown() = onMain {\n"
text = RUNTIME.read_text(encoding="utf-8")
start = text.find(old_show_start)
end = text.find(old_show_end, start)
if start < 0 or end < 0:
    raise SystemExit("Could not isolate Relay 1.2.1 show() block")
new_show = r'''    fun show(reply: ReplyRepository.PendingReply) = onMain {
        // The inbox owns the bus while it is open, and it is already showing
        // this conversation — the capture reached the repository before us.
        if (NotificationControl.inboxOpen) {
            Log.i(TAG, "band suppressed: inbox has the bus")
            return@onMain
        }

        // A newer revision of the item waiting for the inter-notice handoff keeps
        // its FIFO position. Android messaging notifications commonly update one
        // notification key as the thread grows; moving the revision to the tail
        // would reorder conversations, while enqueuing every revision would show
        // cumulative copies of the same thread.
        if (transitioningReply?.id == reply.id) {
            transitioningReply = reply
            Log.i(TAG, "showCoalesced phase=transition id=${reply.id.take(8)}")
            return@onMain
        }

        // If this same notification has not reached the glasses yet, replace the
        // in-flight pending revision in place. It remains the head of the queue.
        if (!activeNotice && pendingShow?.id == reply.id) {
            Log.i(TAG, "showCoalesced phase=pending id=${reply.id.take(8)}")
            startShow(reply)
            return@onMain
        }

        // One live notice owns the client. Everything else waits FIFO. The
        // coalescing queue preserves first insertion order across notification
        // keys and replaces only the value for an already queued key.
        if (currentReply != null || pendingShow != null || activeNotice || transitioningReply != null) {
            val coalesced = showBacklog.offer(reply.id, reply)
            Log.i(
                TAG,
                "showQueued id=${reply.id.take(8)} coalesced=$coalesced depth=${showBacklog.size}",
            )
            return@onMain
        }

        startShow(reply)
    }

    private fun startShow(reply: ReplyRepository.PendingReply) {
        showGeneration += 1
        val generation = showGeneration
        val nowMs = SystemClock.elapsedRealtime()
        invalidateSpeech()
        stopReadAloud()
        essentialUpdates.clear()
        pendingPartial = null
        currentReply = reply
        currentTranscript = null
        pendingShow = reply
        pendingShowStartedAtMs = nowMs
        pendingShowWasBlocked = false
        val captureAgeMs = (System.currentTimeMillis() - reply.capturedAtMs).coerceAtLeast(0L)
        Log.i(TAG, "showRequested generation=$generation captureAgeMs=$captureAgeMs")

        if (client == null) {
            client = NexusPluginClient.create(appContext, PLUGIN_ID, this).also(NexusPluginClient::connect)
        }
        tryShowPending()
        main.postDelayed({
            val elapsedNowMs = SystemClock.elapsedRealtime()
            if (pendingShow != null && shouldAbandonPendingShow(
                    timerGeneration = generation,
                    activeGeneration = showGeneration,
                    startedAtMs = pendingShowStartedAtMs,
                    nowMs = elapsedNowMs,
                )
            ) {
                abandonPendingShow(elapsedNowMs)
            }
        }, REPLAY_WINDOW_MS)
    }
'''
RUNTIME.write_text(text[:start] + new_show + text[end:], encoding="utf-8")

replace_once(
    "    fun shutdown() = onMain {\n"
    "        client?.hideNotice()\n"
    "        closeClient()\n"
    "    }\n",
    "    fun shutdown() = onMain {\n"
    "        clearQueuedShows(\"shutdown\")\n"
    "        client?.hideNotice()\n"
    "        closeClient()\n"
    "    }\n",
)

replace_once(
    "            isTerminalRegistrationResult(result) -> {\n"
    "                Log.w(TAG, \"registration terminal result=$result\")\n"
    "                closeClient()\n"
    "            }\n",
    "            isTerminalRegistrationResult(result) -> {\n"
    "                Log.w(TAG, \"registration terminal result=$result\")\n"
    "                clearQueuedShows(\"registration_terminal\")\n"
    "                closeClient()\n"
    "            }\n",
)

replace_once(
    "    override fun onNoticeClosed(reason: NexusNoticeCloseReason) = onMain {\n"
    "        closeClient()\n"
    "    }\n",
    "    override fun onNoticeClosed(reason: NexusNoticeCloseReason) = onMain {\n"
    "        finishCurrentAndAdvance(\"notice_closed:${reason.name}\")\n"
    "    }\n",
)

replace_once(
    "            } else {\n"
    "                closeClient()\n"
    "            }\n"
    "        }\n"
    "    }\n\n"
    "    private fun markShowBlocked",
    "            } else {\n"
    "                finishCurrentAndAdvance(\"show_result:${result.name}\")\n"
    "            }\n"
    "        }\n"
    "    }\n\n"
    "    private fun markShowBlocked",
)

replace_once(
    "        closeClient()\n"
    "    }\n\n"
    "    private fun readNoticeAloud",
    "        finishCurrentAndAdvance(\"replay_expired\")\n"
    "    }\n\n"
    "    private fun readNoticeAloud",
)

replace_once(
    "    private fun dismissNotice() {\n"
    "        invalidateSpeech()\n"
    "        pendingPartial = null\n"
    "        essentialUpdates.clear()\n"
    "        client?.hideNotice()\n"
    "        main.postDelayed({\n"
    "            if (activeNotice) closeClient()\n"
    "        }, HIDE_FALLBACK_MS)\n"
    "    }\n",
    "    private fun dismissNotice() {\n"
    "        invalidateSpeech()\n"
    "        pendingPartial = null\n"
    "        essentialUpdates.clear()\n"
    "        val generation = showGeneration\n"
    "        client?.hideNotice()\n"
    "        main.postDelayed({\n"
    "            if (generation == showGeneration && activeNotice) {\n"
    "                finishCurrentAndAdvance(\"hide_fallback\")\n"
    "            }\n"
    "        }, HIDE_FALLBACK_MS)\n"
    "    }\n",
)

insert_marker = "    private fun closeClient() {\n"
text = RUNTIME.read_text(encoding="utf-8")
if text.count(insert_marker) != 1:
    raise SystemExit("Expected one closeClient insertion marker")
queue_runtime = r'''    private fun finishCurrentAndAdvance(reason: String) {
        val finishedId = currentReply?.id?.take(8).orEmpty()
        closeClient()
        Log.i(TAG, "showFinished reason=$reason id=$finishedId backlog=${showBacklog.size}")
        scheduleNextQueuedShow()
    }

    private fun scheduleNextQueuedShow() {
        if (transitioningReply != null) return
        val next = pollFreshQueuedReply() ?: return
        transitioningReply = next
        transitionGeneration += 1
        val generation = transitionGeneration
        main.postDelayed({
            if (generation != transitionGeneration) return@postDelayed
            val reply = transitioningReply ?: return@postDelayed
            transitioningReply = null
            val ageMs = (System.currentTimeMillis() - reply.capturedAtMs).coerceAtLeast(0L)
            if (ageMs >= REPLAY_WINDOW_MS) {
                Log.w(TAG, "queued show expired before handoff id=${reply.id.take(8)} ageMs=$ageMs")
                scheduleNextQueuedShow()
                return@postDelayed
            }
            startShow(reply)
        }, NEXT_NOTICE_HANDOFF_MS)
    }

    private fun pollFreshQueuedReply(): ReplyRepository.PendingReply? {
        while (true) {
            val candidate = showBacklog.poll() ?: return null
            val ageMs = (System.currentTimeMillis() - candidate.capturedAtMs).coerceAtLeast(0L)
            if (ageMs < REPLAY_WINDOW_MS) return candidate
            Log.w(TAG, "queued show dropped stale id=${candidate.id.take(8)} ageMs=$ageMs")
        }
    }

    private fun clearQueuedShows(reason: String) {
        transitionGeneration += 1
        transitioningReply = null
        if (showBacklog.size > 0) {
            Log.i(TAG, "showQueueCleared reason=$reason depth=${showBacklog.size}")
        }
        showBacklog.clear()
    }

'''
RUNTIME.write_text(text.replace(insert_marker, queue_runtime + insert_marker, 1), encoding="utf-8")

replace_once(
    "        const val MIN_NOTICE_MESSAGE_INTERVAL_MS = 210L\n",
    "        const val MIN_NOTICE_MESSAGE_INTERVAL_MS = 210L\n"
    "        const val NEXT_NOTICE_HANDOFF_MS = 600L\n",
)

(SRC / "RelayCoalescingQueue.kt").write_text(
    r'''package com.anezium.rokidbus.plugin.relay

/** FIFO by first accepted key; later revisions replace that key without moving it. */
internal class RelayCoalescingQueue<T> {
    private val values = linkedMapOf<String, T>()

    val size: Int
        get() = values.size

    /** @return true when an existing queued key was coalesced in place. */
    fun offer(key: String, value: T): Boolean {
        val replaced = values.containsKey(key)
        values[key] = value
        return replaced
    }

    fun poll(): T? {
        val entry = values.entries.firstOrNull() ?: return null
        values.remove(entry.key)
        return entry.value
    }

    fun clear() {
        values.clear()
    }
}
''',
    encoding="utf-8",
)

TEST.mkdir(parents=True, exist_ok=True)
(TEST / "RelayCoalescingQueueTest.kt").write_text(
    r'''package com.anezium.rokidbus.plugin.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayCoalescingQueueTest {
    @Test
    fun differentKeysRemainFifo() {
        val q = RelayCoalescingQueue<String>()
        q.offer("a", "A1")
        q.offer("b", "B1")
        q.offer("c", "C1")
        assertEquals("A1", q.poll())
        assertEquals("B1", q.poll())
        assertEquals("C1", q.poll())
        assertNull(q.poll())
    }

    @Test
    fun newerRevisionKeepsOriginalQueuePosition() {
        val q = RelayCoalescingQueue<String>()
        assertFalse(q.offer("a", "A1"))
        q.offer("b", "B1")
        assertTrue(q.offer("a", "A2"))
        assertEquals(2, q.size)
        assertEquals("A2", q.poll())
        assertEquals("B1", q.poll())
    }

    @Test
    fun repeatedRevisionDoesNotGrowQueue() {
        val q = RelayCoalescingQueue<String>()
        q.offer("chat", "v1")
        q.offer("chat", "v2")
        q.offer("chat", "v3")
        assertEquals(1, q.size)
        assertEquals("v3", q.poll())
    }

    @Test
    fun clearDropsEverything() {
        val q = RelayCoalescingQueue<String>()
        q.offer("a", "A")
        q.offer("b", "B")
        q.clear()
        assertEquals(0, q.size)
        assertNull(q.poll())
    }
}
''',
    encoding="utf-8",
)

final = RUNTIME.read_text(encoding="utf-8")
for marker in (
    "private val showBacklog = RelayCoalescingQueue",
    "showQueued id=",
    "showCoalesced phase=pending",
    "finishCurrentAndAdvance",
    "pollFreshQueuedReply",
    "NEXT_NOTICE_HANDOFF_MS = 600L",
):
    if marker not in final:
        raise SystemExit(f"Missing Relay 1.2.2 FIFO marker: {marker}")

print("Relay 1.2.2 FIFO patch applied")
