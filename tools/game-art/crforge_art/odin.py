"""Supercell's ``SC_odin_format`` glTF extension -> plain glTF 2.0.

After ``flatgltf`` has turned the FLA2 chunk into glTF JSON, the file is still not standard: its
geometry, node hierarchy, materials and animation live in the ``SC_odin_format`` extension.
Everything below was checked against all 1437 sc3d .glb files of Clash Royale (see
docs/realistic-rendering.md "3D assets" for DATA / INFERRED / GUESS labels).

root ``extensions.SC_odin_format``:
  ``bufferView``      the view holding the vertex streams (and nothing else)
  ``meshDataInfos``   [{vertexDescriptors: [{offset, stride, attributes: [{name, index, format, offset}]}]}]
                      interleaved vertex streams, ``offset`` relative to ``bufferView``
  ``materials``       [{name, shader, blendMode, constants, variables: {textures, floats, floatVectors,
                      booleans}}]: the glTF ``materials`` (primitives index into this list)
  ``animation``       {frameRate, keyframeCount, firstFrame, lastFrame, packed: {...}} (clip files)
node ``extensions.SC_odin_format.parent``: the parent node index (glTF ``children`` are not used)
primitive ``extensions.SC_odin_format.meshDataInfoIndex``: which meshDataInfo holds its vertices;
  the vertex count is max(index) + 1 over the primitives sharing it
mesh / skin ``extensions.SC_odin_format.bounds`` (+ mesh ``skinnedSubMeshMask``): kept in ``extras``
accessor ``componentType``: high 16 bits are an engine hint (1, 2, 3); the glTF type is the low 16.

Vertex formats (attribute ``format``):
   3  4 x uint8              (bone indices)
   9  4 x uint8 normalized   (vertex colour RGBA)
  12  4 x int8 normalized    (normal: xyz + 0; tangent: xyz + handedness sign)
  22  2 x uint16 normalized  (UV, v up: glTF v = 1 - v)
  29  2 x float32            (UV, v up: glTF v = 1 - v)
  30  3 x float32            (position)
  36  uint32 packed weights: w1 = bits 21..31, w2 = bits 10..20, w3 = bits 0..9, each / 4095,
      w0 = 1 - w1 - w2 - w3 (so w1..w3 <= 0.5: a joint weighing more is listed twice; merged here)
Attribute ``index``: 0 pos, 1 normal, 2 uv0, 3 uv1, 4 color, 5 boneindex, 6 boneweights, 7 tangent,
  15 color1 (8..14 never occur in Clash Royale).

Packed animation (``animation.packed``; accessors are int16 unless noted):
  nodes          [{nodeIndex, flags, frameCount, dataSize}] in data order
                 flags: 1 frame time (never used), 2 rotation, 4 translation, 8 scale,
                 16 scale is xyz (else one uniform value)
  nodeAccessor   float32, 8 per node: base translation xyz, base scale xyz,
                 translation multiplier, scale multiplier
  stride         int16 values per frame summed over all nodes (an uncompressed frame); unused here
  uintAccessor   int16, 4 per node: base rotation quaternion xyzw * 32767
  dataAccessor   int16 stream, per node ``dataSize`` values: ``n`` keyframes then ``-r`` (hold the last
                 keyframe r more frames), alternating until frameCount frames are covered; a keyframe
                 is [rotation xyzw * 32767][translation xyz][scale x or xyz] for the flagged parts;
                 translation = base + value * multiplier, scale = base + value * multiplier.
  Frames are sampled at ``frameRate``; nodes without a flag keep their base value.
"""
from __future__ import annotations

import copy
from dataclasses import dataclass, field

import numpy as np

EXT = "SC_odin_format"
COMPONENT_DTYPES = {5120: np.int8, 5121: np.uint8, 5122: np.int16, 5123: np.uint16, 5125: np.uint32,
                    5126: np.float32}
TYPE_SIZES = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT2": 4, "MAT3": 9, "MAT4": 16}
ATTRIBUTE_NAMES = {0: "POSITION", 1: "NORMAL", 2: "TEXCOORD_0", 3: "TEXCOORD_1", 4: "COLOR_0",
                   5: "JOINTS_0", 6: "WEIGHTS_0", 7: "TANGENT", 15: "COLOR_1"}
