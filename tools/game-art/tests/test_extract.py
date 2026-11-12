"""Fast checks against a real game install. Skipped when no game files are available.

Point CRFORGE_ART_APK_DIR at a folder holding the game's APKs (base.apk + split_*.apk) to run them.
"""
from __future__ import annotations

import json
import os
from pathlib import Path

import pytest
from PIL import Image

from crforge_art import extract, scope, sources
from crforge_art.cli import main

APK_DIR = Path(os.environ.get("CRFORGE_ART_APK_DIR",
                              Path(__file__).resolve().parents[4] / "crai" / "game_assets" / "apk"))
APKS = sorted(APK_DIR.glob("*.apk")) if APK_DIR.is_dir() else []
pytestmark = pytest.mark.skipif(not any(p.name.startswith("split_install_time") for p in APKS),
                                reason="no Clash Royale APKs found (set CRFORGE_ART_APK_DIR)")


@pytest.fixture(scope="module")
def src():
    s = sources.from_apks(APKS)
    yield s
    s.close()


def test_fingerprint(src):
    fp = src.fingerprint()
    assert len(fp["sha"]) == 40 and int(fp["sha"], 16) >= 0
    assert fp["version"].count(".") == 2


def test_extract_single_file(tmp_path):
    rc = main(["extract", "--apk", *map(str, APKS), "--out", str(tmp_path), "--only", "chr_knight.sc",
               "--workers", "1"])
    assert rc == 0
    current = json.loads((tmp_path / "current.json").read_text())
    root = tmp_path / current["content_sha"]
    man = json.loads((root / "manifest.json").read_text())
    assert man["format"] == 1 and man["content_sha"] == current["content_sha"]
    entry = man["files"]["chr_knight"]
    assert entry["scene"] == "sc/chr_knight.json"
    scene = json.loads((root / entry["scene"]).read_text())
    assert {"format", "file", "textures", "exports", "shapes", "clips", "banks", "text"} <= scene.keys()
    assert scene["file"] == "chr_knight.sc" and scene["exports"]
    for t, rel in zip(scene["textures"], entry["textures"]):
        assert rel == f"sc/{t['png']}"
        with Image.open(root / rel) as im:
            assert im.size == (t["width"], t["height"]) and im.mode == "RGBA"
    assert (root / "client/csv_logic/characters/knight.toml").read_text().count("chr_knight.sc")
    assert (root / "locations").is_dir() and (root / "tilemaps").is_dir()
    assert not list(root.rglob("*.tmp")) and not (root / ".tmp").exists()

    # idempotent: a second run skips the file
    res = extract.run(sources.from_apks(APKS), tmp_path, only=["chr_knight.sc"], workers=1, log=lambda *_: None)
    assert res.skipped == 1 and res.extracted == 0


def test_battle_scope(src):
    tables = {r: extract.lzma_tables.decode(src.read(r)).decode("utf-8", "replace")
              for r in src.names() if scope.is_battle_table(r)}
    available = [r[3:] for r in src.names("sc/") if r.endswith(".sc")]
    selected, _missing = scope.select("battle", available, tables)
    sel = set(selected)
    assert {"chr_knight.sc", "building_tower.sc", "effects.sc", "ui.sc"} <= sel
    assert any(n.startswith("level_") for n in sel)
    assert "chr_prestige_dl.sc" in available and "chr_prestige_dl.sc" not in sel
    assert not any(scope.is_cosmetic(n) for n in sel)
    everything, _ = scope.select("all", available, tables)
    assert len(everything) == len(set(available)) > len(sel)
