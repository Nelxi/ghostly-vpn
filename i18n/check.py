"""Checks translation files against i18n/source.json. Run from the repo root: `python i18n/check.py [lang ...]`.

Each shared/src/commonMain/composeResources/files/i18n/<lang>.json must be a flat JSON object whose keys are
source strings, with the same {0}/{1}… placeholders as the key. Missing keys are allowed (the app falls back to
Russian) but reported.
"""
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = json.loads((ROOT / "i18n/source.json").read_text(encoding="utf-8"))
DIR = ROOT / "shared/src/commonMain/composeResources/files/i18n"
PH = re.compile(r"\{\d+\}")

langs = sys.argv[1:] or sorted(p.stem for p in DIR.glob("*.json"))
bad = False
for lang in langs:
    p = DIR / f"{lang}.json"
    try:
        tr = json.loads(p.read_text(encoding="utf-8"))
    except Exception as e:
        print(f"{lang}: BROKEN JSON: {e}"); bad = True; continue
    errors = []
    for k, v in tr.items():
        if k not in SRC: errors.append(f"unknown key: {k!r}")
        elif not isinstance(v, str) or not v.strip(): errors.append(f"empty: {k!r}")
        elif sorted(PH.findall(k)) != sorted(PH.findall(v)): errors.append(f"placeholders differ: {k!r} -> {v!r}")
    missing = [k for k in SRC if k not in tr]
    print(f"{lang}: {len(tr)} translated, {len(missing)} missing, {len(errors)} errors")
    for e in errors[:20]: print("   ", e)
    bad |= bool(errors)
sys.exit(1 if bad else 0)
