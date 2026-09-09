#!/usr/bin/env python3
"""Keep approved plugin guardians alive for the whole NEXUS Phone Hub lifetime."""

from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: apply-nexus-phone-1426-persistent-guardians.py <BuildGround-Rokid-root>")

ROOT = Path(sys.argv[1]).resolve()
POLICY = ROOT / "phone-hub/src/main/java/com/anezium/rokidbus/phone/PluginGuardianPolicy.kt"
POLICY_TEST = ROOT / "phone-hub/src/test/java/com/anezium/rokidbus/phone/PluginGuardianPolicyTest.kt"
COORD_TEST = ROOT / "phone-hub/src/test/java/com/anezium/rokidbus/phone/PluginGuardianCoordinatorTest.kt"


def replace_between(path: Path, start: str, end: str, replacement: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    start_index = text.find(start)
    if start_index < 0:
        raise SystemExit(f"{label}: start marker not found")
    end_index = text.find(end, start_index)
    if end_index < 0:
        raise SystemExit(f"{label}: end marker not found")
    if text.find(start, start_index + 1) >= 0:
        raise SystemExit(f"{label}: start marker is not unique")
    path.write_text(text[:start_index] + replacement + text[end_index:], encoding="utf-8")


policy_before = POLICY.read_text(encoding="utf-8")
for marker in (
    "private var linkUp = false",
    "releaseAtMillis = nowMillis + lingerMillis",
    "GuardianLinkDecision.ScheduleRelease(lingerMillis)",
    "GuardianLinkDecision.Release",
    "const val DEFAULT_LINGER_MILLIS = 30_000L",
):
    if marker not in policy_before:
        raise SystemExit(f"Expected pre-1.4.26 guardian lifetime marker missing: {marker}")

# The guardian is the process owner that keeps plugin-side recovery machinery alive.
# It must not disappear merely because the glasses are rebooting or temporarily out of range.
# Bind eligibility is therefore tied to the Phone Hub lifetime, not to glasses link state.
replace_between(
    POLICY,
    "internal class GuardianBindLifetimePolicy(",
    "internal object GuardianBindRetryPolicy",
    '''internal class GuardianBindLifetimePolicy(\n    private val lingerMillis: Long = DEFAULT_LINGER_MILLIS,\n) {\n    /** Approved guardians are owned by the Phone Hub, not by the transient glasses link. */\n    val isLinkUp: Boolean\n        get() = true\n\n    init {\n        // Retain the constructor contract for source/API compatibility with existing tests/callers.\n        require(lingerMillis > 0L)\n    }\n\n    @Suppress("UNUSED_PARAMETER")\n    fun onLinkStateChanged(isUp: Boolean, nowMillis: Long): GuardianLinkDecision =\n        GuardianLinkDecision.EnsureBound\n\n    @Suppress("UNUSED_PARAMETER")\n    fun onReleaseTimer(nowMillis: Long): GuardianLinkDecision = GuardianLinkDecision.None\n\n    companion object {\n        const val DEFAULT_LINGER_MILLIS = 30_000L\n    }\n}\n\n''',
    "persistent guardian lifetime policy",
)

policy_test_before = POLICY_TEST.read_text(encoding="utf-8")
for marker in (
    "initial link down does not start a linger",
    "link loss releases only after the awake-uptime linger",
    "stable repeated states neither extend the linger nor release early",
):
    if marker not in policy_test_before:
        raise SystemExit(f"Expected old guardian policy test missing: {marker}")

POLICY_TEST.write_text(
    '''package com.anezium.rokidbus.phone\n\nimport org.junit.Assert.assertEquals\nimport org.junit.Assert.assertTrue\nimport org.junit.Test\n\nclass PluginGuardianPolicyTest {\n    @Test\n    fun `guardian is binding eligible before any glasses link exists`() {\n        val policy = GuardianBindLifetimePolicy()\n\n        assertTrue(policy.isLinkUp)\n        assertEquals(GuardianLinkDecision.EnsureBound, policy.onLinkStateChanged(false, 1_000L))\n        assertTrue(policy.isLinkUp)\n    }\n\n    @Test\n    fun `glasses link loss never schedules or performs guardian release`() {\n        val policy = GuardianBindLifetimePolicy()\n\n        assertEquals(GuardianLinkDecision.EnsureBound, policy.onLinkStateChanged(true, 1_000L))\n        assertEquals(GuardianLinkDecision.EnsureBound, policy.onLinkStateChanged(false, 2_000L))\n        assertEquals(GuardianLinkDecision.None, policy.onReleaseTimer(32_000L))\n        assertEquals(GuardianLinkDecision.None, policy.onReleaseTimer(3_600_000L))\n        assertTrue(policy.isLinkUp)\n    }\n\n    @Test\n    fun `repeated link states keep asking coordinator to ensure guardian binding`() {\n        val policy = GuardianBindLifetimePolicy()\n\n        assertEquals(GuardianLinkDecision.EnsureBound, policy.onLinkStateChanged(false, 1_000L))\n        assertEquals(GuardianLinkDecision.EnsureBound, policy.onLinkStateChanged(false, 2_000L))\n        assertEquals(GuardianLinkDecision.EnsureBound, policy.onLinkStateChanged(true, 3_000L))\n        assertEquals(GuardianLinkDecision.EnsureBound, policy.onLinkStateChanged(false, 4_000L))\n        assertTrue(policy.isLinkUp)\n    }\n\n    @Test\n    fun `binding retry backoff is bounded at five minutes`() {\n        assertEquals(1_000L, GuardianBindRetryPolicy.delayMillis(1))\n        assertEquals(5_000L, GuardianBindRetryPolicy.delayMillis(2))\n        assertEquals(30_000L, GuardianBindRetryPolicy.delayMillis(3))\n        assertEquals(60_000L, GuardianBindRetryPolicy.delayMillis(4))\n        assertEquals(300_000L, GuardianBindRetryPolicy.delayMillis(5))\n        assertEquals(300_000L, GuardianBindRetryPolicy.delayMillis(50))\n    }\n}\n''',
    encoding="utf-8",
)

coord_test_before = COORD_TEST.read_text(encoding="utf-8")
old_test_name = "link flap cancels linger and stable loss releases after thirty seconds"
if old_test_name not in coord_test_before:
    raise SystemExit(f"Expected old coordinator lifetime test missing: {old_test_name}")

# Keep the existing binding-death retry test, and replace only the link-lifetime test.
start = '    @Test\n    fun `link flap cancels linger and stable loss releases after thirty seconds`() {'
end = '    private fun idleMain() {'
replacement = '''    @Test\n    fun `guardian stays bound through long glasses link loss and releases only when coordinator closes`() {\n        val context = RecordingContext()\n        val coordinator = PluginGuardianCoordinator(\n            context = context,\n            targetProvider = { listOf(target) },\n            logger = {},\n            stoppedFlagReader = { false },\n        )\n\n        // Even if the glasses are already down/rebooting when the coordinator starts,\n        // the approved guardian is process-owned and must be bound.\n        coordinator.onLinkStateChanged(false)\n        idleMain()\n        assertEquals(1, context.connections.size)\n        assertEquals(0, context.unbindCount)\n\n        shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.MINUTES)\n        assertEquals(1, context.connections.size)\n        assertEquals(0, context.unbindCount)\n\n        // Link flaps may reconcile eligibility but must not duplicate or release the binding.\n        coordinator.onLinkStateChanged(true)\n        coordinator.onLinkStateChanged(false)\n        idleMain()\n        shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.MINUTES)\n        assertEquals(1, context.connections.size)\n        assertEquals(0, context.unbindCount)\n\n        // Phone Hub shutdown remains the explicit owner boundary.\n        coordinator.close()\n        idleMain()\n        assertEquals(1, context.unbindCount)\n    }\n\n'''
replace_between(COORD_TEST, start, end, replacement, "persistent guardian coordinator test")

final_policy = POLICY.read_text(encoding="utf-8")
final_policy_test = POLICY_TEST.read_text(encoding="utf-8")
final_coord_test = COORD_TEST.read_text(encoding="utf-8")

for marker in (
    "Approved guardians are owned by the Phone Hub",
    "get() = true",
    "GuardianLinkDecision.EnsureBound",
    "fun onReleaseTimer(nowMillis: Long): GuardianLinkDecision = GuardianLinkDecision.None",
):
    if marker not in final_policy:
        raise SystemExit(f"Persistent guardian marker missing after patch: {marker}")

for forbidden in (
    "releaseAtMillis = nowMillis + lingerMillis",
    "return GuardianLinkDecision.ScheduleRelease(lingerMillis)",
):
    if forbidden in final_policy:
        raise SystemExit(f"Old link-scoped guardian release behavior survived: {forbidden}")

for marker in (
    "glasses link loss never schedules or performs guardian release",
    "guardian stays bound through long glasses link loss and releases only when coordinator closes",
):
    if marker not in final_policy_test + final_coord_test:
        raise SystemExit(f"Persistent guardian regression test marker missing: {marker}")

print("NEXUS Phone 1.4.26 persistent plugin guardian patch applied")
