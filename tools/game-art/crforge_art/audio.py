"""The game's sounds: decode its FMOD banks, and mix a recording's sound events into it.

Extraction (`crforge-art extract --audio`): every battle bank (`soundbanks/card_*.bank`,
`ui_battle_*`, the general battle music, the arena jingles and `ui_menu_general`, which holds the
arena ambience and the match-start jingle) holds an FSB5 sample bank (Vorbis); each sample is
rebuilt as an .ogg under `<cache>/audio/<bank>/<sample>.ogg`. Needs the `fsb5` package and
libvorbis/libogg (Homebrew: `brew install libvorbis`; Debian: `apt install libvorbis0a`).

The banks' FMOD Studio metadata (see `fmod.py`) says what each event plays; it is resolved at
extraction into `<cache>/audio/index.json`:

    {"format": 2,
     "banks": {bank: {sample: seconds}},
     "events": {"<bank>/<event>": {
         "path": "event:/Main/Card/Common/card_common_knight/card_common_knight_hit",
         "volume_db": event master + mixer bus volume, "max_instances": n (2147483647 = no limit),
         "steal": FMOD stealing flags (0 = a new instance is dropped at the limit; INFERRED),
         "cooldown": seconds between two starts,
         "layers": [{"start": s, "length": s, "probability": 0..1,
                     "choices": [{"sample", "weight", "volume_db", "pitch" (semitones),
                                  "rand_volume_db", "rand_pitch", "delay": [min, max] s,
                                  "loop"}]}]}}}

Every layer (an instrument on the event's timeline) plays at its start; per trigger one of its
choices is picked by weight (a multi instrument), attenuated by up to `rand_volume_db` and detuned
by up to +-rand_pitch/2 semitones (the random modulators).

Mixing (`crforge-art audio VIDEO`): the recorder writes `VIDEO.sounds.jsonl` (one
`{"t": seconds, "sound": "soundbanks/<bank>/<event>"}` per sound). `<bank>/<event>` keys the
events table; refs it lacks fall back to a sample-name heuristic (`samples_for`). Music events
(banks `music_arena_*`) switch the looping battle music instead of playing once; without any, the
general battle music (`music_arena_general_battle`) loops from the start. A line `{"t": s, "end":
true}` is the match's end: the battle music stops there (the end jingles are ordinary sounds) and no
timed battle sound (countdown, music change) plays after it. The mix is normalized to about -16 LUFS
and peak-limited.

Thinning (INFERRED, ours; the banks' own instance limits and cooldowns are applied first): a
repeated short sound would otherwise stack into a machine-gun clatter in swarms, so
- footsteps and wing flaps (events ending `_steps`, `_step`, `_wing`) start at most every
  STEP_GAP seconds per event and play FOOTSTEP_DB quieter;
- voice lines (`_vo`) start at most every VO_GAP seconds per event;
- each instance of an event still playing lowers a new one by STACK_DB (at most STACK_MAX_DB).
"""
from __future__ import annotations

import ctypes.util
import json
import os
import subprocess
from pathlib import Path

import numpy as np

from . import fmod

RATE = 48000
FORMAT = 2
BATTLE_BANKS = ("card_", "ui_battle_", "music_arena_general_battle", "music_jingle_general",
                "ui_menu_general", "uncategorized", "emoji", "ui_hero_form")
STRINGS_BANK = "soundbanks/clashroyale.strings.bank"
MASTER_BANK = "soundbanks/clashroyale.bank"
BATTLE_MUSIC = "music_arena_general_battle/music_arena_general_battle"
SILENCE = "music_arena_silence/silence"  # a music cue that stops the music (ours)
MIN_GAP = 0.03  # ours, not the game's: one event restarting within 30 ms only phases/stacks
FALLBACK_DB = -12.0  # a heuristic match's volume (the metadata's typical instrument volume)
# thinning (INFERRED, see the module doc)
STEP_GAP = 0.3
FOOTSTEP_DB = -4.0
VO_GAP = 2.0
STACK_DB = -2.0
STACK_MAX_DB = -6.0
MUSIC_END_FADE = 0.4  # seconds the battle music fades out over at the match's end


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


