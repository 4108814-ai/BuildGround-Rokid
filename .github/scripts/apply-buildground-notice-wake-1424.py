#!/usr/bin/env python3
"""Wake a fresh notice even when Rokid's framework still reports the display interactive."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GLASSES_GRADLE = ROOT / "glasses-hub/build.gradle.kts"
POLICY = ROOT / "glasses-hub/src/main/java/com/anezium/rokidbus/glasses/DisplayWakePolicy.kt"
TESTS = ROOT / "glasses-hub/src/test/java/com/anezium/rokidbus/glasses/DisplayWakePolicyTest.kt"


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8-sig")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


# Runs after the BuildGround 1.4.23 chain.
replace_once(GLASSES_GRADLE, "versionCode = 10423", "versionCode = 10424", "versionCode")
replace_once(GLASSES_GRADLE, 'versionName = "1.4.23"', 'versionName = "1.4.24"', "versionName")

# Physical RV101 testing: Relay sound arrives immediately and the notice state/renderer are updated
# immediately, while the optics can stay dark for tens of seconds. On this firmware
# PowerManager.isInteractive can remain true during that optically-dark interval. A fresh notice
# explicitly carrying wakeDisplay=true must therefore still acquire the short one-shot screen wake.
# Keep the old ALREADY_INTERACTIVE refusal for surfaces, activities, and non-new notice requests.
replace_once(
    POLICY,
    '''        isInteractive -> DisplayWakeDecision.Refused(\n            kind,\n            DisplayWakeRefusal.ALREADY_INTERACTIVE,\n            budget,\n        )''',
    '''        isInteractive && !(kind == DisplayWakeKind.NOTICE && newNotice) ->\n            DisplayWakeDecision.Refused(\n                kind,\n                DisplayWakeRefusal.ALREADY_INTERACTIVE,\n                budget,\n            )''',
    "fresh notice interactive bypass",
)

replace_once(
    TESTS,
    '''    @Test\n    fun `already interactive during lock can be reevaluated once dark`() {\n        val first = freshNoticeWake(DisplayWakeBudget(), nowMs = 1_000L)\n        val duringLock = DisplayWakePolicy.decide(\n            kind = DisplayWakeKind.NOTICE,\n            requested = true,\n            isInteractive = true,\n            budget = first.budget,\n            nowMs = 4_500L,\n            newNotice = true,\n        )\n        assertRefused(duringLock, DisplayWakeRefusal.ALREADY_INTERACTIVE, first.budget)\n\n        val afterLock = freshNoticeWake(duringLock.budget, nowMs = 4_575L)\n\n        assertEquals(DisplayWakeAdmission.NEW_NOTICE_ENTITLEMENT, afterLock.admission)\n        assertEquals(1, afterLock.budget.unattendedNoticeWakeCount)\n    }''',
    '''    @Test\n    fun `fresh notice is allowed to relight Rokid optics even while framework says interactive`() {\n        val decision = DisplayWakePolicy.decide(\n            kind = DisplayWakeKind.NOTICE,\n            requested = true,\n            isInteractive = true,\n            budget = DisplayWakeBudget(),\n            nowMs = 4_500L,\n            newNotice = true,\n        )\n\n        assertTrue(decision is DisplayWakeDecision.Wake)\n        assertEquals(\n            DisplayWakeAdmission.BUDGET_AVAILABLE,\n            (decision as DisplayWakeDecision.Wake).admission,\n        )\n        assertEquals(4_500L, decision.budget.lastWakeAtMs)\n    }''',
    "fresh notice interactive test",
)

policy = POLICY.read_text(encoding="utf-8")
tests = TESTS.read_text(encoding="utf-8")
gradle = GLASSES_GRADLE.read_text(encoding="utf-8")

for required in (
    'versionCode = 10424',
    'versionName = "1.4.24"',
):
    if required not in gradle:
        raise SystemExit(f"Missing 1.4.24 version marker: {required}")

for required in (
    'isInteractive && !(kind == DisplayWakeKind.NOTICE && newNotice)',
    'PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP',
    'WAKE_LOCK_MS = 3_000L',
):
    if required not in policy:
        raise SystemExit(f"Missing 1.4.24 wake marker: {required}")

if 'already interactive during lock can be reevaluated once dark' in tests:
    raise SystemExit("Legacy fresh-notice ALREADY_INTERACTIVE expectation remains")
if 'fresh notice is allowed to relight Rokid optics even while framework says interactive' not in tests:
    raise SystemExit("Missing 1.4.24 wake regression test")

print("Applied BuildGround Nexus Glasses 1.4.24 fresh-notice wake fix.")