VERTEX_FORMATS = {  # format -> (numpy dtype, components, bytes); 36 is special
    3: (np.uint8, 4, 4), 9: (np.uint8, 4, 4), 12: (np.int8, 4, 4), 22: (np.uint16, 2, 4),
    29: (np.float32, 2, 8), 30: (np.float32, 3, 12), 36: (np.uint32, 1, 4),
}
FLAG_TIME, FLAG_ROT, FLAG_TRANS, FLAG_SCALE, FLAG_SCALE3 = 1, 2, 4, 8, 16


class OdinError(ValueError):
    pass


# --------------------------------------------------------------------------- buffer builder
@dataclass
class Builder:
    """Collects binary blobs into one BIN buffer, creating bufferViews/accessors in a glTF dict."""
    gltf: dict
    blobs: list[bytes] = field(default_factory=list)
    size: int = 0

    def view(self, data: bytes, target: int | None = None, stride: int | None = None) -> int:
        pad = -self.size % 16
        if pad:
            self.blobs.append(b"\x00" * pad)
            self.size += pad
        bv = {"buffer": 0, "byteOffset": self.size, "byteLength": len(data)}
        if stride:
            bv["byteStride"] = stride
        if target:
            bv["target"] = target
        self.blobs.append(bytes(data))
        self.size += len(data)
        self.gltf.setdefault("bufferViews", []).append(bv)
        return len(self.gltf["bufferViews"]) - 1

    def accessor(self, arr: np.ndarray, gtype: str, normalized: bool = False, minmax: bool = False,
                 target: int | None = None, name: str | None = None) -> int:
        arr = np.ascontiguousarray(arr)
        comp = {np.dtype(v): k for k, v in COMPONENT_DTYPES.items()}[arr.dtype]
        n = TYPE_SIZES[gtype]
        count = arr.size // n
        acc = {"bufferView": self.view(arr.tobytes(), target), "componentType": comp, "count": int(count),
               "type": gtype}
        if normalized:
            acc["normalized"] = True
        if minmax and count:
            flat = arr.reshape(count, n)
            if arr.dtype == np.float32:
                acc["min"] = [float(v) for v in flat.min(0)]
                acc["max"] = [float(v) for v in flat.max(0)]
            else:
                acc["min"] = [int(v) for v in flat.min(0)]
                acc["max"] = [int(v) for v in flat.max(0)]
        if name:
            acc["name"] = name
        self.gltf.setdefault("accessors", []).append(acc)
        return len(self.gltf["accessors"]) - 1

    @classmethod
    def resume(cls, gltf: dict, bin_data: bytes) -> "Builder":
        """A builder that appends to an existing buffer (``gltf``'s views already point into it)."""
        return cls(gltf, [bytes(bin_data)] if bin_data else [], len(bin_data))

    def data(self) -> bytes:
        out = b"".join(self.blobs)
        return out + b"\x00" * (-len(out) % 4)


# --------------------------------------------------------------------------- reading the source
class Source:
    """Accessor/bufferView reader over a decoded (still Odin) glTF and its BIN chunk."""

    def __init__(self, gltf: dict, bin_data: bytes):
        self.g = gltf
        self.bin = bytes(bin_data)

    def view_bytes(self, i: int) -> bytes:
        bv = self.g["bufferViews"][i]
        o = bv.get("byteOffset", 0)
        n = bv.get("byteLength", 0)
        if o + n > len(self.bin):
            raise OdinError(f"bufferView {i} ({o}+{n}) beyond the BIN chunk ({len(self.bin)})")
        return self.bin[o:o + n]

    def accessor(self, i: int) -> np.ndarray:
        a = self.g["accessors"][i]
        ct = a["componentType"] & 0xFFFF
        dt = np.dtype(COMPONENT_DTYPES[ct]).newbyteorder("<")
        n = TYPE_SIZES[a["type"]]
        count = a["count"]
        if "bufferView" not in a:
            return np.zeros((count, n), dt)
        raw = self.view_bytes(a["bufferView"])
        bv = self.g["bufferViews"][a["bufferView"]]
        off = a.get("byteOffset", 0)
        stride = bv.get("byteStride") or dt.itemsize * n
        need = off + (count - 1) * stride + dt.itemsize * n if count else 0
        if need > len(raw):
            raise OdinError(f"accessor {i}: {count} x {a['type']} does not fit its bufferView")
        if stride == dt.itemsize * n:
            return np.frombuffer(raw, dt, count * n, off).reshape(count, n)
        return np.lib.stride_tricks.as_strided(np.frombuffer(raw, np.uint8, len(raw) - off, off).view(dt),
                                               (count, n), (stride, dt.itemsize)).copy()