def _fsb(data: bytes):
    _find_library_with_homebrew()
    import fsb5  # noqa: PLC0415  (optional dependency)

    i = data.find(b"FSB5")
    return fsb5.FSB5(data[i:]) if i >= 0 else None


def _unique_names(samples) -> list[str]:
    names, seen = [], set()
    for i, s in enumerate(samples):
        n = s.name or f"sample_{i}"
        if n in seen:
            n = f"{n}~{i}"
        seen.add(n)
        names.append(n)
    return names


def decode_bank(data: bytes) -> list[tuple[str, float, bytes | None]]:
    """(sample name, seconds, .ogg bytes or None if it failed) of one .bank file, in FSB order (the
    order the bank's waveforms index)."""
    bank = _fsb(data)
    if bank is None:
        return []
    out = []
    for name, s in zip(_unique_names(bank.samples), bank.samples):
        secs = s.samples / s.frequency if s.frequency else 0.0
        try:
            ogg = bank.rebuild_sample(s)
        except Exception:  # noqa: BLE001  (one bad sample must not lose the bank)
            ogg = None
        out.append((name, secs, ogg))
    return out


def _load_index(path: Path) -> dict:
    index = json.loads(path.read_text()) if path.exists() else {}
    if index.get("format") != FORMAT:
        # format 1 was {bank: {sample: seconds}}, without events
        index = {"format": FORMAT, "banks": {}, "events": {}}
    return index


def extract(src, root: Path, log=print, force: bool = False) -> dict:
    """Decodes the battle banks of a Source into <root>/audio and resolves their events; returns
    the index."""
    audio = root / "audio"
    audio.mkdir(parents=True, exist_ok=True)
    index_path = audio / "index.json"
    index = {"format": FORMAT, "banks": {}, "events": {}} if force else _load_index(index_path)
    paths: dict[bytes, str] = {}
    mixer: dict = {}
    try:
        if src.has(STRINGS_BANK):
            paths = fmod.strings(fmod.chunk(src.read(STRINGS_BANK), "STDT") or b"")
        if src.has(MASTER_BANK):
            mixer = fmod.parse(src.read(MASTER_BANK)).buses
    except Exception as e:  # noqa: BLE001  (events then lack paths/mixer volumes, nothing worse)
        log(f"audio: master/strings bank: {e}")
    banks = sorted(n for n in src.names("soundbanks/") if n.endswith(".bank") and is_battle_bank(n))
    done = 0
    for rel in banks:
        stem = Path(rel).stem
        if stem in index["banks"]:
            continue
        try:
            data = src.read(rel)
            samples = decode_bank(data)
        except Exception as e:  # noqa: BLE001
            log(f"audio: {stem}: {e}")
            continue
        d = audio / stem
        d.mkdir(exist_ok=True)
        entry = {}
        for name, secs, ogg in samples:
            if ogg is None:
                continue
            (d / f"{name}.ogg").write_bytes(ogg)
            entry[name] = round(secs, 3)
        index["banks"][stem] = entry
        try:
            events = fmod.bank_events(data, [n for n, _, _ in samples], paths, mixer)
        except Exception as e:  # noqa: BLE001  (the heuristic still covers this bank)
            log(f"audio: {stem}: events: {e}")
            events = {}
        for name, ev in events.items():
            index["events"][f"{stem}/{name}"] = ev
        done += 1
        if done % 25 == 0:
            log(f"audio: {done} banks decoded")
    index_path.write_text(json.dumps(index, separators=(",", ":"), sort_keys=True))
    log(f"audio: {len(index['banks'])} banks, {sum(len(v) for v in index['banks'].values())} "
        f"samples, {len(index['events'])} events -> {audio}")
    return index


def _load(p: Path, cache: dict) -> np.ndarray:
    if p not in cache:
        raw = subprocess.run(["ffmpeg", "-v", "error", "-i", str(p), "-f", "f32le", "-ac", "2", "-ar",
                              str(RATE), "-"], capture_output=True, timeout=120).stdout
        cache[p] = np.frombuffer(raw, np.float32).reshape(-1, 2)
    return cache[p]


