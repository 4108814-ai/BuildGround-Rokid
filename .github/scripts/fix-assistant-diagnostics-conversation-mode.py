from pathlib import Path

path = Path('.github/scripts/apply-assistant-diagnostics.py')
text = path.read_text(encoding='utf-8')
old = '''    '                val transcript = normalizeTranscript(text)\\n'\n    '                if (transcript.isEmpty()) return\\n'\n    '                finalDelivered = true\\n',\n    '                val transcript = normalizeTranscript(text)\\n'\n    '                if (transcript.isEmpty()) return\\n'\n    '                diagnostics.mark(\"STT_FINAL\", \"chars=${transcript.length}\")\\n'\n    '                finalDelivered = true\\n',\n'''
new = '''    '                val transcript = normalizeTranscript(text)\\n'\n    '                if (transcript.isEmpty()) return\\n'\n    '                followUpController.markUserSpeechStarted()\\n'\n    '                automaticFollowUpCapture = false\\n'\n    '                finalDelivered = true\\n',\n    '                val transcript = normalizeTranscript(text)\\n'\n    '                if (transcript.isEmpty()) return\\n'\n    '                followUpController.markUserSpeechStarted()\\n'\n    '                automaticFollowUpCapture = false\\n'\n    '                diagnostics.mark(\"STT_FINAL\", \"chars=${transcript.length}\")\\n'\n    '                finalDelivered = true\\n',\n'''
count = text.count(old)
if count != 1:
    raise SystemExit(f'Expected one diagnostics Conversation Mode anchor, found {count}')
path.write_text(text.replace(old, new, 1), encoding='utf-8')
print('Patched Assistant diagnostics anchor for Conversation Mode.')
