"""Which .sc files to extract.

``battle`` scope = every .sc referenced (``"sc/<name>.sc"``, optionally ``#ExportName``) by the
battle-related tables, plus the battle file families (chr_*, building_*, level_*, arena_*,
effects*, ui.sc and the in-battle UI files), minus cosmetic-only files (prestige skins, tower
skins, emotes, chests, shop). ``all`` scope = every .sc in the game.

Tables are read as decoded text keyed by their path relative to assets/.
"""
from __future__ import annotations

import csv
import fnmatch
import io
import json
import re

# tables (path relative to assets/) whose sc references are battle content
BATTLE_TABLES = [
    "csv_logic/characters*.toml", "csv_logic/characters/*", "csv_logic/buildings*",
    "csv_logic/projectiles*", "csv_logic/area_effect_objects*", "csv_logic/spells_*",
    "csv_logic/character_buffs*", "csv_logic/locations.csv", "csv_logic/arenas*.csv",
    "locations/*",                                   # per-arena composition (level_*.sc + export)
    "legacy_vfx/effects.json",                       # the effects table (FileName per SWF component)
    "legacy_vfx/emitters.json",                      # particle emitters (their sprite files)
    "csv_client/health_bars.csv", "csv_client/healthbars.toml", "csv_client/client_globals.csv",
]
# columns that point at menu/collection art or cosmetic variants, not at what a battle draws
SKIP_COLUMNS = {"IconSWF", "HighresPlaceholderAsset", "PopoverIconFileName", "LeagueArenaSWF",
                "PrestigeSWF", "IndicatorPrestigeSWF", "IndicatorReplacementFileName"}
# client_globals.csv lists every preloaded UI file; only these of them are battle-related
CLIENT_GLOBALS_KEEP = {"ui.sc", "ui_battle_banners.sc", "effects.sc"}

FAMILIES = ["chr_*.sc", "building_*.sc", "level_*.sc", "arena_*.sc", "effects*.sc"]
BATTLE_UI = ["ui.sc", "ui_spells.sc", "ui_ability_button.sc", "ui_spectate_hud.sc", "ui_battle*.sc",
             "ui_card_*.sc"]  # newer cards keep their hand icon in a file of their own (IconSWF)
COSMETIC = ["*prestige*", "*towerskin*", "*tower_skin*", "*emote*", "*chest*", "*shop*", "*battle_pass*"]

_REF = re.compile(r"sc/([^\s\"',;#\]\[]+?\.sc)(?![a-z0-9_])", re.I)
_TOML_KEY = re.compile(r"^\s*([A-Za-z0-9_]+)\s*=")


def is_battle_table(rel: str) -> bool:
    return any(fnmatch.fnmatch(rel, p) for p in BATTLE_TABLES)


def is_cosmetic(name: str) -> bool:
    n = name.lower()
    return any(fnmatch.fnmatch(n, p) for p in COSMETIC)


def table_refs(rel: str, text: str) -> list[tuple[str, str]]:
    """(column, sc file name) for every sc reference in a decoded table; column '' if unknown."""
    out: list[tuple[str, str]] = []
    if rel.endswith(".csv") and not rel.startswith("locations/"):
        rows = list(csv.reader(io.StringIO(text)))
        header = rows[0] if rows else []
        for row in rows[1:]:
            for i, v in enumerate(row):
                col = header[i] if i < len(header) else ""
                out += [(col, m) for m in _REF.findall(v)]
    elif rel.endswith(".toml"):
        key = ""
        for line in text.splitlines():
            m = _TOML_KEY.match(line)
            if m:
                key = m.group(1)  # continuation lines of a multi-line array keep the last key
            out += [(key, r) for r in _REF.findall(line)]
    elif rel.endswith(".json"):
        def walk(o, k=""):
            if isinstance(o, dict):
                for kk, v in o.items():
                    walk(v, kk)
            elif isinstance(o, list):
                for v in o:
                    walk(v, k)
            elif isinstance(o, str):
                out.extend((k, r) for r in _REF.findall(o))
        try:
            walk(json.loads(text))
        except ValueError:
            out += [("", r) for r in _REF.findall(text)]
    else:
        out += [("", r) for r in _REF.findall(text)]
    return out


def select(scope: str, available: list[str], tables: dict[str, str]) -> tuple[list[str], list[str]]:
    """Returns (sc file names to extract, referenced-but-missing names), both sorted.

    available: .sc file names present in the source ("chr_knight.sc").
    tables: decoded tables {path relative to assets/: text}.
    """
    have = set(available)
    if scope == "all":
        return sorted(have), []
    lower = {n.lower(): n for n in have}
    wanted: set[str] = set()
    missing: set[str] = set()
    for rel, text in tables.items():
        if not is_battle_table(rel):
            continue
        for col, name in table_refs(rel, text):
            if col in SKIP_COLUMNS or is_cosmetic(name):
                continue
            if rel == "csv_client/client_globals.csv" and name not in CLIENT_GLOBALS_KEEP:
                continue
            real = name if name in have else lower.get(name.lower())
            (wanted.add(real) if real else missing.add(name))
    for n in have:
        if any(fnmatch.fnmatch(n, p) for p in FAMILIES + BATTLE_UI) and not is_cosmetic(n):
            wanted.add(n)
    return sorted(wanted), sorted(missing)