def samples_for(event_ref: str, banks: dict) -> tuple[str, list[str]]:
    """The heuristic: the bank and its sample names an event reference plays (the longest matching
    suffix of the event name, then the samples' abbreviations of its action)."""
    parts = event_ref.strip("/").split("/")
    if len(parts) < 2:
        return "", []
    bank, event = parts[-2], parts[-1]
    names = banks.get(bank, {})
    tokens = event.replace(" ", "_").split("_")
    for i in range(len(tokens)):
        key = "_".join(tokens[i:])
        cands = sorted(s for s in names if s == key or s.startswith(key + "_") or s.startswith(key))
        if cands and len(key) >= 3:
            return bank, cands
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


def event_key(ref: str) -> str:
    """`soundbanks/<bank>/<event>` -> `<bank>/<event>`."""
    parts = ref.strip("/").split("/")
    return "/".join(parts[-2:])


def lookup(ref: str, index: dict) -> tuple[str, dict | None]:
    """("exact" | "fallback" | "missing", event description) of a sound reference."""
    key = event_key(ref)
    events = index.get("events", {})
    ev = events.get(key)
    if ev is None:
        low = key.lower().replace(" ", "_")
        ev = next((v for k, v in events.items() if k.lower().replace(" ", "_") == low), None)
    if ev is not None:  # (an event without layers is silent in the game too)
        return "exact", ev
    bank, cands = samples_for(ref, index.get("banks", {}))
    if not cands:
        return "missing", None
    choices = [{"sample": c, "weight": 1 / len(cands), "volume_db": 0.0, "pitch": 0.0,
                "rand_volume_db": 0.0, "rand_pitch": 0.0, "delay": [0.0, 0.0], "loop": False}
               for c in cands]
    return "fallback", {"volume_db": FALLBACK_DB, "max_instances": 3, "steal": 64, "cooldown": 0.05,
                        "layers": [{"start": 0.0, "length": 0.0, "probability": 1.0,
                                    "choices": choices}], "bank": bank}


def _db(x: float) -> float:
    return float(10 ** (x / 20))


def _pitched(s: np.ndarray, semitones: float) -> np.ndarray:
    if abs(semitones) < 0.01 or len(s) < 2:
        return s
    ratio = 2 ** (semitones / 12)
    n = max(1, int(len(s) / ratio))
    x = np.arange(n) * ratio
    return np.stack([np.interp(x, np.arange(len(s)), s[:, c]) for c in range(2)], 1).astype(np.float32)


class _Voice:
    """One playing event instance: its sample parts, and when it was stopped (stolen), if."""

    __slots__ = ("t", "end", "parts", "stop")

    def __init__(self, t: float):
        self.t = t
        self.end = t
        self.parts: list[tuple[int, np.ndarray, float]] = []
        self.stop: float | None = None


def _bank_of(key: str) -> str:
    return key.split("/", 1)[0]


def _is_music(key: str) -> bool:
    return _bank_of(key).startswith("music_arena_")


def _pick(choices: list[dict], rng) -> dict:
    w = np.array([max(c["weight"], 0.0) for c in choices], float)
    if w.sum() <= 0:
        w = np.ones(len(w))
    return choices[int(rng.choice(len(choices), p=w / w.sum()))]


