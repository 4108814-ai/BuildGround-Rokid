from pathlib import Path
import runpy

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "glasses-hub/src/main/java/com/anezium/rokidbus/glasses"
TEST = ROOT / "glasses-hub/src/test/java/com/anezium/rokidbus/glasses"
SERVICE = SRC / "RokidBusAccessibilityService.kt"
WATCHDOG = SRC / "DisplayStandbyWatchdog.kt"

# Reuse the proven 1.4.8 launcher restore patch, then narrow the lifetime of
# "deliberate exit" from the whole Accessibility-service lifetime to one
# display-wake episode.
runpy.run_path(
    str(ROOT / ".github/scripts/apply-nexus-glasses-148-launcher-retry.py"),
    run_name="__main__",
)


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected one match in {path.name}, found {count}: {old[:180]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def replace_count(path: Path, old: str, new: str, expected: int) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != expected:
        raise SystemExit(
            f"Expected {expected} matches in {path.name}, found {count}: {old[:180]!r}"
        )
    path.write_text(text.replace(old, new), encoding="utf-8")


# Screen lifecycle callbacks come from the already-existing standby watcher.
replace_once(
    WATCHDOG,
    """internal class DisplayStandbyWatchdog(
    private val service: AccessibilityService,
    private val handler: Handler,
    private val nowMs: () -> Long = SystemClock::uptimeMillis,
) {
""",
    """internal class DisplayStandbyWatchdog(
    private val service: AccessibilityService,
    private val handler: Handler,
    private val nowMs: () -> Long = SystemClock::uptimeMillis,
    private val onScreenOn: () -> Unit = {},
    private val onScreenOff: () -> Unit = {},
) {
""",
)

replace_once(
    WATCHDOG,
    """            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> arm()
                Intent.ACTION_SCREEN_OFF -> disarm()
            }
""",
    """            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    arm()
                    onScreenOn()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    disarm()
                    onScreenOff()
                }
            }
""",
)

# Wire screen lifecycle into launcher ownership.
replace_once(
    SERVICE,
    """    private val displayStandbyWatchdog by lazy(LazyThreadSafetyMode.NONE) {
        DisplayStandbyWatchdog(this, main)
    }
""",
    """    private val displayStandbyWatchdog by lazy(LazyThreadSafetyMode.NONE) {
        DisplayStandbyWatchdog(
            service = this,
            handler = main,
            onScreenOn = { onDisplayScreenOnForLauncherRestore() },
            onScreenOff = { onDisplayScreenOffForLauncherRestore() },
        )
    }
""",
)

replace_once(
    SERVICE,
    """    private var launcherAutoRestoreCompleted = false
    private var launcherAutoRestoreGeneration = 0
""",
    """    private val launcherAutoRestoreSession = LauncherAutoRestoreSession()
""",
)

replace_once(
    SERVICE,
    """        launcherAutoRestoreGeneration += 1
        scheduleLauncherAutoRestore(attempt = 0, generation = launcherAutoRestoreGeneration)
""",
    """        scheduleLauncherAutoRestore(
            attempt = 0,
            generation = launcherAutoRestoreSession.beginServiceEpisode(),
        )
""",
)

replace_once(
    SERVICE,
    """        if (launcherAutoRestoreCompleted || generation != launcherAutoRestoreGeneration) return
""",
    """        if (!launcherAutoRestoreSession.canRun(generation)) return
""",
)

replace_once(
    SERVICE,
    """            if (liveInstance !== this || launcherAutoRestoreCompleted || generation != launcherAutoRestoreGeneration) {
""",
    """            if (liveInstance !== this || !launcherAutoRestoreSession.canRun(generation)) {
""",
)

replace_count(
    SERVICE,
    """                launcherAutoRestoreCompleted = true
""",
    """                launcherAutoRestoreSession.markCompleted()
""",
    expected=2,
)

replace_once(
    SERVICE,
    """        }, delayMs)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
""",
    """        }, delayMs)
    }

    private fun onDisplayScreenOffForLauncherRestore() {
        launcherAutoRestoreSession.onScreenOff()
        log("Launcher auto-restore rearmed for next display wake")
    }

    private fun onDisplayScreenOnForLauncherRestore() {
        val generation = launcherAutoRestoreSession.beginScreenOnEpisode()
        scheduleLauncherAutoRestore(attempt = 0, generation = generation)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
""",
)

(SRC / "LauncherAutoRestoreSession.kt").write_text(
    '''package com.anezium.rokidbus.glasses

/**
 * Keeps Nexus-primary ownership scoped to a display wake episode.
 *
 * Once Nexus has been shown, an explicit launcher exit is respected while the
 * display stays awake. SCREEN_OFF invalidates that completion and the next
 * SCREEN_ON starts a fresh episode in which Nexus may become primary again.
 */
internal class LauncherAutoRestoreSession {
    private var generation: Int = 0
    private var completed: Boolean = false

    fun beginServiceEpisode(): Int {
        completed = false
        generation += 1
        return generation
    }

    fun onScreenOff() {
        completed = false
        generation += 1
    }

    fun beginScreenOnEpisode(): Int {
        generation += 1
        return generation
    }

    fun canRun(candidateGeneration: Int): Boolean =
        !completed && candidateGeneration == generation

    fun markCompleted() {
        completed = true
    }
}
''',
    encoding="utf-8",
)

TEST.mkdir(parents=True, exist_ok=True)
(TEST / "LauncherAutoRestoreSessionTest.kt").write_text(
    '''package com.anezium.rokidbus.glasses

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherAutoRestoreSessionTest {
    @Test
    fun deliberateExitIsRespectedUntilDisplaySleeps() {
        val session = LauncherAutoRestoreSession()
        val serviceGeneration = session.beginServiceEpisode()

        assertTrue(session.canRun(serviceGeneration))
        session.markCompleted()
        assertFalse(session.canRun(serviceGeneration))

        session.onScreenOff()
        assertFalse(session.canRun(serviceGeneration))

        val wakeGeneration = session.beginScreenOnEpisode()
        assertTrue(session.canRun(wakeGeneration))
    }

    @Test
    fun staleRetryCannotReopenLauncherAfterNewWakeEpisode() {
        val session = LauncherAutoRestoreSession()
        val first = session.beginServiceEpisode()

        session.onScreenOff()
        val second = session.beginScreenOnEpisode()

        assertFalse(session.canRun(first))
        assertTrue(session.canRun(second))
    }

    @Test
    fun launcherCompletionSuppressesFurtherRetriesWithinSameWakeEpisode() {
        val session = LauncherAutoRestoreSession()
        session.onScreenOff()
        val generation = session.beginScreenOnEpisode()

        assertTrue(session.canRun(generation))
        session.markCompleted()
        assertFalse(session.canRun(generation))
    }
}
''',
    encoding="utf-8",
)

service_text = SERVICE.read_text(encoding="utf-8")
watchdog_text = WATCHDOG.read_text(encoding="utf-8")
for marker in (
    "private val launcherAutoRestoreSession = LauncherAutoRestoreSession()",
    "onDisplayScreenOffForLauncherRestore()",
    "onDisplayScreenOnForLauncherRestore()",
    "launcherAutoRestoreSession.beginScreenOnEpisode()",
):
    if marker not in service_text:
        raise SystemExit(f"Missing Nexus Glasses 1.4.9 service marker: {marker}")

for marker in (
    "private val onScreenOn: () -> Unit = {}",
    "private val onScreenOff: () -> Unit = {}",
    "onScreenOn()",
    "onScreenOff()",
):
    if marker not in watchdog_text:
        raise SystemExit(f"Missing Nexus Glasses 1.4.9 watchdog marker: {marker}")
