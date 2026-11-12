"""Hand-made sc3d files for the tests (no game data): a FlatBuffer writer driven by the same
.fbs schema the reader uses, and builders for FLA2 .glb, KTX2, .rmat and a minimal assets folder."""
from __future__ import annotations

import json
import sqlite3
import struct
from pathlib import Path

import flatbuffers
import numpy as np
from flatbuffers import flexbuffers

from crforge_art import flatgltf

_PREPEND = {"bool": "Bool", "byte": "Int8", "ubyte": "Uint8", "short": "Int16", "ushort": "Uint16",
            "int": "Int32", "uint": "Uint32", "long": "Int64", "ulong": "Uint64", "float": "Float32",
            "double": "Float64"}


def _encode_table(b: flatbuffers.Builder, sch, tname: str, obj: dict) -> int:
    fields = sch.tables[tname]
    refs = {}
    for f in fields:  # children first
        if f.name not in obj:
            continue
        v = obj[f.name]
        if f.flex:
            refs[f.name] = b.CreateByteVector(bytes(flexbuffers.Dumps(v)))
        elif f.vector:
            if f.type == "string":
                offs = [b.CreateString(s) for s in v]
                b.StartVector(4, len(offs), 4)
                for o in reversed(offs):
                    b.PrependUOffsetTRelative(o)
                refs[f.name] = b.EndVector()
            elif f.type in sch.tables:
                offs = [_encode_table(b, sch, f.type, x) for x in v]
                b.StartVector(4, len(offs), 4)
                for o in reversed(offs):
                    b.PrependUOffsetTRelative(o)
                refs[f.name] = b.EndVector()
            else:
                base = sch.enums[f.type][0] if f.type in sch.enums else f.type
                size = struct.calcsize("<" + flatgltf._SCALARS[base][0])
                b.StartVector(size, len(v), size)
                for x in reversed(v):
                    getattr(b, "Prepend" + _PREPEND[base])(x)
                refs[f.name] = b.EndVector()
        elif f.type == "string":
            refs[f.name] = b.CreateString(v)
        elif f.type in sch.tables:
            refs[f.name] = _encode_table(b, sch, f.type, v)
    b.StartObject(len(fields))
    for i, f in enumerate(fields):
        if f.name not in obj:
            continue
        if f.name in refs:
            b.PrependUOffsetTRelativeSlot(i, refs[f.name], 0)
            continue
        v = obj[f.name]
        if f.type in sch.enums:
            base, names = sch.enums[f.type]
            if isinstance(v, str):
                v = {n: k for k, n in names.items()}[v]
        else:
            base = f.type
        getattr(b, "Prepend" + _PREPEND[base])(v)  # always written (no default elision)
        b.Slot(i)
    return b.EndObject()


def encode_flat(gltf: dict) -> bytes:
    sch = flatgltf.schema()
    b = flatbuffers.Builder(1024)
    b.Finish(_encode_table(b, sch, sch.root, gltf))
    return bytes(b.Output())


def flat_glb(gltf: dict, bin_data: bytes) -> bytes:
    """A Supercell-style .glb: FLA2 chunk + BIN chunk."""
    fla = encode_flat(gltf)
    fla += b"\x00" * (-len(fla) % 4)
    bin_data = bytes(bin_data) + b"\x00" * (-len(bin_data) % 4)
    body = struct.pack("<I4s", len(fla), b"FLA2") + fla + struct.pack("<I4s", len(bin_data), b"BIN\x00") + bin_data
    return b"glTF" + struct.pack("<II", 2, 12 + len(body)) + body


class Bin:
    def __init__(self):
        self.data = b""
        self.views = []

    def add(self, raw: bytes) -> int:
        self.data += b"\x00" * (-len(self.data) % 16)
        self.views.append({"buffer": 0, "byteOffset": len(self.data), "byteLength": len(raw)})
        self.data += raw
        return len(self.views) - 1