def mix(video: Path, cache_root: Path, out: Path | None = None, music: bool = True,
        sfx_db: float = 0.0, music_db: float = 0.0, seed: int = 0, overtime_at: float | None = None,
        normalize: bool = True, report: bool = False, clock: float | None = None,
        location: str = "PvP_royal") -> Path:
    """Mixes VIDEO.sounds.jsonl (and the battle music) into a copy of VIDEO with an audio track.

    `clock`: the battle's remaining regulation seconds at the video's start (180 = it starts at the
    battle's start); adds the game's own timed battle sounds and music changes
    (`battle_schedule`). `overtime_at`: the video time overtime starts (sudden-death music and the
    extra-time jingle). The sounds file can give both instead: a line `{"t": s, "clock": seconds
    left}` and a line `{"t": s, "overtime": true}`."""
    events_path = video.with_name(video.name + ".sounds.jsonl")
    audio = cache_root / "audio"
    index = json.loads((audio / "index.json").read_text())
    if index.get("format") != FORMAT:  # an old cache: samples only
        index = {"banks": {k: v for k, v in index.items() if isinstance(v, dict)}, "events": {}}
    dur = float(subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of",
                                "csv=p=0", str(video)], capture_output=True, text=True).stdout.strip())
    n = int(dur * RATE) + RATE
    buf = np.zeros((n, 2), np.float32)
    decoded: dict = {}
    rng = np.random.default_rng(seed)
    refs = []
    end_at: float | None = None
    if events_path.exists():
        for line in events_path.read_text().splitlines():
            if not line.strip():
                continue
            ev = json.loads(line)
            if "sound" in ev:
                refs.append((float(ev["t"]), ev["sound"]))
            elif "clock" in ev and clock is None:  # {"t", "clock": seconds left in regulation}
                clock = float(ev["clock"]) + float(ev["t"])
            elif ev.get("overtime") and overtime_at is None:  # {"t", "overtime": true}
                overtime_at = float(ev["t"])
            elif ev.get("end") and end_at is None:  # {"t", "end": true}
                end_at = float(ev["t"])
    if clock is not None or overtime_at is not None:
        timed = battle_schedule(cache_root, clock, dur, overtime_at, location, index)
        refs.extend((t, ref) for t, ref in timed if end_at is None or t < end_at)
    refs.sort(key=lambda r: r[0])

    stats = {"exact": 0, "fallback": 0, "missing": 0, "cooldown": 0, "limit": 0, "stolen": 0,
             "played": 0}
    per_ref: dict[str, str] = {}
    voices: dict[str, list[_Voice]] = {}
    last_start: dict[str, float] = {}
    all_voices: list[_Voice] = []
    music_cues: list[tuple[float, str]] = []

    for t, ref in refs:
        key = event_key(ref)
        if _is_music(key):
            music_cues.append((t, key))
            continue
        how, ev = lookup(ref, index)
        stats[how] += 1
        per_ref[key] = how
        if ev is None:
            continue
        cooldown = max(ev.get("cooldown", 0.0), MIN_GAP)
        footstep = _is_footstep(key)
        if footstep:
            cooldown = max(cooldown, STEP_GAP)
        elif key.endswith("_vo"):
            cooldown = max(cooldown, VO_GAP)
        if key in last_start and t - last_start[key] < cooldown:
            stats["cooldown"] += 1
            continue
        active = [v for v in voices.get(key, []) if v.end > t and (v.stop is None or v.stop > t)]
        thin_db = max(STACK_MAX_DB, STACK_DB * len(active)) + (FOOTSTEP_DB if footstep else 0.0)
        if len(active) >= ev.get("max_instances", 1 << 31):
            if ev.get("steal", 64) == 0:  # stealing "none": the new instance does not start
                stats["limit"] += 1
                continue
            oldest = min(active, key=lambda v: v.t)
            oldest.stop = t
            stats["stolen"] += 1
            active.remove(oldest)
        bank = ev.get("bank") or _bank_of(key)
        voice = _Voice(t)
        for layer in ev["layers"]:
            if rng.random() > layer.get("probability", 1.0):
                continue
            c = _pick(layer["choices"], rng)
            if c["sample"] is None:
                continue
            p = audio / bank / f"{c['sample']}.ogg"
            if not p.exists():
                continue
            pitch = c["pitch"] + (rng.uniform(-0.5, 0.5) * c["rand_pitch"] if c["rand_pitch"] else 0)
            s = _pitched(_load(p, decoded), pitch)
            gain_db = ev["volume_db"] + c["volume_db"] + sfx_db + thin_db - (
                rng.uniform(0, c["rand_volume_db"]) if c["rand_volume_db"] else 0)
            delay = rng.uniform(*c["delay"]) if c["delay"][1] > c["delay"][0] else c["delay"][0]
            start = t + layer["start"] + delay
            if c.get("loop") and layer.get("length", 0) > len(s) / RATE:  # loops over its region
                reps = int(np.ceil(layer["length"] * RATE / max(len(s), 1)))
                s = np.tile(s, (reps, 1))[: int(layer["length"] * RATE)]
            voice.parts.append((int(start * RATE), s, _db(gain_db)))
            voice.end = max(voice.end, start + len(s) / RATE)
        if not voice.parts:
            continue
        voices.setdefault(key, []).append(voice)
        all_voices.append(voice)
        last_start[key] = t
        stats["played"] += 1

    fade = int(0.02 * RATE)
    for v in all_voices:
        for at, s, g in v.parts:
            if at >= n:
                continue
            seg = s[: n - at] * g
            if v.stop is not None:
                cut = int(v.stop * RATE) - at
                if cut <= 0:
                    continue
                seg = seg[: cut + fade].copy()
                k = len(seg) - cut
                if k > 0:
                    seg[cut:] *= np.linspace(1, 0, k, dtype=np.float32)[:, None]
            if at < 0:  # started before the video
                seg, at = seg[-at:], 0
            buf[at:at + len(seg)] += seg

    if music:
        music_cues.sort()
        if not music_cues or music_cues[0][0] > 0.5:
            music_cues.insert(0, (0.0, BATTLE_MUSIC))
        if end_at is not None:  # the battle music stops at the match's end
            music_cues = [c for c in music_cues if c[0] < end_at] + [(end_at, SILENCE)]
        _mix_music(buf, music_cues, index, audio, decoded, rng, music_db)

    if normalize:
        buf *= _db(_normalize_db(buf))
    buf = _limit(buf)
    wav = video.with_suffix(".mix.wav")
    _write_wav(wav, buf)
    out = out or video.with_name(video.stem + "_audio.mp4")
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(video), "-i", str(wav), "-map", "0:v",
                    "-map", "1:a", "-c:v", "copy", "-c:a", "aac", "-b:a", "192k", "-shortest", str(out)],
                   check=True)
    os.remove(wav)
    if report:
        for key, how in sorted(per_ref.items()):
            print(f"  {how:8s} {key}")
    print(f"audio: {stats['exact']} sounds from the bank metadata, {stats['fallback']} by sample name, "
          f"{stats['missing']} without samples; {stats['played']} played, {stats['cooldown']} within "
          f"cooldown, {stats['limit'] + stats['stolen']} over the instance limit "
          f"({stats['stolen']} stolen) -> {out}")
    return out


