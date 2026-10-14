"""Collects every Russian UI string the app translates into i18n/source.json.

Run from the repo root: `python i18n/extract.py`. Sources:
- the first argument of every `tr("…")` call;
- the Russian words passed to `plural(n, "…", "…", "…")`;
- enum labels that are translated on read (`titleRu` / `shortRu` constructor arguments);
- month and country names in ui/Format.kt;
- SERVER_NAMES below: parts of server names that come from the Ghostly subscription.
"""
import json
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parent.parent
DIRS = ["shared/src/commonMain/kotlin", "androidApp/src/main/kotlin", "desktopApp/src/main/kotlin"]
CYR = re.compile(r"[А-Яа-яЁё]")
LIT = r'"((?:[^"\\]|\\.)*)"'

# Parts of server / group names sent by the Ghostly subscription (split on " · ", flags removed).
SERVER_NAMES = [
    "Обычный", "Торрент", "⚡ Быстрый", "🎭 Маскировка VK", "🔀 Авто (выбор лучшего)", "⚡ Авто", "🛟 Резерв",
    "Скрытый", "🥷 Hysteria2", "Белые списки", "⬜ Белые списки", "Белые списки 2", "🤖 ИИ", "🧠 Умный выбор",
    "Стандарт", "Резерв", "Стабильный", "Низкая задержка", "Оптимизированный", "Балансировка", "Расширенный",
]


def unescape(s):
    return s.replace('\\"', '"').replace("\\$", "$").replace("\\n", "\n").replace("\\\\", "\\")


keys = set(SERVER_NAMES)
for d in DIRS:
    for p in (ROOT / d).rglob("*.kt"):
        src = p.read_text(encoding="utf-8")
        for m in re.finditer(r"(?<![\w.])tr\(" + LIT, src):
            keys.add(unescape(m.group(1)))
        for m in re.finditer(r"plural\([^,()]+(?:\([^()]*\))*[^,()]*,\s*" + LIT + r",\s*" + LIT + r",\s*" + LIT, src):
            keys.update(unescape(g) for g in m.groups())
        if re.search(r"private val (titleRu|shortRu)", src):
            for m in re.finditer(r"^\s*[A-Z][A-Z0-9_]*\((.*)\)[,;]?\s*$", src, re.M):
                keys.update(unescape(x) for x in re.findall(LIT, m.group(1)) if CYR.search(x))
fmt = (ROOT / "shared/src/commonMain/kotlin/app/ghostly/ui/Format.kt").read_text(encoding="utf-8")
for name in ("MONTHS", "COUNTRY_NAMES"):
    block = fmt[fmt.index(f"val {name} ="):]
    block = block[:block.index("\n)") if name == "COUNTRY_NAMES" else block.index(")\n")]
    keys.update(x for x in re.findall(LIT, block) if CYR.search(x))

keys = sorted(k for k in keys if CYR.search(k))
out = ROOT / "i18n/source.json"
out.write_text(json.dumps({k: k for k in keys}, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
print(f"{len(keys)} strings -> {out.relative_to(ROOT)}")
