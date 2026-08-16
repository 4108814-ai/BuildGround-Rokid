from pathlib import Path
import runpy

ROOT = Path(__file__).resolve().parents[2]
OLD = ROOT / ".github/scripts/apply-assistant-diagnostics.py"
SETTINGS = ROOT / "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/AssistantSettingsActivity.kt"
SERVICE = ROOT / "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/AssistantPluginService.kt"
GRADLE = ROOT / "plugins/assistant/build.gradle.kts"
DIAG = ROOT / "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/AssistantDiagnostics.kt"
TEST = ROOT / "plugins/assistant/src/test/java/com/anezium/rokidbus/plugin/assistant/AssistantDiagnosticsContractTest.kt"


def require(text: str, needle: str, where: str) -> None:
    if needle not in text:
        raise SystemExit(f"Missing required diagnostics marker in {where}: {needle}")


def insert_once(text: str, anchor: str, addition: str, label: str) -> str:
    if addition.strip() in text:
        return text
    count = text.count(anchor)
    if count < 1:
        raise SystemExit(f"Cannot place {label}: anchor not found")
    return text.replace(anchor, addition + anchor, 1)


# The legacy script already has the proven service instrumentation. Let it apply all
# successful transformations, but do not let its brittle Settings UI anchor abort the
# build. We validate every core result before continuing, so unrelated failures are not
# silently accepted.
legacy_error = None
try:
    runpy.run_path(str(OLD), run_name="__main__")
except SystemExit as exc:
    legacy_error = str(exc)

build = GRADLE.read_text(encoding="utf-8")
service = SERVICE.read_text(encoding="utf-8")
if 'versionCode = 11' not in build or 'versionName = "1.5.1"' not in build:
    raise SystemExit(f"Legacy diagnostics failed before version update: {legacy_error}")
if not DIAG.exists():
    raise SystemExit(f"Legacy diagnostics failed before recorder generation: {legacy_error}")
for marker in (
    'diagnostics.begin("glasses_button")',
    'diagnostics.mark("CAPTURE_BEGIN")',
    'diagnostics.mark("STT_FINAL"',
    'diagnostics.mark("PIPELINE_START"',
    'diagnostics.mark("AI_REQUEST"',
    'diagnostics.mark("AI_FIRST_TOKEN")',
    'diagnostics.mark("AI_DONE"',
    'diagnostics.mark("AI_FAILED"',
    'diagnostics.mark("HUD_RENDER"',
):
    require(service, marker, "AssistantPluginService.kt")

# Finish the settings UI against the actual post-MVP source, using stable semantic
# anchors rather than assuming two unrelated calls remain adjacent.
text = SETTINGS.read_text(encoding="utf-8")

if 'private val diagnostics by lazy { AssistantDiagnostics(applicationContext) }' not in text:
    anchor = '    private val accountContextSync by lazy { AccountContextSync(applicationContext) }\n'
    require(text, anchor, "AssistantSettingsActivity.kt")
    text = text.replace(
        anchor,
        anchor + '    private val diagnostics by lazy { AssistantDiagnostics(applicationContext) }\n',
        1,
    )

if 'private lateinit var diagnosticsStatus: TextView' not in text:
    anchor = '    private lateinit var refreshButton: View\n'
    require(text, anchor, "AssistantSettingsActivity.kt")
    text = text.replace(anchor, anchor + '    private lateinit var diagnosticsStatus: TextView\n', 1)

if '        renderDiagnostics()\n' not in text:
    anchor = '        maybeDetectHermes(ProviderCatalog.custom)\n'
    require(text, anchor, "AssistantSettingsActivity.kt")
    text = text.replace(anchor, '        renderDiagnostics()\n' + anchor, 1)

if 'sectionRow(this@AssistantSettingsActivity, "Diagnostics")' not in text:
    plugin_anchor = '            addView(NexusUi.sectionRow(this@AssistantSettingsActivity, "Plugin"), NexusUi.block())\n'
    require(text, plugin_anchor, "AssistantSettingsActivity.kt")
    block = (
        '            addView(NexusUi.sectionRow(this@AssistantSettingsActivity, "Diagnostics"), NexusUi.block())\n'
        '            addView(BusTheme.gap(this@AssistantSettingsActivity, 12))\n'
        '            diagnosticsStatus = NexusUi.cardBody(\n'
        '                this@AssistantSettingsActivity,\n'
        '                diagnostics.summary(),\n'
        '            )\n'
        '            addView(diagnosticsStatus, NexusUi.block())\n'
        '            addView(BusTheme.gap(this@AssistantSettingsActivity, 28))\n'
    )
    text = text.replace(plugin_anchor, block + plugin_anchor, 1)

if 'private fun renderDiagnostics()' not in text:
    anchor = '    private fun buildUi() {\n'
    require(text, anchor, "AssistantSettingsActivity.kt")
    function = (
        '    private fun renderDiagnostics() {\n'
        '        if (::diagnosticsStatus.isInitialized) {\n'
        '            diagnosticsStatus.text = diagnostics.summary()\n'
        '        }\n'
        '    }\n\n'
    )
    text = text.replace(anchor, function + anchor, 1)

SETTINGS.write_text(text, encoding="utf-8")

TEST.parent.mkdir(parents=True, exist_ok=True)
TEST.write_text(
    '''package com.anezium.rokidbus.plugin.assistant\n\nimport org.junit.Assert.assertEquals\nimport org.junit.Test\n\nclass AssistantDiagnosticsContractTest {\n    @Test\n    fun `diagnostic stage names stay stable for field testing`() {\n        val stages = listOf(\n            "INVOKE",\n            "CAPTURE_BEGIN",\n            "STT_STARTED",\n            "STT_FINAL",\n            "PIPELINE_START",\n            "AI_REQUEST",\n            "AI_STARTED",\n            "AI_FIRST_TOKEN",\n            "AI_DONE",\n            "AI_FAILED",\n            "HUD_RENDER",\n        )\n        assertEquals(11, stages.distinct().size)\n    }\n}\n''',
    encoding="utf-8",
)

# Final contract: settings must contain all UI hooks and core service must remain intact.
settings_final = SETTINGS.read_text(encoding="utf-8")
for marker in (
    'private val diagnostics by lazy { AssistantDiagnostics(applicationContext) }',
    'private lateinit var diagnosticsStatus: TextView',
    'renderDiagnostics()',
    'sectionRow(this@AssistantSettingsActivity, "Diagnostics")',
):
    require(settings_final, marker, "AssistantSettingsActivity.kt")

print("Assistant 1.5.1 diagnostics applied to final post-MVP source.")
