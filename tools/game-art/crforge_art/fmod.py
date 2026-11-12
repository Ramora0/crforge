"""Just enough of FMOD Studio's bank format to know what each event plays.

A Studio bank (`soundbanks/*.bank`) is a RIFF file ("RIFF" ... "FEV ") whose LIST "PROJ" holds one
LIST per object kind, each object being a LIST of chunks; the samples themselves are an FSB5 file in
the "SND " chunk. Objects refer to each other by 16-byte GUID. Reverse-engineered from the game's
banks (FMOD 2.x, FMT version 0x8e); only the fields used here are decoded.

Arrays are serialized as u16 (count << 1 | 1), then (if count > 0) u16 element size and the
elements.

Objects (LIST type: chunk -> layout):
- EVNT: EVTB = event GUID, parent event GUID (zero for top-level events), timeline GUID, input bus
  GUID, master bus GUID, u32 max instances (0x7fffffff = unlimited), u32 flags, 5 bytes, array of
  parameter-sheet GUIDs, 12 bytes, f32 trigger cooldown (seconds), ...
- TMLN: TLNB = timeline GUID, then two arrays of {instrument GUID, u32 start, u32 length} (48 kHz
  samples).
- PMLO: PMLB = parameter sheet GUID, parameter GUID, array of instrument GUIDs.
- MUIT (multi instrument): MUIB = GUID; PLST = u32 play mode, u32, array of {instrument GUID,
  f32 weight in percent} (one is picked per play); INST (below).
- WAIT (single instrument): WAIB = GUID, waveform GUID; INST.
- EVIT (event instrument): EVIB = GUID, nested event GUID; INST.
- SLNI (silence), CMDI (command), PRIT (programmer): first 16 bytes the GUID; INST.
- INST (130 bytes, every instrument): owner GUID (timeline/sheet; zero inside a multi),
  f32 volume dB, f32 pitch semitones, i32 loop count (-1 forever), 8 bytes, f32 trigger
  probability %, f32 delay min ms, f32 delay max ms, ..., audio track bus GUID at 86.
- WAV: waveform GUID, u32, u16 FSB index, u16 subsound index.
- MODU: MODB = u16, modulator GUID, owner (instrument) GUID, u32 property (0 volume, 1 pitch),
  u32 kind (1 = random), u32, f32 amount (dB or semitones). Other kinds (envelopes, LFOs) are
  ignored.
- Volume automation curves (PRPS/MAP) are ignored; the static volumes are dB as stored (the
  fader map is the identity above -20 dB).
- GBUS/IBUS/MBUS/RBUS: *SB = GUID, u16, output bus GUID; BUS = u32, u32, [u16] arrays..., u16 8,
  f32 volume dB.

The strings bank (`clashroyale.strings.bank`, STDT chunk) maps GUIDs to paths
(`event:/Main/Card/Common/card_common_knight/card_common_knight_hit`, `bus:/SFX/...`) through a
radix tree: u32 version, array of 8-byte nodes {u24 string offset, u8 first char, u24 first child,
u8 child count}, array of GUIDs, u16 pool size + u16 + string pool, u16 count + u24 node of each
GUID, u16 count + u24 parent of each node. A path is its node's strings from the root down.
"""
from __future__ import annotations

import struct
from dataclasses import dataclass, field

ZERO = bytes(16)


def riff(data: bytes) -> list[tuple[tuple[str, ...], str, bytes]]:
    """Every leaf chunk as (the LIST types it is nested in, chunk id, body), depth-first."""
    out: list[tuple[tuple[str, ...], str, bytes]] = []

    def walk(off: int, end: int, path: tuple[str, ...]) -> None:
        while off + 8 <= end:
            cid = data[off:off + 4].decode("latin1")
            size = struct.unpack_from("<I", data, off + 4)[0]
            body = off + 8
            if cid in ("RIFF", "LIST"):
                walk(body + 4, min(end, body + size), path + (data[body:body + 4].decode("latin1"),))
            else:
                out.append((path, cid, data[body:body + size]))
            off = body + size + (size & 1)

    walk(0, len(data), ())
    return out