def _ext(obj: dict | None):
    if not obj:
        return None
    ext = obj.get("extensions") or {}
    return ext.get(EXT)


def _pop_ext(obj: dict):
    ext = obj.get("extensions")
    if not isinstance(ext, dict) or EXT not in ext:
        return None
    v = ext.pop(EXT)
    if not ext:
        obj.pop("extensions")
    return v


# --------------------------------------------------------------------------- vertex streams
def decode_attribute(raw: bytes, base: int, stride: int, count: int, attr: dict) -> tuple[np.ndarray, str, dict]:
    """(array, glTF accessor type, accessor options) for one Odin vertex attribute."""
    fmt = attr["format"]
    if fmt not in VERTEX_FORMATS:
        raise OdinError(f"unknown vertex format {fmt} ({attr.get('name')})")
    dt, n, size = VERTEX_FORMATS[fmt]
    start = base + attr.get("offset", 0)
    if count and start + (count - 1) * stride + size > len(raw):
        raise OdinError(f"vertex stream {attr.get('name')}: {count} vertices overrun the Odin buffer")
    u8 = np.frombuffer(raw, np.uint8)
    rows = np.lib.stride_tricks.as_strided(u8[start:], (count, size), (stride, 1)).copy()
    vals = rows.view(np.dtype(dt).newbyteorder("<")).reshape(count, -1)
    index = attr.get("index")
    if fmt == 36:
        w = vals[:, 0].astype(np.uint32)
        x = (w >> 21) / 4095.0
        y = ((w >> 10) & 0x7FF) / 4095.0
        z = (w & 0x3FF) / 4095.0
        out = np.stack([1.0 - x - y - z, x, y, z], 1).astype(np.float32)
        out = np.clip(out, 0, 1)
        s = out.sum(1, keepdims=True)
        out = np.where(s > 0, out / np.where(s > 0, s, 1), np.array([1, 0, 0, 0], np.float32)).astype(np.float32)
        return out, "VEC4", {}
    if fmt == 3:
        return vals.astype(np.uint8), "VEC4", {}
    if fmt == 9:
        return vals.astype(np.uint8), "VEC4", {"normalized": True}
    if fmt in (22, 29, 30):
        f = vals.astype(np.float32) / (65535.0 if fmt == 22 else 1.0)
        if n == 2:  # UVs are stored with v up (OpenGL); glTF's v runs down the image
            f[:, 1] = 1.0 - f[:, 1]
        return f.astype(np.float32), "VEC2" if n == 2 else "VEC3", ({"minmax": True} if index == 0 else {})
    if fmt == 12:
        f = np.maximum(vals.astype(np.float32) / 127.0, -1.0)
        xyz = f[:, :3]
        ln = np.linalg.norm(xyz, axis=1, keepdims=True)
        if index == 7:  # tangent: xyz + handedness
            xyz = np.where(ln > 1e-6, xyz / np.where(ln > 1e-6, ln, 1), np.array([1, 0, 0], np.float32))
            sign = np.where(f[:, 3:4] < 0, -1.0, 1.0)
            return np.hstack([xyz, sign]).astype(np.float32), "VEC4", {}
        xyz = np.where(ln > 1e-6, xyz / np.where(ln > 1e-6, ln, 1), np.array([0, 1, 0], np.float32))
        return xyz.astype(np.float32), "VEC3", {}
    raise OdinError(f"vertex format {fmt} not handled")


