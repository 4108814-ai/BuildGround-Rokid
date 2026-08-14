from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
path = ROOT / "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/AssistantSettingsActivity.kt"
text = path.read_text(encoding="utf-8")

# The MVP integration originally added a second onResume(). Remove that generated
# duplicate and refresh notification access from the Activity's existing onResume().
duplicate = '''
    override fun onResume() {
        super.onResume()
        if (::notificationAccessSlot.isInitialized) renderNotificationAccess()
    }

    // ------------------------------------------------------------------ providers
'''
replacement = '''
    // ------------------------------------------------------------------ providers
'''
if text.count(duplicate) != 1:
    raise SystemExit(f"Expected one generated duplicate onResume block, found {text.count(duplicate)}")
text = text.replace(duplicate, replacement, 1)

old_resume_tail = '''        renderCalendarAccess()
        renderPhoneAccess()
        maybeDetectHermes(ProviderCatalog.custom)
'''
new_resume_tail = '''        renderCalendarAccess()
        renderPhoneAccess()
        if (::notificationAccessSlot.isInitialized) renderNotificationAccess()
        maybeDetectHermes(ProviderCatalog.custom)
'''
if text.count(old_resume_tail) != 1:
    raise SystemExit(f"Expected one original onResume tail, found {text.count(old_resume_tail)}")
text = text.replace(old_resume_tail, new_resume_tail, 1)

if text.count("override fun onResume()") != 1:
    raise SystemExit("AssistantSettingsActivity must contain exactly one onResume()")

path.write_text(text, encoding="utf-8")
