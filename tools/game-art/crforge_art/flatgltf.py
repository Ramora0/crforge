"""Supercell "flat glTF": a .glb whose JSON chunk is a FlatBuffer (chunk type ``FLA2``).

GLB container (standard glTF 2.0 binary)::

    "glTF" | u32 version=2 | u32 total length | chunks: (u32 length | 4-byte type | data)*

Supercell's files hold a ``FLA2`` chunk where the ``JSON`` chunk should be, followed by the usual
``BIN\\0`` chunk. ``FLA2`` is a FlatBuffer whose tables are the glTF 2.0 JSON schema, one table per
glTF object (schema: ``schemas/sc3d/gltf_flat.fbs``). This module turns it back into the glTF JSON
dict, with the same property names. ``extensions``/``extras`` are FlexBuffers holding the JSON
objects. The Supercell extension ``SC_odin_format`` is left as is (see ``odin.py``).

The schema file is parsed at runtime by a small .fbs reader (tables, enums, scalars, strings,
vectors, nested tables, flexbuffer blobs), so the .fbs is the single source of truth.
"""
from __future__ import annotations

import json
import re
import struct
from dataclasses import dataclass
from pathlib import Path

from flatbuffers import flexbuffers

SCHEMA = Path(__file__).parent / "schemas" / "sc3d" / "gltf_flat.fbs"

GLB_MAGIC = b"glTF"
CHUNK_JSON = b"JSON"
CHUNK_BIN = b"BIN\x00"
CHUNK_FLAT = b"FLA2"

_SCALARS = {  # fbs type -> (struct code, size)
    "bool": ("?", 1), "byte": ("b", 1), "ubyte": ("B", 1), "short": ("h", 2), "ushort": ("H", 2),
    "int": ("i", 4), "uint": ("I", 4), "long": ("q", 8), "ulong": ("Q", 8), "float": ("f", 4),
    "double": ("d", 8),
}
# enums that glTF writes as strings (stored as the index of the name)
_STRING_ENUMS = {"AccessorType", "AnimationChannelTargetPath", "AnimationSamplerInterpolation",
                 "CameraType", "MaterialAlphaMode"}


# --------------------------------------------------------------------------- .fbs schema
@dataclass
class Field:
    name: str
    type: str          # scalar name, "string", enum name, table name
    vector: bool
    flex: bool
    default: object


@dataclass
class Schema:
    tables: dict[str, list[Field]]
    enums: dict[str, tuple[str, dict[int, str]]]   # name -> (underlying scalar, value -> name)
    root: str


def parse_fbs(text: str) -> Schema:
    text = re.sub(r"//[^\n]*", "", text)
    enums: dict[str, tuple[str, dict[int, str]]] = {}
    for name, base, body in re.findall(r"enum\s+(\w+)\s*:\s*(\w+)\s*\{([^}]*)\}", text):
        values, nxt = {}, 0
        for item in filter(None, (s.strip() for s in body.split(","))):
            if "=" in item:
                k, v = (s.strip() for s in item.split("="))
                nxt = int(v, 0)
            else:
                k = item
            values[nxt] = k
            nxt += 1
        enums[name] = (base, values)
    tables: dict[str, list[Field]] = {}
    for name, body in re.findall(r"table\s+(\w+)\s*\{([^}]*)\}", text):
        fields = []
        for decl in filter(None, (s.strip() for s in body.split(";"))):
            m = re.match(r"(\w+)\s*:\s*(\[?)\s*(\w+)\s*\]?\s*(?:=\s*([^(]+?))?\s*(\(.*\))?$", decl, re.S)
            if not m:
                raise ValueError(f"cannot parse field {decl!r} in table {name}")
            fname, vec, ftype, default, attrs = m.groups()
            fields.append(Field(fname, ftype, bool(vec), bool(attrs and "flexbuffer" in attrs),
                                default.strip() if default else None))
        tables[name] = fields
    root = re.search(r"root_type\s+(\w+)", text).group(1)
    return Schema(tables, enums, root)


_SCHEMA: Schema | None = None


def schema() -> Schema:
    global _SCHEMA
    if _SCHEMA is None:
        _SCHEMA = parse_fbs(SCHEMA.read_text())
    return _SCHEMA


# --------------------------------------------------------------------------- reader
def _flex_to_py(ref):
    """FlexBuffer reference -> plain Python (dict/list/str/int/float/bool/None)."""
    if ref.IsNull:
        return None
    if ref.IsBool:
        return ref.AsBool
    if ref.IsInt:
        return ref.AsInt
    if ref.IsFloat:
        return ref.AsFloat
    if ref.IsString or ref.IsKey:
        return ref.AsString
    if ref.IsMap:
        m = ref.AsMap
        keys, vals = m.Keys, m.Values
        return {keys[i].AsKey: _flex_to_py(vals[i]) for i in range(len(m))}
    if ref.IsVector or ref.IsTypedVector or ref.IsFixedTypedVector:
        v = ref.AsVector if ref.IsVector else (ref.AsTypedVector if ref.IsTypedVector else ref.AsFixedTypedVector)
        return [_flex_to_py(v[i]) for i in range(len(v))]
    if ref.IsBlob:
        return list(ref.AsBlob)
    return ref.Value


def read_flex(blob: bytes):
    if not blob:
        return None
    return _flex_to_py(flexbuffers.GetRoot(bytearray(blob)))