def pack_weights(w1: float, w2: float, w3: float) -> int:
    return (round(w1 * 4095) << 21) | (round(w2 * 4095) << 10) | round(w3 * 4095)


def model_glb() -> bytes:
    """A skinned quad: 4 vertices, 2 triangles, joints [hips (node 2), arm (node 3)]."""
    pos = np.array([[0, 0, 0], [1, 0, 0], [1, 2, 0], [0, 2, 0]], np.float32)
    joints = np.array([[0, 0, 0, 0], [0, 1, 0, 0], [1, 1, 0, 0], [1, 0, 0, 0]], np.uint8)
    weights = np.array([pack_weights(0, 0, 0), pack_weights(0.25, 0, 0),
                        pack_weights(0.4, 0, 0),           # joint 1 listed twice: 0.6 + 0.4
                        pack_weights(0, 0, 0)], np.uint32)
    s0 = b"".join(pos[i].tobytes() + joints[i].tobytes() + weights[i:i + 1].tobytes() for i in range(4))
    nrm = np.array([[0, 0, 127, 0]] * 4, np.int8)
    tan = np.array([[127, 0, 0, -127]] * 4, np.int8)
    uv = np.array([[0, 0], [65535, 0], [65535, 65535], [0, 65535]], np.uint16)
    s1 = b"".join(nrm[i].tobytes() + tan[i].tobytes() + uv[i].tobytes() for i in range(4))
    idx = np.array([0, 1, 2, 0, 2, 3], np.uint16).tobytes()
    ibm = np.tile(np.eye(4, dtype=np.float32).T.reshape(-1), 2).tobytes()
    bn = Bin()
    v_idx = bn.add(idx)
    odin_off = len(bn.data) + (-len(bn.data) % 16)
    v_odin = bn.add(s0 + b"\x00" * 4 + s1)   # streams at +0 and +84
    v_ibm = bn.add(ibm)
    odin_ext = {
        "bufferView": v_odin,
        "materials": [{"name": "main", "shader": "uber_pbr", "blendMode": 4, "constants": ["AMBIENT"],
                       "variables": {"booleans": {}, "floatVectors": {"teamColor": [1.0, 1.0, 1.0, 1.0]},
                                     "floats": {"metalness": 0.5, "roughness": 0.5},
                                     "textures": {"diffuseTex": "sc3d/test/material/test_diff.ktx#repeat",
                                                  "mraTex": "sc3d/test/material/test_mra.ktx#repeat",
                                                  "normalTex": "sc3d/test/material/test_n.ktx#repeat",
                                                  "teamColorTex": "sc3d/test/material/test_spec.ktx#repeat"}}}],
        "meshDataInfos": [{"vertexDescriptors": [
            {"offset": 0, "stride": 20, "attributes": [
                {"name": "a_pos", "index": 0, "format": 30, "offset": 0},
                {"name": "a_boneindex", "index": 5, "format": 3, "offset": 12},
                {"name": "a_boneweights", "index": 6, "format": 36, "offset": 16}]},
            {"offset": 84, "stride": 12, "attributes": [
                {"name": "a_normal", "index": 1, "format": 12, "offset": 0},
                {"name": "a_tangent", "index": 7, "format": 12, "offset": 4},
                {"name": "a_uv0", "index": 2, "format": 22, "offset": 8}]}]}],
    }
    assert odin_off == bn.views[v_odin]["byteOffset"]
    g = {
        "asset": {"version": "2.0"},
        "accessors": [{"bufferView": v_idx, "componentType": 5123, "count": 6, "type": "SCALAR"},
                      {"bufferView": v_ibm, "componentType": 0x10000 | 5126, "count": 2, "type": "MAT4"}],
        "bufferViews": bn.views, "buffers": [{"byteLength": len(bn.data)}],
        "extensions": {"SC_odin_format": odin_ext},
        "extensionsUsed": ["SC_odin_format"], "extensionsRequired": ["SC_odin_format"],
        "meshes": [{"primitives": [{"indices": 0, "material": 0,
                                    "extensions": {"SC_odin_format": {"meshDataInfoIndex": 0}}}],
                    "extensions": {"SC_odin_format": {"bounds": [[0, 0, 0], [1, 2, 0]]}}}],
        "nodes": [{"name": "CHARACTER", "rotation": [-0.70710677, 0.0, 0.0, -0.70710677],
                   "extensions": {"SC_odin_format": None}},
                  {"name": "test_lod1", "mesh": 0, "skin": 0, "translation": [5.0, 0.0, 0.0],
                   "extensions": {"SC_odin_format": {"parent": 0}}},
                  {"name": "hips", "extensions": {"SC_odin_format": {"parent": 0}}},
                  {"name": "arm", "translation": [0.0, 1.0, 0.0], "extensions": {"SC_odin_format": {"parent": 2}}}],
        "skins": [{"inverseBindMatrices": 1, "joints": [2, 3]}],
    }
    return flat_glb(g, bn.data)