def objects(data: bytes, kind: str) -> list[dict[str, bytes]]:
    """Every LIST of the given type, as {chunk id: body} of its direct chunks (first of each id)."""
    res: list[dict[str, bytes]] = []

    def walk(off: int, end: int, cur: dict | None) -> None:
        while off + 8 <= end:
            cid = data[off:off + 4].decode("latin1")
            size = struct.unpack_from("<I", data, off + 4)[0]
            body = off + 8
            if cid in ("RIFF", "LIST"):
                typ = data[body:body + 4].decode("latin1")
                sub = None
                if typ == kind:
                    sub = {}
                    res.append(sub)
                walk(body + 4, min(end, body + size), sub)
            elif cur is not None:
                cur.setdefault(cid, data[body:body + size])
            off = body + size + (size & 1)

    walk(0, len(data), None)
    return res


def chunk(data: bytes, cid: str) -> bytes | None:
    for _p, c, body in riff(data):
        if c == cid:
            return body
    return None


def array(b: bytes, o: int) -> tuple[list[bytes], int]:
    v = struct.unpack_from("<H", b, o)[0]
    n = v >> 1
    if n == 0:
        return [], o + 2
    es = struct.unpack_from("<H", b, o + 2)[0]
    return [b[o + 4 + i * es:o + 4 + (i + 1) * es] for i in range(n)], o + 4 + n * es


def strings(stdt: bytes) -> dict[bytes, str]:
    """{GUID: path} of a strings bank's STDT chunk."""
    s = stdt
    nn = struct.unpack_from("<H", s, 4)[0] >> 1
    nodes = [struct.unpack_from("<I", s, 8 + 8 * i)[0] & 0xFFFFFF for i in range(nn)]
    o = 8 + 8 * nn
    ng = struct.unpack_from("<H", s, o)[0] >> 1
    guids = [s[o + 4 + 16 * i:o + 20 + 16 * i] for i in range(ng)]
    o += 4 + 16 * ng
    tail = 2 + 3 * ng + 2 + 3 * nn  # the two u24 tables end the chunk
    pool_end = len(s) - tail
    pool = s[o + 4:pool_end]
    o = pool_end
    if struct.unpack_from("<H", s, o)[0] != ng & 0xFFFF:
        raise ValueError("unexpected strings bank layout")

    def u24s(at, n):
        return [int.from_bytes(s[at + 3 * i:at + 3 * i + 3], "little") for i in range(n)]

    gnode = u24s(o + 2, ng)
    o += 2 + 3 * ng
    parent = u24s(o + 2, nn)

    def text(off):
        if off == 0xFFFFFF:
            return ""
        return pool[off:pool.index(b"\0", off)].decode("utf-8", "replace")

    out = {}
    for g, n in zip(guids, gnode):
        parts = []
        seen = 0
        while 0 < n < nn and seen < 64:
            parts.append(text(nodes[n]))
            n = parent[n]
            seen += 1
        out[g] = "".join(reversed(parts))
    return out


@dataclass
class Inst:
    kind: str  # wave | multi | event | silence | command | programmer
    owner: bytes = ZERO
    volume: float = 0.0
    pitch: float = 0.0
    probability: float = 100.0
    delay: tuple[float, float] = (0.0, 0.0)  # ms
    bus: bytes = ZERO
    wav: bytes = ZERO  # wave
    playlist: list[tuple[bytes, float]] = field(default_factory=list)  # multi
    event: bytes = ZERO  # event
    rand_volume: float = 0.0  # dB, from random modulators
    rand_pitch: float = 0.0  # semitones
    loop: bool = False  # loop count -1


@dataclass
class Event:
    guid: bytes
    parent: bytes
    timeline: bytes
    ibus: bytes
    mbus: bytes
    max_instances: int
    flags: int
    sheets: list[bytes]
    cooldown: float


@dataclass
class Bank:
    events: dict[bytes, Event] = field(default_factory=dict)
    timelines: dict[bytes, list[tuple[bytes, int, int]]] = field(default_factory=dict)
    sheets: dict[bytes, list[bytes]] = field(default_factory=dict)
    insts: dict[bytes, Inst] = field(default_factory=dict)
    wavs: dict[bytes, int] = field(default_factory=dict)  # waveform GUID -> FSB subsound index
    buses: dict[bytes, tuple[float, bytes]] = field(default_factory=dict)  # GUID -> (dB, output)