# --------------------------------------------------------------------------- animation
def decode_packed_animation(src: Source, anim: dict) -> list[dict]:
    """Per animated node: {node, frames, translation (F,3), rotation (F,4), scale (F,3), flags}."""
    p = anim["packed"]
    data = src.accessor(p["dataAccessor"]).reshape(-1).astype(np.int32)
    base = src.accessor(p["nodeAccessor"]).reshape(-1).astype(np.float64)
    rot_base = src.accessor(p["uintAccessor"]).reshape(-1).astype(np.float64) if "uintAccessor" in p else None
    stride = 8 if rot_base is not None else 12  # floats per node in nodeAccessor
    n_nodes = len(p.get("nodes") or [])
    if base.size < stride * n_nodes or (rot_base is not None and rot_base.size < 4 * n_nodes):
        raise OdinError(f"animation base data too short for {n_nodes} nodes")
    out = []
    pos = 0
    for k, nd in enumerate(p.get("nodes") or []):
        flags = nd.get("flags", 0)
        frames = max(1, nd.get("frameCount", anim.get("keyframeCount", 1)))
        b = base[k * stride:(k + 1) * stride]
        if rot_base is not None:
            t0, s0, tmul, smul = b[0:3], b[3:6], b[6], b[7]
            r0 = rot_base[k * 4:k * 4 + 4] / 32767.0
        else:  # stride-12 layout (no uintAccessor): t xyz, r xyzw, s xyz, multipliers. Not seen in CR.
            t0, r0, s0, tmul, smul = b[0:3], b[3:7], b[7:10], b[10], b[11]
        per = ((1 if flags & FLAG_TIME else 0) + (4 if flags & FLAG_ROT else 0) + (3 if flags & FLAG_TRANS else 0)
               + ((3 if flags & FLAG_SCALE3 else 1) if flags & FLAG_SCALE else 0))
        keys = np.zeros((frames, per), np.float64)
        start = pos
        fi = 0
        if flags:
            while fi < frames:
                if fi:
                    hold = abs(int(data[pos]))
                    pos += 1
                    end = min(frames, fi + hold)
                    keys[fi:end] = keys[fi - 1]
                    fi = end
                    if fi >= frames:
                        break
                n = int(data[pos])
                pos += 1
                if n <= 0 or fi + n > frames or pos + n * per > len(data):
                    raise OdinError(f"animation node {k}: bad keyframe run {n} at frame {fi}")
                keys[fi:fi + n] = data[pos:pos + n * per].reshape(n, per)
                pos += n * per
                fi += n
        if nd.get("dataSize") is not None and pos - start != nd["dataSize"]:
            raise OdinError(f"animation node {k}: read {pos - start} values, dataSize says {nd['dataSize']}")
        col = 1 if flags & FLAG_TIME else 0
        rot = np.tile(r0, (frames, 1))
        trans = np.tile(t0, (frames, 1))
        scale = np.tile(s0, (frames, 1))
        if flags & FLAG_ROT:
            rot = keys[:, col:col + 4] / 32767.0
            col += 4
        if flags & FLAG_TRANS:
            trans = t0 + keys[:, col:col + 3] * tmul
            col += 3
        if flags & FLAG_SCALE:
            if flags & FLAG_SCALE3:
                scale = s0 + keys[:, col:col + 3] * smul
                col += 3
            else:
                scale = s0 + keys[:, col:col + 1] * smul
                col += 1
        ln = np.linalg.norm(rot, axis=1, keepdims=True)
        rot = np.where(ln > 1e-9, rot / np.where(ln > 1e-9, ln, 1), np.array([0, 0, 0, 1.0]))
        out.append({"node": nd.get("nodeIndex", 0), "flags": flags, "frames": frames,
                    "translation": trans.astype(np.float32), "rotation": rot.astype(np.float32),
                    "scale": scale.astype(np.float32)})
    if pos != len(data):
        raise OdinError(f"animation data has {len(data) - pos} unread values")
    return out


def animation_to_gltf(b: Builder, tracks: list[dict], fps: float, name: str, node_map=None,
                      extras: dict | None = None) -> dict | None:
    """Adds one glTF animation (LINEAR, one key per frame) built from decoded tracks.

    node_map: optional function source node index -> target node index (or None to drop the track).
    """
    channels, samplers = [], []
    inputs: dict[int, int] = {}
    fps = fps or 30.0

    def time_input(n: int, keys: int) -> int:
        k = (n, keys)
        if k not in inputs:
            if keys == 2:  # constant channel: first and last frame
                times = np.array([0.0, max(n - 1, 1) / fps], np.float32)
            else:
                times = (np.arange(n, dtype=np.float32) / np.float32(fps)).astype(np.float32)
            inputs[k] = b.accessor(times, "SCALAR", minmax=True)
        return inputs[k]

    for t in tracks:
        target = node_map(t["node"]) if node_map else t["node"]
        if target is None:
            continue
        n = t["frames"]
        for path, gtype in (("translation", "VEC3"), ("rotation", "VEC4"), ("scale", "VEC3")):
            v = t[path]
            if n < 2 or np.abs(v - v[0]).max() < 1e-6:
                v, inp = np.repeat(v[:1], 2, axis=0), time_input(n, 2)
            else:
                inp = time_input(n, n)
            samplers.append({"input": inp, "output": b.accessor(v.astype(np.float32), gtype),
                             "interpolation": "LINEAR"})
            channels.append({"sampler": len(samplers) - 1, "target": {"node": target, "path": path}})
    if not channels:
        return None
    anim = {"name": name, "channels": channels, "samplers": samplers}
    if extras:
        anim["extras"] = extras
    return anim


