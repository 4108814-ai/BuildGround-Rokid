from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
path = ROOT / "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/NexusAgentPolicy.kt"
text = path.read_text(encoding="utf-8")

old = '''                        "about what was seen, say so plainly and offer to look again. In an ongoing conversation, " +
                        "deictic follow-ups such as 'what is this', 'and this part', 'а вот это' or 'эта деталь' " +
                        "refer to the wearer's current view unless they explicitly refer to the previous photo; " +
                        "capture a fresh photo for those follow-ups.\\n",
'''
new = '''                        "about what was seen, say so plainly and offer to look again. In an ongoing conversation, " +
                        "when a follow-up semantically refers to a newly indicated object or detail in the wearer's " +
                        "current view, capture a fresh photo unless the user explicitly refers to the previous image.\\n",
'''

if text.count(old) != 1:
    raise SystemExit(f"Expected one generated Visual Conversation policy block, found {text.count(old)}")
text = text.replace(old, new, 1)
path.write_text(text, encoding="utf-8")