def _inst(b: bytes, kind: str) -> Inst:
    vol, pitch = struct.unpack_from("<ff", b, 16)
    loops = struct.unpack_from("<i", b, 24)[0]
    prob, dmin, dmax = struct.unpack_from("<fff", b, 36)
    return Inst(kind, owner=b[:16], volume=vol, pitch=pitch, probability=prob, delay=(dmin, dmax),
                bus=b[86:102] if len(b) >= 102 else ZERO, loop=loops == -1)


def _bus_volume(b: bytes) -> float:
    o = 8
    for _ in range(8):
        v = struct.unpack_from("<H", b, o)[0]
        if not v & 1:
            return struct.unpack_from("<f", b, o + 2)[0]
        _, o = array(b, o)
    return 0.0


INST_KINDS = {"WAIT": ("WAIB", "wave"), "MUIT": ("MUIB", "multi"), "EVIT": ("EVIB", "event"),
              "SLNI": ("SLNB", "silence"), "CMDI": ("CMDB", "command"), "PRIT": ("PRIB", "programmer")}


def parse(data: bytes) -> Bank:
    bank = Bank()
    for o in objects(data, "EVNT"):
        b = o.get("EVTB")
        if not b or len(b) < 96:
            continue
        sheets, p = array(b, 93)
        cooldown = struct.unpack_from("<f", b, p + 12)[0] if p + 16 <= len(b) else 0.0
        bank.events[b[:16]] = Event(b[:16], b[16:32], b[32:48], b[48:64], b[64:80],
                                    struct.unpack_from("<I", b, 80)[0],
                                    struct.unpack_from("<I", b, 84)[0],
                                    [g[:16] for g in sheets], cooldown if 0 <= cooldown < 60 else 0.0)
    for o in objects(data, "TMLN"):
        b = o.get("TLNB")
        if not b:
            continue
        items = []
        p = 16
        for _ in range(2):
            arr, p = array(b, p)
            for e in arr:
                start, length = struct.unpack_from("<II", e, 16)
                items.append((e[:16], start, length))
        bank.timelines[b[:16]] = items
    for o in objects(data, "PMLO"):
        b = o.get("PMLB")
        if b:
            arr, _ = array(b, 32)
            bank.sheets[b[:16]] = [g[:16] for g in arr]
    for kind, (head, name) in INST_KINDS.items():
        for o in objects(data, kind):
            h, ib = o.get(head), o.get("INST")
            if not h or not ib:
                continue
            inst = _inst(ib, name)
            if name == "wave":
                inst.wav = h[16:32]
            elif name == "event":
                inst.event = h[16:32]
            elif name == "multi" and "PLST" in o:
                arr, _ = array(o["PLST"], 8)
                inst.playlist = [(e[:16], struct.unpack_from("<f", e, 16)[0]) for e in arr]
            bank.insts[h[:16]] = inst
    for _p, cid, b in riff(data):
        if cid == "WAV " and len(b) >= 24:
            bank.wavs[b[:16]] = struct.unpack_from("<H", b, 22)[0]
    for o in objects(data, "MODU"):
        b = o.get("MODB")
        if not b or len(b) != 50:
            continue
        owner = b[18:34]
        prop, kind = struct.unpack_from("<II", b, 34)
        amount = struct.unpack_from("<f", b, 46)[0]
        inst = bank.insts.get(owner)
        if inst is None or kind != 1:
            continue
        if prop == 0:
            inst.rand_volume += amount
        elif prop == 1:
            inst.rand_pitch += amount
    for kind, head in (("GBUS", "GBSB"), ("IBUS", "IBSB"), ("MBUS", "MBSB"), ("RBUS", "RBSB")):
        for o in objects(data, kind):
            h, b = o.get(head), o.get("BUS ")
            if not h or not b:
                continue
            vol = _bus_volume(b)
            bank.buses[h[:16]] = (vol if -100 <= vol <= 30 else 0.0, h[18:34])
    return bank


def bus_db(guid: bytes, buses: dict[bytes, tuple[float, bytes]]) -> float:
    """The summed volume of a bus and every bus it outputs to."""
    db = 0.0
    for _ in range(32):
        if guid == ZERO or guid not in buses:
            break
        v, guid = buses[guid]
        db += v
    return db