def clip_glb(frames: int = 5) -> bytes:
    """An animation file for the model above. Its node order differs from the model's (arm first).

    arm: rotation keyed at frames 0 and 1 (identity, then 90 degrees about z), then held;
    hips: translation keyed every frame, x = 0.5 * frame.
    """
    q0 = [0, 0, 0, 32767]
    q1 = [0, 0, 23170, 23170]
    arm = [2] + q0 + q1 + [-(frames - 2)]
    hips = [frames] + [v for i in range(frames) for v in (i, 0, 0)]
    data = np.array(arm + hips, np.int16)
    # nodeAccessor, 8 floats per node: base translation xyz, base scale xyz, t multiplier, s multiplier
    base = np.array([0, 1, 0, 1, 1, 1, 0, 0,
                     0, 0, 0, 1, 1, 1, 0.5, 0], np.float32)
    rot = np.array([0, 0, 0, 32767, 0, 0, 0, 32767], np.int16)   # base rotations (identity)
    bn = Bin()
    bn.add(b"")
    v_data, v_base, v_rot = bn.add(data.tobytes()), bn.add(base.tobytes()), bn.add(rot.tobytes())
    g = {
        "asset": {"version": "2.0"},
        "accessors": [{"bufferView": v_data, "componentType": 0x10000 | 5122, "count": data.size, "type": "SCALAR"},
                      {"bufferView": v_base, "componentType": 0x20000 | 5126, "count": base.size, "type": "SCALAR"},
                      {"bufferView": v_rot, "componentType": 0x30000 | 5122, "count": rot.size, "type": "SCALAR"}],
        "bufferViews": bn.views, "buffers": [{"byteLength": len(bn.data)}],
        "extensions": {"SC_odin_format": {"bufferView": 0, "animation": {
            "frameRate": 30.0, "keyframeCount": frames, "firstFrame": 0.0, "lastFrame": float(frames - 1),
            "packed": {"dataAccessor": 0, "nodeAccessor": 1, "uintAccessor": 2, "stride": 7,
                       "nodes": [{"nodeIndex": 1, "flags": 2, "frameCount": frames, "dataSize": len(arm)},
                                 {"nodeIndex": 2, "flags": 4, "frameCount": frames, "dataSize": len(hips)}]}}}},
        "extensionsUsed": ["SC_odin_format"], "extensionsRequired": ["SC_odin_format"],
        "nodes": [{"name": "CHARACTER", "extensions": {"SC_odin_format": None}},
                  {"name": "arm", "extensions": {"SC_odin_format": {"parent": 2}}},
                  {"name": "hips", "extensions": {"SC_odin_format": {"parent": 0}}}],
    }
    return flat_glb(g, bn.data)


