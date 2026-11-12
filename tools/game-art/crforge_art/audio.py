"""The game's sounds: decode its FMOD banks to .ogg, and mix a recording's sound events into it.

Extraction (`crforge-art extract --audio`): every battle bank (`soundbanks/card_*.bank`,
`ui_battle_*`, `music_arena_*battle*`) holds an FSB5 sample bank (Vorbis); each sample is rebuilt as
an .ogg under `<cache>/audio/<bank>/<sample>.ogg`, listed in `<cache>/audio/index.json`
({bank: {sample: seconds}}). Needs the `fsb5` package and libvorbis/libogg (Homebrew: `brew
install libvorbis`; Debian: `apt install libvorbis0a`).

Mixing (`crforge-art audio VIDEO`): the recorder writes `VIDEO.sounds.jsonl` (one
`{"t": seconds, "sound": "soundbanks/<bank>/<event>"}` per effect sound). An event names an FMOD
event; its samples are the bank's samples named after it without the bank's family prefix
(`card_common_knight_hit` -> `knight_hit_01..`), one picked per play (INFERRED: the banks' event
metadata is not parsed). The battle music (`music_arena_general_battle`) plays underneath.
"""
from __future__ import annotations

import ctypes.util
import hashlib
import json
import os
import subprocess
from pathlib import Path

import numpy as np

RATE = 48000
BATTLE_BANKS = ("card_", "ui_battle_", "music_arena_general_battle")


def _find_library_with_homebrew():
    original = ctypes.util.find_library

    def find(name: str):
        found = original(name)
        if found:
            return found
        for d in ("/opt/homebrew/lib", "/usr/local/lib"):
            p = Path(d) / f"lib{name}.dylib"
            if p.exists():
                return str(p)
        return None

    ctypes.util.find_library = find


def is_battle_bank(name: str) -> bool:
    stem = Path(name).stem
    return stem.startswith(BATTLE_BANKS)


def decode_bank(data: bytes) -> dict[str, bytes]:
    """{sample name: .ogg bytes} of one .bank file."""
    _find_library_with_homebrew()
    import fsb5  # noqa: PLC0415  (optional dependency)

    i = data.find(b"FSB5")
    if i < 0:
        return {}
    bank = fsb5.FSB5(data[i:])
    out = {}
    for s in bank.samples:
        try:
            out[s.name] = bank.rebuild_sample(s)
        except Exception:  # noqa: BLE001  (one bad sample must not lose the bank)
            continue
    return out


def extract(src, root: Path, log=print) -> dict:
    """Decodes the battle banks of a Source into <root>/audio; returns the index."""
    audio = root / "audio"
    audio.mkdir(parents=True, exist_ok=True)
    index_path = audio / "index.json"
    index = json.loads(index_path.read_text()) if index_path.exists() else {}
    banks = [n for n in src.names("soundbanks/") if n.endswith(".bank") and is_battle_bank(n)]
    for n, rel in enumerate(sorted(banks)):
        stem = Path(rel).stem
        if stem in index:
            continue
        try:
            samples = decode_bank(src.read(rel))
        except Exception as e:  # noqa: BLE001
            log(f"audio: {stem}: {e}")
            continue
        d = audio / stem
        d.mkdir(exist_ok=True)
        entry = {}
        for name, ogg in samples.items():
            p = d / f"{name}.ogg"
            p.write_bytes(ogg)
            entry[name] = round(_duration(p), 3)
        index[stem] = entry
        if n % 20 == 0:
            log(f"audio: {n + 1}/{len(banks)} banks")
    index_path.write_text(json.dumps(index, indent=0, sort_keys=True))
    log(f"audio: {len(index)} banks, {sum(len(v) for v in index.values())} samples -> {audio}")
    return index


def _duration(p: Path) -> float:
    try:
        out = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of",
                              "csv=p=0", str(p)], capture_output=True, text=True, timeout=30).stdout
        return float(out.strip() or 0)
    except Exception:  # noqa: BLE001
        return 0.0


