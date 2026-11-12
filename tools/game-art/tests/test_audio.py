"""Audio: the FMOD bank metadata parser (skipped without the game's APKs) and the mixer's voice
limits (synthetic, needs ffmpeg)."""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import wave
from pathlib import Path

import numpy as np
import pytest

from crforge_art import audio, fmod

APK_DIR = Path(os.environ.get("CRFORGE_ART_APK_DIR",
                              Path(__file__).resolve().parents[4] / "crai" / "game_assets" / "apk"))
APKS = sorted(APK_DIR.glob("*.apk")) if APK_DIR.is_dir() else []
needs_game = pytest.mark.skipif(not any(p.name.startswith("split_install_time") for p in APKS),
                                reason="no Clash Royale APKs found (set CRFORGE_ART_APK_DIR)")
needs_ffmpeg = pytest.mark.skipif(shutil.which("ffmpeg") is None, reason="ffmpeg not on PATH")


@pytest.fixture(scope="module")
def src():
    from crforge_art import sources

    s = sources.from_apks(APKS)
    yield s
    s.close()


@needs_game
def test_strings_bank_paths(src):
    paths = fmod.strings(fmod.chunk(src.read(audio.STRINGS_BANK), "STDT"))
    values = set(paths.values())
    assert len(paths) > 1000
    assert "event:/Main/Card/Common/card_common_knight/card_common_knight_hit" in values
    assert "bus:/SFX" in values


@needs_game
def test_knight_events(src):
    paths = fmod.strings(fmod.chunk(src.read(audio.STRINGS_BANK), "STDT"))
    mixer = fmod.parse(src.read(audio.MASTER_BANK)).buses
    data = src.read("soundbanks/card_common_knight.bank")
    names = audio._unique_names(audio._fsb(data).samples)
    events = fmod.bank_events(data, names, paths, mixer)
    hit = events["card_common_knight_hit"]
    assert hit["path"].endswith("/card_common_knight/card_common_knight_hit")
    assert [c["sample"] for c in hit["layers"][0]["choices"]] == ["knight_hit_01"]
    assert hit["max_instances"] == 3
    assert hit["layers"][0]["choices"][0]["volume_db"] == pytest.approx(-14.5)
    start = events["card_common_knight_attack_start"]["layers"][0]["choices"]
    assert sorted(c["sample"] for c in start) == [f"knight_attack_start_0{i}" for i in range(1, 6)]
    assert sum(c["weight"] for c in start) == pytest.approx(1.0)
    assert start[0]["delay"] == [pytest.approx(0.36), pytest.approx(0.36)]


def _write_wav(path: Path, x: np.ndarray, rate: int = audio.RATE) -> None:
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes((np.clip(x, -1, 1) * 32767).astype("<i2").tobytes())


def _peak(video: Path) -> float:
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", str(video), "-f", "f32le", "-ac", "1", "-"],
                         capture_output=True, check=True).stdout
    return float(np.abs(np.frombuffer(raw, np.float32)).max())


@needs_ffmpeg
def test_mix_caps_identical_voices(tmp_path):
    root = tmp_path / "cache"
    (root / "audio" / "b").mkdir(parents=True)
    t = np.arange(int(0.3 * audio.RATE)) / audio.RATE
    _write_wav(root / "audio" / "b" / "s.ogg", 0.05 * np.sin(2 * np.pi * 440 * t))  # ffmpeg sniffs
    choice = {"sample": "s", "weight": 1.0, "volume_db": 0.0, "pitch": 0.0, "rand_volume_db": 0.0,
              "rand_pitch": 0.0, "delay": [0.0, 0.0], "loop": False}
    ev = {"volume_db": 0.0, "max_instances": 2, "steal": 64, "cooldown": 0.0, "path": "event:/b/e",
          "layers": [{"start": 0.0, "length": 0.3, "probability": 1.0, "choices": [choice]}]}
    (root / "audio" / "index.json").write_text(json.dumps(
        {"format": audio.FORMAT, "banks": {"b": {"s": 0.3}}, "events": {"b/e": ev}}))
    video = tmp_path / "v.mp4"
    subprocess.run(["ffmpeg", "-v", "error", "-f", "lavfi", "-i", "color=c=black:s=64x64:d=2", "-r",
                    "10", str(video)], check=True)

    def mixed(times):
        video.with_name(video.name + ".sounds.jsonl").write_text(
            "".join(json.dumps({"t": x, "sound": "soundbanks/b/e"}) + "\n" for x in times))
        return _peak(audio.mix(video, root, tmp_path / "out.mp4", music=False, normalize=False))

    one = mixed([0.5])
    assert one == pytest.approx(0.05, rel=0.15)
    # 30 starts within 30 ms (a volley): one voice; 30 starts 40 ms apart: at most 2 at a time
    assert mixed([0.5 + 0.001 * i for i in range(30)]) == pytest.approx(one, rel=0.15)
    assert mixed([0.2 + 0.04 * i for i in range(30)]) <= 2.2 * one


def test_limiter_ceiling():
    rng = np.random.default_rng(1)
    x = (rng.standard_normal((48000, 2)) * 0.8).astype(np.float32)
    y = audio._limit(x)
    assert np.abs(y).max() <= 0.9
    quiet = (x * 0.05).astype(np.float32)
    assert np.allclose(audio._limit(quiet), quiet, atol=1e-6)