def ktx2(width: int, height: int, vk: int, payload: bytes, orientation: str = "rd") -> bytes:
    kv_entry = b"KTXorientation\x00" + orientation.encode() + b"\x00"
    kvd = struct.pack("<I", len(kv_entry)) + kv_entry
    kvd += b"\x00" * (-len(kvd) % 4)
    header_len = 80 + 24
    kvd_off = header_len
    data_off = kvd_off + len(kvd)
    data_off += -data_off % 16
    out = bytearray(b"\xabKTX 20\xbb\r\n\x1a\n")
    out += struct.pack("<9I", vk, 1, width, height, 0, 0, 1, 1, 0)
    out += struct.pack("<4I", 0, 0, kvd_off, len(kvd))
    out += struct.pack("<2Q", 0, 0)
    out += struct.pack("<3Q", data_off, len(payload), len(payload))
    out += kvd
    out += b"\x00" * (data_off - len(out))
    out += payload
    return bytes(out)


def astc_const(width: int, height: int, rgba16, block=(8, 8), srgb=True) -> bytes:
    """KTX2 ASTC texture of one colour (void-extent blocks), rgba16 in 0..65535."""
    blk = bytes([0xFC, 0xFD, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF]) + struct.pack("<4H", *rgba16)
    n = ((width + block[0] - 1) // block[0]) * ((height + block[1] - 1) // block[1])
    sizes = [(4, 4), (5, 4), (5, 5), (6, 5), (6, 6), (8, 5), (8, 6), (8, 8)]
    vk = 157 + 2 * sizes.index(block) + (1 if srgb else 0)
    return ktx2(width, height, vk, blk * n)


def rmat_bytes(base: str, params: list[tuple[str, int, object]]) -> bytes:
    """Material{version (slot 0), base (slot 1), params (slot 2)}; Param{name, type, value table}."""
    b = flatbuffers.Builder(256)
    pofs = []
    for name, kind, val in params:
        n = b.CreateString(name)
        sv = b.CreateString(val) if kind == 6 else None
        if kind in (1, 2, 3, 4):
            vals = [val] if kind == 1 else list(val)
            b.StartObject(1)
            if kind == 1:
                b.PrependFloat32Slot(0, vals[0], 0.0)
            else:
                b.Prep(4, 4 * len(vals))
                for x in reversed(vals):
                    b.PrependFloat32(x)
                b.Slot(0)
            vt = b.EndObject()
        elif kind == 6:
            b.StartObject(1)
            b.PrependUOffsetTRelativeSlot(0, sv, 0)
            vt = b.EndObject()
        elif kind == 7:
            b.StartObject(1)
            b.PrependBoolSlot(0, bool(val), False)
            vt = b.EndObject()
        b.StartObject(3)
        b.PrependUOffsetTRelativeSlot(0, n, 0)
        b.PrependUint8Slot(1, kind, 0)
        b.PrependUOffsetTRelativeSlot(2, vt, 0)
        pofs.append(b.EndObject())
    bs = b.CreateString(base)
    b.StartVector(4, len(pofs), 4)
    for o in reversed(pofs):
        b.PrependUOffsetTRelative(o)
    vec = b.EndVector()
    b.StartObject(3)
    b.PrependInt32Slot(0, 1, 0)
    b.PrependUOffsetTRelativeSlot(1, bs, 0)
    b.PrependUOffsetTRelativeSlot(2, vec, 0)
    b.Finish(b.EndObject())
    return bytes(b.Output())


def assets_folder(root: Path) -> Path:
    """A minimal extracted assets/ folder: fingerprint, a toml row, assets.scdb, one model, one clip,
    four textures."""
    a = root / "assets"
    (a / "csv_logic" / "characters").mkdir(parents=True)
    (a / "fingerprint.json").write_text(json.dumps({"sha": "f" * 40, "version": "1.2.3"}))
    (a / "csv_logic" / "characters" / "test.toml").write_text(
        '[CHARACTER.TestUnit]\nHitpoints = 10\nPrefabAsset = "sc3d/test/chr_prefab_test_battle"\n'
        '[CHARACTER.Other]\nFileName = "sc/chr_other.sc"\n')
    mdir = a / "sc3d" / "test"
    (mdir / "model").mkdir(parents=True)
    (mdir / "animation").mkdir()
    (mdir / "material").mkdir()
    (mdir / "model" / "test_lod1_rig.glb").write_bytes(model_glb())
    (mdir / "animation" / "test_walk.glb").write_bytes(clip_glb())
    (mdir / "animation" / "other_rig_walk.glb").write_bytes(clip_glb())  # wrong prefix: not picked
    (mdir / "material" / "test_diff.ktx").write_bytes(astc_const(16, 16, (65535, 32768, 0, 65535)))
    (mdir / "material" / "test_mra.ktx").write_bytes(astc_const(16, 16, (65535, 32768, 0, 65535), srgb=False))
    (mdir / "material" / "test_n.ktx").write_bytes(astc_const(16, 16, (32896, 32896, 32896, 32896), srgb=False))
    (mdir / "material" / "test_spec.ktx").write_bytes(astc_const(16, 16, (65535, 0, 0, 65535), srgb=False))
    (mdir / "material" / "mat_test_override.rmat").write_bytes(
        rmat_bytes("sc3d/shared/materials/base_pbr.rmat", [("enableRimLight", 7, True), ("rl_pow", 1, 1.5)]))
    prefab = {"data": {"objectType": 5000, "name": "root", "pos": [0, 0, 0], "rot": [0, 0, 0], "scl": [1, 1, 1],
                       "children": [
                           {"name": "model", "pos": [0, 0.25, 0], "rot": [0, 0, 180], "scl": [0.8, 0.8, 0.8],
                            "components": [
                                {"objectType": 5001, "ShadowVisible": True,
                                 "Model": "sc3d/test/model/test_lod1_rig | 0000",
                                 "Geometries": [{"Name": "test_lod1",
                                                 "MaterialOverride": "sc3d/test/material/mat_test_override | 1"}]},
                                {"objectType": 5003, "AsmAsset": "sc3d/test/animation/asm_test_battle | 2"}]},
                           {"name": "sun", "pos": [0, 0, 0], "rot": [-80, 0, -30], "scl": [1, 1, 1],
                            "components": [{"objectType": 5007, "Intensity": 2}]},
                           {"name": "hudoffset", "pos": [0, 0, 1.5], "rot": [0, 0, 0], "scl": [1, 1, 1]}],
                       "components": [{"objectType": 5019, "PivotPoints": [{"PivotName": "hudoffset"}]}]}}
    asm = {"States": [{"Type": "AnimClip", "Name": "Walk",
                       "Animation": "sc3d/test/animation/test_walk | 3", "BaseSpeed": 1.2}],
           "DefaultState": "Walk"}
    con = sqlite3.connect(a / "assets.scdb")
    con.execute("create table meta(path TEXT PRIMARY KEY, assetType TEXT, shortid INTEGER, assetFilename TEXT, "
                "uuid TEXT, tags TEXT, data BLOB, format INTEGER, isbundle INTEGER, parent TEXT)")
    con.execute("create table asset(path TEXT PRIMARY KEY, data BLOB, extref TEXT, format INTEGER)")
    rows = [("sc3d/test/chr_prefab_test_battle", "ScrollRuntimeScene", json.dumps(prefab), ".json"),
            ("sc3d/test/animation/asm_test_battle", "AssetASMData", json.dumps(asm), ".json"),
            ("sc3d/test/model/test_lod1_rig", "SCW", None, ".glb"),
            ("sc3d/test/animation/test_walk", "SCW", None, ".glb"),
            ("sc3d/test/material/mat_test_override", "scAssetMaterial", None, ".rmat")]
    for path, kind, data, ext in rows:
        con.execute("insert into meta(path, assetType, assetFilename, format, isbundle) values (?,?,?,1,0)",
                    (path, kind, path + ext))
        con.execute("insert into asset(path, data, format) values (?,?,0)",
                    (path, data.encode() if data else None))
    con.commit()
    con.close()
    return a
