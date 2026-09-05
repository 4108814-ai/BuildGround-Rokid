#!/usr/bin/env python3
"""Fix notice band geometry so low HUD positions cannot collapse Relay notices."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GLASSES_GRADLE = ROOT / "glasses-hub/build.gradle.kts"
RENDERER = ROOT / "glasses-hub/src/main/java/com/anezium/rokidbus/glasses/NoticeOverlayRenderer.kt"
TESTS = ROOT / "glasses-hub/src/test/java/com/anezium/rokidbus/glasses/NoticeStateMachineTest.kt"


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8-sig")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


# Runs after the 1.4.18 + 1.4.20 + 1.4.21 + 1.4.22 BuildGround patch chain.
replace_once(GLASSES_GRADLE, "versionCode = 10422", "versionCode = 10423", "versionCode")
replace_once(GLASSES_GRADLE, 'versionName = "1.4.22"', 'versionName = "1.4.23"', "versionName")

replace_once(
    RENDERER,
    '''internal fun noticeBandHeightCeiling(\n    displayHeightPx: Int,\n    heightFraction: Float,\n    topInsetPx: Int,\n): Int = ((displayHeightPx * heightFraction).toInt() - topInsetPx).coerceAtLeast(0)''',
    '''internal fun noticeBandHeightCeiling(\n    displayHeightPx: Int,\n    heightFraction: Float,\n    topMarginPx: Int,\n): Int {\n    val fractionalCap = (displayHeightPx.coerceAtLeast(0) * heightFraction)\n        .toInt()\n        .coerceAtLeast(0)\n    val remainingViewport = (displayHeightPx - topMarginPx.coerceAtLeast(0)).coerceAtLeast(0)\n    return minOf(fractionalCap, remainingViewport)\n}''',
    "noticeBandHeightCeiling",
)

replace_once(
    RENDERER,
    '''        private var pageCountReportPending = false\n        private var hudTopInsetPx = 0''',
    '''        private var pageCountReportPending = false\n        private var hudTopInsetDp = 0''',
    "notice HUD inset field",
)

replace_once(
    RENDERER,
    '''            val ceiling = noticeBandHeightCeiling(\n                displayHeightPx = resources.displayMetrics.heightPixels,\n                heightFraction = heightFraction,\n                topInsetPx = hudTopInsetPx,\n            )''',
    '''            val displayHeightPx = resources.displayMetrics.heightPixels\n            val topMarginPx = HudBandGeometry.topPx(context, hudTopInsetDp)\n            val ceiling = noticeBandHeightCeiling(\n                displayHeightPx = displayHeightPx,\n                heightFraction = heightFraction,\n                topMarginPx = topMarginPx,\n            )''',
    "notice viewport calculation",
)

replace_once(
    RENDERER,
    '''        fun setHudTopInsetDp(value: Int) {\n            val next = BusTheme.dp(context, HudTopInset.sanitize(value))\n            if (hudTopInsetPx == next) return\n            hudTopInsetPx = next\n            requestLayout()\n        }''',
    '''        fun setHudTopInsetDp(value: Int) {\n            val next = HudTopInset.sanitize(value)\n            if (hudTopInsetDp == next) return\n            hudTopInsetDp = next\n            requestLayout()\n        }''',
    "notice HUD inset setter",
)

replace_once(
    TESTS,
    '''    @Test\n    fun `band height ceiling loses the full nonzero top inset`() {\n        val baseline = noticeBandHeightCeiling(\n            displayHeightPx = 640,\n            heightFraction = 0.92f,\n            topInsetPx = 0,\n        )\n\n        assertEquals(\n            baseline - 100,\n            noticeBandHeightCeiling(\n                displayHeightPx = 640,\n                heightFraction = 0.92f,\n                topInsetPx = 100,\n            ),\n        )\n    }''',
    '''    @Test\n    fun `band height ceiling keeps its design cap while the remaining viewport can fit it`() {\n        assertEquals(\n            416,\n            noticeBandHeightCeiling(\n                displayHeightPx = 640,\n                heightFraction = 0.65f,\n                topMarginPx = 100,\n            ),\n        )\n    }\n\n    @Test\n    fun `band height ceiling is limited by the real remaining viewport at low HUD positions`() {\n        assertEquals(\n            340,\n            noticeBandHeightCeiling(\n                displayHeightPx = 640,\n                heightFraction = 0.65f,\n                topMarginPx = 300,\n            ),\n        )\n        assertEquals(\n            0,\n            noticeBandHeightCeiling(\n                displayHeightPx = 640,\n                heightFraction = 0.65f,\n                topMarginPx = 700,\n            ),\n        )\n    }''',
    "notice height tests",
)

renderer = RENDERER.read_text(encoding="utf-8")
gradle = GLASSES_GRADLE.read_text(encoding="utf-8")
tests = TESTS.read_text(encoding="utf-8")

for required in (
    'versionCode = 10423',
    'versionName = "1.4.23"',
):
    if required not in gradle:
        raise SystemExit(f"Missing 1.4.23 version marker: {required}")

for required in (
    'topMarginPx: Int',
    'val remainingViewport = (displayHeightPx - topMarginPx.coerceAtLeast(0)).coerceAtLeast(0)',
    'val topMarginPx = HudBandGeometry.topPx(context, hudTopInsetDp)',
    'private var hudTopInsetDp = 0',
):
    if required not in renderer:
        raise SystemExit(f"Missing 1.4.23 renderer marker: {required}")

if 'topInsetPx = hudTopInsetPx' in renderer:
    raise SystemExit("Legacy double-subtraction notice geometry remains")

for required in (
    'band height ceiling keeps its design cap while the remaining viewport can fit it',
    'band height ceiling is limited by the real remaining viewport at low HUD positions',
):
    if required not in tests:
        raise SystemExit(f"Missing 1.4.23 test marker: {required}")

print("Applied BuildGround Nexus Glasses 1.4.23 notice viewport fix.")