def resolve(bank: Bank, sample_names: list[str], buses: dict[bytes, tuple[float, bytes]],
            ev: Event, depth: int = 0) -> dict:
    """What one event plays: {"layers": [{"start", "probability", "choices": [...]}], ...}.

    Each choice is {"sample" (None = silence), "weight", "volume_db", "pitch", "rand_volume_db",
    "rand_pitch", "delay": [min, max] seconds, "loop"}; one choice per layer is played per trigger.
    """
    entries = list(bank.timelines.get(ev.timeline, []))
    for sh in ev.sheets:
        for g in bank.sheets.get(sh, [])[:1]:  # a parameter sheet: its first instrument
            entries.append((g, 0, 0))
    layers = []
    for g, start, length in entries:
        inst = bank.insts.get(g)
        if inst is None:
            continue
        choices = _expand(bank, sample_names, buses, inst, 1.0, depth)
        if not choices:
            continue
        layers.append({"start": round(start / 48000, 4), "length": round(length / 48000, 4),
                       "probability": round(inst.probability / 100, 4), "choices": choices})
    master = bus_db(ev.mbus, buses) + bus_db(ev.ibus, buses)
    return {"volume_db": round(master, 3), "max_instances": ev.max_instances,
            "steal": ev.flags, "cooldown": round(ev.cooldown, 4), "layers": layers}


def _expand(bank, names, buses, inst: Inst, weight: float, depth: int) -> list[dict]:
    own = {"volume_db": inst.volume, "pitch": inst.pitch, "rand_volume_db": inst.rand_volume,
           "rand_pitch": inst.rand_pitch, "delay": [inst.delay[0] / 1000, inst.delay[1] / 1000],
           "loop": inst.loop}
    if inst.bus != ZERO and inst.bus in buses:
        # an instrument on an audio track: the track's volume (the track outputs to the event's
        # master bus, which resolve() adds)
        own["volume_db"] += buses[inst.bus][0]
    out = []
    if inst.kind == "wave":
        i = bank.wavs.get(inst.wav)
        if i is None or i >= len(names):
            return []
        out.append({"sample": names[i], "weight": weight, **own})
    elif inst.kind == "silence":
        out.append({"sample": None, "weight": weight, **own})
    elif inst.kind == "multi":
        total = sum(w for _, w in inst.playlist) or 1.0
        for g, w in inst.playlist:
            sub = bank.insts.get(g)
            if sub is None:
                continue
            for c in _expand(bank, names, buses, sub, weight * w / total, depth):
                out.append(_combine(own, c))
    elif inst.kind == "event" and depth < 3:
        nested = bank.events.get(inst.event)
        if nested is not None:
            r = resolve(bank, names, buses, nested, depth + 1)
            if r["layers"]:
                for c in r["layers"][0]["choices"]:
                    c = dict(c, weight=c["weight"] * weight)
                    c["volume_db"] += r["volume_db"]
                    out.append(_combine(own, c))
    for c in out:
        for k in ("volume_db", "pitch", "rand_volume_db", "rand_pitch", "weight"):
            c[k] = round(c[k], 4)
        c["delay"] = [round(x, 4) for x in c["delay"]]
    return out


def _combine(outer: dict, inner: dict) -> dict:
    c = dict(inner)
    for k in ("volume_db", "pitch", "rand_volume_db", "rand_pitch"):
        c[k] = inner[k] + outer[k]
    c["delay"] = [inner["delay"][0] + outer["delay"][0], inner["delay"][1] + outer["delay"][1]]
    c["loop"] = inner["loop"] or outer["loop"]
    return c


def bank_events(data: bytes, sample_names: list[str], paths: dict[bytes, str],
                mixer: dict[bytes, tuple[float, bytes]]) -> dict[str, dict]:
    """{event name (last path component): resolve() + "path"} of every top-level event of a bank.

    `paths` comes from the strings bank, `mixer` is the master bank's buses (the mixer groups the
    events route into)."""
    bank = parse(data)
    buses = dict(mixer)
    buses.update(bank.buses)
    out = {}
    for g, ev in bank.events.items():
        if ev.parent != ZERO:
            continue  # a nested event, played by an event instrument
        path = paths.get(g, "")
        name = path.rsplit("/", 1)[-1] if path else g.hex()
        r = resolve(bank, sample_names, buses, ev)
        r["path"] = path
        out[name] = r
    return out
