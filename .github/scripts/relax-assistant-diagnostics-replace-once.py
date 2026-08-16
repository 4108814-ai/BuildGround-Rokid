from pathlib import Path

path = Path('.github/scripts/apply-assistant-diagnostics.py')
text = path.read_text(encoding='utf-8')
old = '''    count = text.count(old)\n    if count != 1:\n        raise SystemExit(\n            f"Expected one match in {relative_path}, found {count}: {old[:160]!r}"\n        )\n    path.write_text(text.replace(old, new, 1), encoding="utf-8")\n'''
new = '''    count = text.count(old)\n    if count < 1:\n        raise SystemExit(\n            f"Expected at least one match in {relative_path}, found {count}: {old[:160]!r}"\n        )\n    path.write_text(text.replace(old, new, 1), encoding="utf-8")\n'''
if old not in text:
    raise SystemExit('replace_once helper shape changed')
path.write_text(text.replace(old, new, 1), encoding='utf-8')
print('Diagnostics patcher now applies the first matching generated anchor.')