# --------------------------------------------------------------------------- whole file
def convert(g: dict, bin_data: bytes, material_cb=None, name: str = "") -> tuple[dict, bytes, dict]:
    """Odin glTF (from flatgltf.load) -> (standard glTF dict, BIN, info).

    material_cb(odin_material, builder, gltf) -> glTF material dict; default keeps only the name
    and puts the Odin material in ``extras``. ``info`` carries the decoded animation tracks
    (``info["animation"]``) for clip files, so they can be retargeted onto a model.
    """
    g = copy.deepcopy(g)  # extensions are popped below
    src = Source(g, bin_data)
    root_ext = _ext(g) or {}
    out: dict = {"asset": {"version": "2.0", "generator": "crforge-art sc3d (from Supercell FLA2/Odin)"}}
    if g.get("asset", {}).get("copyright"):
        out["asset"]["copyright"] = g["asset"]["copyright"]
    b = Builder(out)
    info: dict = {}

    # nodes and hierarchy
    nodes = []
    children: dict[int, list[int]] = {}
    roots = []
    for i, n in enumerate(g.get("nodes", [])):
        n = dict(n)
        ext = _pop_ext(n)
        parent = ext.get("parent") if isinstance(ext, dict) else None
        if parent is None or parent < 0 or parent == i:
            roots.append(i)
        else:
            children.setdefault(parent, []).append(i)
        n.pop("children", None)
        nodes.append(n)
    for p, ch in children.items():
        if p >= len(nodes):
            raise OdinError(f"node parent {p} out of range")
        nodes[p]["children"] = ch
    if nodes:
        out["nodes"] = nodes
        out["scenes"] = [{"nodes": roots}]
        out["scene"] = 0

    # materials
    odin_mats = root_ext.get("materials") or []
    if odin_mats:
        mats = []
        for m in odin_mats:
            if material_cb:
                mats.append(material_cb(m, b, out))
            else:
                mats.append({"name": m.get("name", ""), "extras": {"odin": m}})
        out["materials"] = mats

    # meshes
    odin_view = root_ext.get("bufferView")
    vraw = src.view_bytes(odin_view) if odin_view is not None else b""
    infos = root_ext.get("meshDataInfos") or []
    copied_acc: dict[int, int] = {}

    def copy_accessor(i: int, target: int | None = None) -> int:
        if i not in copied_acc:
            a = g["accessors"][i]
            arr = src.accessor(i)
            ct = a["componentType"] & 0xFFFF
            arr = arr.astype(COMPONENT_DTYPES[ct])
            copied_acc[i] = b.accessor(arr, a["type"], normalized=a.get("normalized", False), target=target)
        return copied_acc[i]

    vcount: dict[int, int] = {}
    for m in g.get("meshes", []):
        for p in m.get("primitives", []):
            pe = _ext(p)
            if isinstance(pe, dict) and "indices" in p:
                mi = pe.get("meshDataInfoIndex", 0)
                idx = src.accessor(p["indices"])
                vcount[mi] = max(vcount.get(mi, 0), int(idx.max()) + 1 if idx.size else 0)
    attr_cache: dict[int, dict] = {}

    def vertex_attributes(mi: int) -> dict:
        if mi in attr_cache:
            return attr_cache[mi]
        if mi >= len(infos):
            raise OdinError(f"meshDataInfoIndex {mi} out of range")
        count = vcount.get(mi, 0)
        decoded = {}
        for vd in infos[mi].get("vertexDescriptors") or []:
            for at in vd.get("attributes") or []:
                gname = ATTRIBUTE_NAMES.get(at.get("index"))
                if gname is None:
                    continue
                decoded[gname] = decode_attribute(vraw, vd.get("offset", 0), vd.get("stride", 0), count, at)
        if ("WEIGHTS_0" in decoded) != ("JOINTS_0" in decoded):
            decoded.pop("WEIGHTS_0", None)
            decoded.pop("JOINTS_0", None)
        if "JOINTS_0" in decoded:
            joints, weights = decoded["JOINTS_0"][0], decoded["WEIGHTS_0"][0]
            # the packing caps influences 1..3 at 0.5, so a heavy joint can be listed twice: merge
            for k in range(1, 4):
                for m in range(k):
                    dup = (joints[:, k] == joints[:, m]) & (weights[:, k] > 0)
                    weights[dup, m] += weights[dup, k]
                    weights[dup, k] = 0
            joints[weights <= 0] = 0  # unused influences point at joint 0 (glTF recommendation)
        attrs = {}
        for gname, (arr, gtype, opts) in decoded.items():
            attrs[gname] = b.accessor(arr, gtype, normalized=opts.get("normalized", False),
                                      minmax=opts.get("minmax", False), target=34962)
        attr_cache[mi] = attrs
        return attrs

    meshes = []
    old_to_new: dict[int, int] = {}
    for mi_, m in enumerate(g.get("meshes", [])):
        m = dict(m)
        me = _pop_ext(m)
        prims = []
        for p in m.get("primitives", []):
            p = dict(p)
            pe = _pop_ext(p)
            if not isinstance(pe, dict) or "indices" not in p:
                continue  # empty primitive (animation files carry these)
            np_ = {"attributes": dict(vertex_attributes(pe.get("meshDataInfoIndex", 0))),
                   "indices": copy_accessor(p["indices"], 34963), "mode": p.get("mode", 4)}
            if "material" in p:
                np_["material"] = p["material"]
            prims.append(np_)
        if not prims:
            continue
        m["primitives"] = prims
        if me:
            m.setdefault("extras", {})["odin"] = me
        old_to_new[mi_] = len(meshes)
        meshes.append(m)
    for n in nodes:  # meshes without primitives are dropped: remap node.mesh
        if "mesh" in n:
            if n["mesh"] in old_to_new:
                n["mesh"] = old_to_new[n["mesh"]]
            else:
                n.pop("mesh")
                n.pop("skin", None)
    if meshes:
        out["meshes"] = meshes

    # skins
    skins = []
    for s in g.get("skins", []):
        s = dict(s)
        se = _pop_ext(s)
        if "inverseBindMatrices" in s:
            s["inverseBindMatrices"] = copy_accessor(s["inverseBindMatrices"])
        if se:
            s.setdefault("extras", {})["odin"] = se
        skins.append(s)
    if skins:
        out["skins"] = skins
    for n in nodes:  # a skin on a node without a mesh is invalid glTF
        if "skin" in n and "mesh" not in n:
            n.pop("skin")
    # A skinned mesh ignores its node's transform (the joints place it), so make those nodes scene
    # roots, as glTF recommends; the game parents them under "Geometry", which changes nothing.
    for i, n in enumerate(nodes):
        if "skin" in n:
            for k in ("translation", "rotation", "scale", "matrix"):  # ignored for skinned meshes
                n.pop(k, None)
        if "skin" in n and i not in roots:
            for p in nodes:
                if i in p.get("children", []):
                    p["children"].remove(i)
                    if not p["children"]:
                        p.pop("children")
            roots.append(i)

    # animation clip
    anim = root_ext.get("animation")
    if anim:
        tracks = decode_packed_animation(src, anim)
        info["animation"] = {"tracks": tracks, "fps": anim.get("frameRate") or 30.0,
                             "frames": anim.get("keyframeCount"), "firstFrame": anim.get("firstFrame", 0),
                             "lastFrame": anim.get("lastFrame")}
        a = animation_to_gltf(b, tracks, anim.get("frameRate") or 30.0, name or "clip",
                              extras={"frameRate": anim.get("frameRate"), "firstFrame": anim.get("firstFrame", 0),
                                      "lastFrame": anim.get("lastFrame")})
        if a:
            out["animations"] = [a]

    bin_out = b.data()
    if bin_out:
        out["buffers"] = [{"byteLength": len(bin_out)}]
    other = [e for e in g.get("extensionsUsed", []) if e != EXT]
    if other:
        out["extensionsUsed"] = other
        info["unknown_extensions"] = other
    return out, bin_out, info


def node_names(g: dict) -> list[str]:
    return [n.get("name", "") for n in g.get("nodes", [])]


def mat4_from_trs(t, r, s) -> np.ndarray:
    x, y, z, w = r
    rm = np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                   [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                   [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])
    m = np.eye(4)
    m[:3, :3] = rm * np.asarray(s)[None, :]
    m[:3, 3] = t
    return m


def node_matrix(n: dict) -> np.ndarray:
    if "matrix" in n:
        return np.array(n["matrix"], dtype=np.float64).reshape(4, 4).T
    return mat4_from_trs(n.get("translation", [0, 0, 0]), n.get("rotation", [0, 0, 0, 1]), n.get("scale", [1, 1, 1]))