def _load(p: Path, cache: dict) -> np.ndarray:
    if p not in cache:
        raw = subprocess.run(["ffmpeg", "-v", "error", "-i", str(p), "-f", "f32le", "-ac", "2", "-ar",
                              str(RATE), "-"], capture_output=True, timeout=60).stdout
        cache[p] = np.frombuffer(raw, np.float32).reshape(-1, 2)
    return cache[p]


def samples_for(event_ref: str, index: dict) -> tuple[str, list[str]]:
    """The bank and its sample names an event reference plays (the longest matching suffix)."""
    parts = event_ref.strip("/").split("/")
    if len(parts) < 2:
        return "", []
    bank, event = parts[-2], parts[-1]
    names = index.get(bank, {})
    tokens = event.replace(" ", "_").split("_")
    for i in range(len(tokens)):
        key = "_".join(tokens[i:])
        cands = sorted(s for s in names if s == key or s.startswith(key + "_") or s.startswith(key))
        if cands and len(key) >= 3:
            return bank, cands
    # the samples' own abbreviations of the event's action (attack -> atk / fire, deploy -> dep)
    action = event[len(bank):].strip("_ ") if event.startswith(bank) else tokens[-1]
    for word in reversed(action.replace(" ", "_").split("_")):
        for syn in SYNONYMS.get(word, [word]):
            cands = sorted(s for s in names if syn in s.split("_"))
            if cands:
                return bank, cands
    return bank, []


SYNONYMS = {
    "attack": ["attack", "atk", "fire", "shoot", "shot", "throw"],
    "die": ["die", "death", "dead", "gone"],
    "death": ["death", "die", "dead", "gone"],
    "deploy": ["deploy", "dep", "spawn", "drop"],
    "hit": ["hit", "impact", "imp"],
    "impact": ["impact", "hit", "imp"],
    "spawn": ["spawn", "deploy", "dep"],
}


def mix(video: Path, cache_root: Path, out: Path | None = None, music: bool = True,
        sfx_gain: float = 0.7, music_gain: float = 0.28) -> Path:
    """Mixes VIDEO.sounds.jsonl (and the battle music) into a copy of VIDEO with an audio track."""
    events_path = video.with_name(video.name + ".sounds.jsonl")
    audio = cache_root / "audio"
    index = json.loads((audio / "index.json").read_text())
    dur = float(subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of",
                                "csv=p=0", str(video)], capture_output=True, text=True).stdout.strip())
    n = int(dur * RATE) + RATE
    buf = np.zeros((n, 2), np.float32)
    decoded: dict = {}
    played = missing = 0
    if events_path.exists():
        for line in events_path.read_text().splitlines():
            if not line.strip():
                continue
            ev = json.loads(line)
            bank, cands = samples_for(ev["sound"], index)
            if not cands:
                missing += 1
                continue
            h = int(hashlib.md5(f"{ev['t']:.3f}{ev['sound']}".encode()).hexdigest(), 16)
            s = _load(audio / bank / f"{cands[h % len(cands)]}.ogg", decoded)
            start = int(ev["t"] * RATE)
            end = min(n, start + len(s))
            if end > start:
                buf[start:end] += s[: end - start] * sfx_gain
                played += 1
    if music and "music_arena_general_battle" in index:
        tracks = sorted(index["music_arena_general_battle"].items(), key=lambda kv: -kv[1])
        if tracks:
            s = _load(audio / "music_arena_general_battle" / f"{tracks[0][0]}.ogg", decoded)
            pos = 0
            while pos < n and len(s):
                end = min(n, pos + len(s))
                buf[pos:end] += s[: end - pos] * music_gain
                pos = end
    buf = np.tanh(buf)  # soft limit
    wav = video.with_suffix(".mix.wav")
    _write_wav(wav, buf)
    out = out or video.with_name(video.stem + "_audio.mp4")
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(video), "-i", str(wav), "-map", "0:v",
                    "-map", "1:a", "-c:v", "copy", "-c:a", "aac", "-b:a", "192k", "-shortest", str(out)],
                   check=True)
    os.remove(wav)
    print(f"audio: {played} sounds mixed, {missing} without samples -> {out}")
    return out


def _write_wav(path: Path, buf: np.ndarray) -> None:
    import wave

    pcm = (np.clip(buf, -1, 1) * 32767).astype("<i2")
    with wave.open(str(path), "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(pcm.tobytes())