def _is_footstep(key: str) -> bool:
    return key.endswith(("_steps", "_step", "_wing"))


def _csv_rows(path: Path) -> list[dict]:
    import csv

    if not path.exists():
        return []
    rows = list(csv.reader(path.read_text(encoding="utf-8", errors="replace").splitlines()))
    return [dict(zip(rows[0], r)) for r in rows[2:]] if len(rows) > 2 else []


def _globals_array(rows: list[dict], name: str, column: str) -> list[str]:
    """A client_globals array: the named row and its unnamed continuation rows."""
    out, inside = [], False
    for r in rows:
        key = r.get(" ", "") or r.get("", "")
        if key:
            inside = key == name
        if inside and r.get(column):
            out.append(r[column])
    return out


def battle_schedule(cache_root: Path, clock: float | None, dur: float,
                    overtime_at: float | None = None, location: str = "PvP_royal",
                    index: dict | None = None) -> list[tuple[float, str]]:
    """The sounds the game itself times in a battle, as (video seconds, sound reference):

    - client_globals SOUNDS_PVP at SOUND_START_TIMES (seconds left: the 60 s / 30 s warnings and
      the final 10..1 countdown);
    - MUSICS_PVP at MUSIC_START_TIMES (BattleMusic from 180 s left, 60sec_loop, 30sec_loop),
      named in csv_client/music.csv;
    - the location's start jingle (locations.csv "Sound") when the video starts the battle. In the
      game it plays over the intro banners, before the clock (and the BattleMusic at 180 s left)
      starts; a recording starts at the clock, so the music waits for the jingle's length here
      (INFERRED), a silent cue (SILENCE) holding the time until then;
    - at overtime, the location's ExtraTimeMusic and the extra-time jingle.
    `clock` is the seconds left at video time 0 (None: only the overtime part)."""
    client = cache_root / "client"
    glob_rows = _csv_rows(client / "csv_client" / "client_globals.csv")
    music_rows = {r["Name"]: r for r in _csv_rows(client / "csv_client" / "music.csv")}
    loc = next((r for r in _csv_rows(client / "csv_logic" / "locations.csv") if r.get("Name") == location),
               {})

    def music_ref(name: str) -> str | None:
        r = music_rows.get(name)
        if not r or not r.get("EventName"):
            return None
        return f"soundbanks/{Path(r['BankName']).stem}/{r['EventName'].rsplit('/', 1)[-1]}"

    out: list[tuple[float, str]] = []
    if clock is not None:
        sounds = _globals_array(glob_rows, "SOUNDS_PVP", "StringArray")
        times = [float(x) for x in _globals_array(glob_rows, "SOUND_START_TIMES", "NumberArray")]
        times = times[len(times) - len(sounds):] if len(times) >= len(sounds) else times
        for left, ref in zip(times, sounds):
            if 0 <= clock - left < dur:
                out.append((clock - left, ref))
        musics = _globals_array(glob_rows, "MUSICS_PVP", "StringArray")
        mtimes = [float(x) for x in _globals_array(glob_rows, "MUSIC_START_TIMES", "NumberArray")]
        cues = sorted((clock - left, music_ref(name)) for left, name in zip(mtimes, musics))
        cues = [(t, ref) for t, ref in cues if ref]
        playing = [c for c in cues if c[0] <= 0]  # the music already on at the video's start
        if playing:
            out.append((0.0, playing[-1][1]))
        out += [(t, ref) for t, ref in cues if 0 < t < dur]
        if clock >= 179.5 and loc.get("Sound"):
            t0 = max(0.0, clock - 180)
            out.append((t0, loc["Sound"]))
            wait = _event_seconds(loc["Sound"], index or {}, cache_root / "audio")
            if wait > 0:
                late = [(t, ref) for t, ref in out if _is_music(event_key(ref)) and t <= t0 + wait]
                for cue in late:
                    out.remove(cue)
                out.append((t0, f"soundbanks/{SILENCE}"))
                if late:
                    out.append((t0 + wait, late[-1][1]))
    if overtime_at is not None and overtime_at < dur:
        ref = music_ref(loc.get("ExtraTimeMusic") or "Sudden_death")
        if ref:
            out.append((overtime_at, ref))
        out.append((overtime_at, "soundbanks/ui_battle_general/ui_battle_general_extra_time_jingle"))
    return out


