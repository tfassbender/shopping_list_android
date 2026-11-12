"""Converts the web app's i18n dictionaries (en.ts / de.ts) into the app's strings.xml files, so both
clients use the same wording. App-only texts live in strings_app.xml (not touched here).

Keys: 'list.menu.rename' -> list_menu_rename. Placeholders {name} -> %1$s (numbered by first appearance in
the English text; German uses the same numbers by name). After a change, ErrorCodes.kt may need new
error codes (ui/common/ErrorCodes.kt maps error_* strings).

Usage (from the repo root):
    python scripts/sync_strings.py <server repo>/frontend/src/i18n [app/src/main/res]
"""
import re
import sys
from pathlib import Path

ENTRY = re.compile(r"""^\s*'?([\w.]+)'?\s*:\s*\n?\s*'((?:[^'\\]|\\.)*)'\s*,?\s*$""", re.M)


def parse(path: Path) -> dict:
    text = path.read_text(encoding="utf-8")
    # join "key':\n    'value'" continuation lines
    text = re.sub(r":\s*\n\s+'", ": '", text)
    return {m.group(1): m.group(2).replace("\\'", "'") for m in ENTRY.finditer(text)}


def res_name(key: str) -> str:
    return "app_name" if key == "appName" else key.replace(".", "_")


def escape(value: str) -> str:
    value = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    value = value.replace("\\", "\\\\").replace("'", "\\'").replace('"', '\\"')
    if value.startswith("@") or value.startswith("?"):
        value = "\\" + value
    return value


def convert(value: str, order: list) -> str:
    value = value.replace("%", "%%") if order else value
    return re.sub(r"\{(\w+)}", lambda m: f"%{order.index(m.group(1)) + 1}$s", value)


def main():
    src = Path(sys.argv[1])
    res = Path(sys.argv[2]) if len(sys.argv) > 2 else Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "res"
    en, de = parse(src / "en.ts"), parse(src / "de.ts")
    missing = set(en) - set(de)
    if missing:
        raise SystemExit(f"missing in de: {missing}")
    for lang, values, folder in (("en", en, "values"), ("de", de, "values-de")):
        lines = ['<?xml version="1.0" encoding="utf-8"?>',
                 "<!-- Generated from the web app's i18n (frontend/src/i18n/" + lang + ".ts in the server repo) by",
                 "     a script - keep the wording in sync with the web app. App-only texts: strings_app.xml. -->",
                 '<resources xmlns:tools="http://schemas.android.com/tools" tools:ignore="UnusedResources,MissingTranslation,TypographyEllipsis,TypographyDashes">']
        for key in en:
            order = list(dict.fromkeys(re.findall(r"\{(\w+)}", en[key])))
            text = escape(convert(values[key], order))
            lines.append(f'    <string name="{res_name(key)}">{text}</string>')
        lines.append("</resources>")
        (res / folder / "strings.xml").write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
        print(folder, len(values))


main()
