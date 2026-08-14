from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
path = ROOT / "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/AssistantSettingsActivity.kt"
text = path.read_text(encoding="utf-8")

phrases = (
    '"Contacts & calling",',
    '"Say ‘call…’ or ‘позвони…’ to place calls from your phone"',
    '"Allow the assistant to find contacts and place phone calls"',
)

for phrase in phrases:
    lines = text.splitlines(keepends=True)
    matches = [index for index, line in enumerate(lines) if phrase in line]
    if len(matches) != 1:
        raise SystemExit(f"Expected one generated settings line for {phrase!r}, found {len(matches)}")
    index = matches[0]
    newline = "\n" if lines[index].endswith("\n") else ""
    lines[index] = " " * 44 + phrase + newline
    text = "".join(lines)

path.write_text(text, encoding="utf-8")