def _event_seconds(ref: str, index: dict, audio: Path | None = None) -> float:
    """How long an event plays: its longest layer's start plus its longest choice's sample. With
    `audio` (the samples' folder), up to the samples' last audible point (above -40 dBFS), so a
    sample's silent tail does not count (a jingle's tail would leave a gap before the music)."""
    ev = index.get("events", {}).get(event_key(ref))
    if not ev:
        return 0.0
    bank = ev.get("bank") or _bank_of(event_key(ref))
    names = index.get("banks", {}).get(bank, {})
    out = 0.0
    for layer in ev.get("layers", []):
        for c in layer.get("choices", []):
            if c.get("sample") in names:
                length = names[c["sample"]]
                p = audio / bank / f"{c['sample']}.ogg" if audio is not None else None
                if p is not None and p.exists():
                    loud = np.nonzero(np.abs(_load(p, {})).max(axis=1) > _db(-40.0))[0]
                    if len(loud):
                        length = (loud[-1] + 1) / RATE
                out = max(out, layer.get("start", 0.0) + length)
    return out


def _mix_music(buf, cues, index, audio, decoded, rng, music_db):
    """Loops each music event from its cue to the next one (a short fade between them; SILENCE
    stops the music, fading out over MUSIC_END_FADE)."""
    n = len(buf)
    fade = int(1.0 * RATE)
    for i, (t, key) in enumerate(cues):
        if key == SILENCE:
            continue
        if i + 1 < len(cues) and cues[i + 1][1] == SILENCE:
            fade = int(MUSIC_END_FADE * RATE)
        else:
            fade = int(1.0 * RATE)
        ev = index.get("events", {}).get(key)
        bank = _bank_of(key)
        if ev is None or not ev["layers"]:
            names = index.get("banks", {}).get(bank, {})
            if not names:
                continue
            longest = max(names, key=names.get)
            ev = {"volume_db": -5.0, "layers": [{"choices": [
                {"sample": longest, "weight": 1, "volume_db": 0.0}]}]}
        start = int(t * RATE)
        end = int(cues[i + 1][0] * RATE) if i + 1 < len(cues) else n
        end = min(n, end)
        if end <= start:
            continue
        choices = [c for c in ev["layers"][0]["choices"] if c["sample"]]
        if not choices:
            continue
        order = list(rng.permutation(len(choices)))  # a shuffled playlist, looped
        track = []
        length = 0
        k = 0
        while length < end - start + fade:
            c = choices[order[k % len(order)]]
            p = audio / bank / f"{c['sample']}.ogg"
            if not p.exists():
                break
            s = _load(p, decoded) * _db(ev["volume_db"] + c["volume_db"] + music_db)
            if not len(s):
                break
            track.append(s)
            length += len(s)
            k += 1
        if not track:
            continue
        seg = np.concatenate(track)[: min(end - start + (fade if end < n else 0), n - start)].copy()
        if end < n and len(seg) > end - start:  # fade out under the next cue
            k = len(seg) - (end - start)
            seg[end - start:] *= np.linspace(1, 0, k, dtype=np.float32)[:, None]
        buf[start:start + len(seg)] += seg