class _Reader:
    def __init__(self, buf: bytes, sch: Schema):
        self.b = buf
        self.s = sch

    def u32(self, pos: int) -> int:
        return struct.unpack_from("<I", self.b, pos)[0]

    def table(self, pos: int, tname: str) -> dict:
        vt = pos - struct.unpack_from("<i", self.b, pos)[0]
        vt_len = struct.unpack_from("<H", self.b, vt)[0]
        out: dict = {}
        for i, f in enumerate(self.s.tables[tname]):
            slot = 4 + 2 * i
            if slot >= vt_len:
                break
            off = struct.unpack_from("<H", self.b, vt + slot)[0]
            if off == 0:
                continue
            out[f.name] = self.value(pos + off, f)
        return out

    def _scalar(self, pos: int, t: str):
        code, _ = _SCALARS[t]
        return struct.unpack_from("<" + code, self.b, pos)[0]

    def _deref(self, pos: int) -> int:
        return pos + self.u32(pos)

    def _string(self, pos: int) -> str:
        n = self.u32(pos)
        return self.b[pos + 4:pos + 4 + n].decode("utf-8", "replace")

    def value(self, pos: int, f: Field):
        t = f.type
        if f.vector:
            vpos = self._deref(pos)
            n = self.u32(vpos)
            data = vpos + 4
            if f.flex:
                return read_flex(self.b[data:data + n])
            if t == "string":
                return [self._string(self._deref(data + 4 * i)) for i in range(n)]
            if t in self.s.tables:
                return [self.table(self._deref(data + 4 * i), t) for i in range(n)]
            base = self.s.enums[t][0] if t in self.s.enums else t
            code, size = _SCALARS[base]
            return list(struct.unpack_from(f"<{n}{code}", self.b, data))
        if t == "string":
            return self._string(self._deref(pos))
        if t in self.s.tables:
            return self.table(self._deref(pos), t)
        if t in self.s.enums:
            base, names = self.s.enums[t]
            v = self._scalar(pos, base)
            return names.get(v, v) if t in _STRING_ENUMS else v
        return self._scalar(pos, t)


def flat_to_dict(buf: bytes) -> dict:
    """Decodes a FLA2 chunk into a glTF-shaped dict (only the fields present in the buffer)."""
    sch = schema()
    r = _Reader(bytes(buf), sch)
    return r.table(r.u32(0), sch.root)


# --------------------------------------------------------------------------- clean-up to glTF JSON
_ID_FIELDS = {"bufferView", "buffer", "node", "mesh", "skin", "camera", "material", "indices", "source",
              "sampler", "index", "inverseBindMatrices", "skeleton", "scene"}


def _strip(obj, parent_key: str = ""):
    """Drops absent glTF ids (-1), empty extensions/extras, and NONE sampler filters."""
    if isinstance(obj, dict):
        out = {}
        for k, v in obj.items():
            if k in _ID_FIELDS and isinstance(v, int) and v < 0:
                continue
            if k in ("extensions", "extras") and not v:
                continue
            if k in ("magFilter", "minFilter") and v == 0:
                continue
            if k == "target" and parent_key == "bufferViews" and v == 0:
                continue
            out[k] = _strip(v, k)
        return out
    if isinstance(obj, list):
        return [_strip(v, parent_key) for v in obj]
    return obj


def to_gltf_json(flat: dict) -> dict:
    """The decoded flat dict -> glTF JSON (required properties filled with their schema defaults)."""
    g = _strip(flat)
    g.setdefault("asset", {}).setdefault("version", "2.0")
    for a in g.get("accessors", []):
        a.setdefault("componentType", 5120)
        a.setdefault("count", 0)
        a.setdefault("type", "SCALAR")
    for bv in g.get("bufferViews", []):
        bv.setdefault("buffer", 0)
        bv.setdefault("byteLength", 0)
        if not bv.get("byteStride"):
            bv.pop("byteStride", None)
    for an in g.get("animations", []):
        for c in an.get("channels", []):
            c.setdefault("sampler", 0)
        for s in an.get("samplers", []):
            s.setdefault("input", 0)
            s.setdefault("output", 0)
    return g


# --------------------------------------------------------------------------- GLB container
def read_glb(data: bytes) -> list[tuple[bytes, bytes]]:
    """[(chunk type, chunk data)] of a GLB file."""
    if data[:4] != GLB_MAGIC:
        raise ValueError("not a glTF binary (bad magic)")
    version, length = struct.unpack_from("<II", data, 4)
    if version != 2:
        raise ValueError(f"unsupported glTF binary version {version}")
    if length > len(data):
        raise ValueError(f"truncated GLB: header says {length} bytes, file has {len(data)}")
    chunks, pos = [], 12
    while pos + 8 <= length:
        n, kind = struct.unpack_from("<I4s", data, pos)
        chunks.append((kind, data[pos + 8:pos + 8 + n]))
        pos += 8 + n
    return chunks


def write_glb(gltf: dict, bin_data: bytes) -> bytes:
    js = json.dumps(gltf, separators=(",", ":"), allow_nan=False).encode()
    js += b" " * (-len(js) % 4)
    bin_data = bytes(bin_data) + b"\x00" * (-len(bin_data) % 4)
    parts = [struct.pack("<I4s", len(js), CHUNK_JSON), js]
    if bin_data:
        parts += [struct.pack("<I4s", len(bin_data), CHUNK_BIN), bin_data]
    body = b"".join(parts)
    return GLB_MAGIC + struct.pack("<II", 2, 12 + len(body)) + body


def load(data: bytes) -> tuple[dict, bytes]:
    """(glTF JSON dict, BIN chunk) of a flat or ordinary GLB."""
    chunks = dict(read_glb(data))
    if CHUNK_FLAT in chunks:
        g = to_gltf_json(flat_to_dict(chunks[CHUNK_FLAT]))
    elif CHUNK_JSON in chunks:
        g = json.loads(chunks[CHUNK_JSON])
    else:
        raise ValueError("GLB has neither a FLA2 nor a JSON chunk")
    return g, chunks.get(CHUNK_BIN, b"")