def _normalize_db(buf: np.ndarray, target: float = -18.5) -> float:
    """The gain (dB) that brings the mix's gated loudness (mean square of 400 ms blocks above
    -50 dBFS; unweighted, so about 2.5 dB under the LUFS of a battle mix: -18.5 here is about
    -16 LUFS) to `target`."""
    w = int(0.4 * RATE)
    nb = len(buf) // w
    if nb == 0:
        return 0.0
    ms = (buf[: nb * w].astype(np.float64) ** 2).reshape(nb, w, 2).mean(axis=(1, 2))
    ms = ms[ms > 10 ** (-50 / 10)]
    if not len(ms):
        return 0.0
    loud = 10 * np.log10(ms.mean())
    return float(np.clip(target - loud, -20, 30))


def _limit(buf: np.ndarray, ceiling: float = 0.89, release: float = 0.15) -> np.ndarray:
    """A look-ahead peak limiter: never above `ceiling` (-1 dBFS), gain recovering over `release`
    seconds."""
    block = 48  # 1 ms
    nb = (len(buf) + block - 1) // block
    peak = np.abs(buf).max(axis=1)
    peak = np.pad(peak, (0, nb * block - len(peak))).reshape(nb, block).max(axis=1)
    need = np.minimum(1.0, ceiling / np.maximum(peak, 1e-9))
    look = 5  # ms of look-ahead: the gain is down before the peak arrives
    ahead = need.copy()
    for k in range(1, look + 1):
        ahead[:-k] = np.minimum(ahead[:-k], need[k:])
    g = np.empty(nb)
    cur = 1.0
    coef = np.exp(-1.0 / (release * 1000))
    for i in range(nb):
        a = ahead[i]
        cur = a if a < cur else a + (cur - a) * coef
        g[i] = cur
    gs = np.interp(np.arange(len(buf)) / block, np.arange(nb), g).astype(np.float32)
    return np.clip(buf * gs[:, None], -1, 1)


def _write_wav(path: Path, buf: np.ndarray) -> None:
    import wave

    pcm = (np.clip(buf, -1, 1) * 32767).astype("<i2")
    with wave.open(str(path), "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(pcm.tobytes())
