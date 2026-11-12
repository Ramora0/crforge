"""Headless preview renderer for the extracted .glb files (numpy software rasterizer).

    uv run python -m crforge_art.preview model.glb out.png [--clip NAME --time SECONDS] [--size 512]

Supports what the sc3d extractor writes: triangle lists, node hierarchy (TRS/matrix), linear-blend
skinning, LINEAR/STEP animation of translation/rotation/scale, baseColor textures. Lighting is one
directional light plus ambient (Lambert). The camera is orthographic, looking down at ``--elevation``
degrees from ``--azimuth`` degrees around the model's up axis (glTF +Y), framed on the posed bounds.
It exists to check decoding without a GPU; it is not the game's shading.
"""
from __future__ import annotations

import argparse
import io
import json
import struct
from pathlib import Path

import numpy as np
from PIL import Image

_CT = {5120: np.int8, 5121: np.uint8, 5122: np.int16, 5123: np.uint16, 5125: np.uint32, 5126: np.float32}
_N = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}


class Glb:
    def __init__(self, data: bytes):
        _, _, length = struct.unpack_from("<III", data, 0)
        pos, self.bin = 12, b""
        while pos < length:
            n, kind = struct.unpack_from("<I4s", data, pos)
            chunk = data[pos + 8:pos + 8 + n]
            if kind == b"JSON":
                self.g = json.loads(chunk)
            elif kind == b"BIN\x00":
                self.bin = chunk
            pos += 8 + n
        self._img: dict[int, np.ndarray] = {}

    def view(self, i: int) -> bytes:
        bv = self.g["bufferViews"][i]
        o = bv.get("byteOffset", 0)
        return self.bin[o:o + bv["byteLength"]]

    def acc(self, i: int) -> np.ndarray:
        a = self.g["accessors"][i]
        dt = np.dtype(_CT[a["componentType"]])
        n = _N[a["type"]]
        raw = self.view(a["bufferView"])
        arr = np.frombuffer(raw, dt, a["count"] * n, a.get("byteOffset", 0)).reshape(a["count"], n)
        if a.get("normalized"):
            arr = arr.astype(np.float32) / np.iinfo(dt).max
        return arr

    def image(self, i: int) -> np.ndarray:
        if i not in self._img:
            im = self.g["images"][i]
            if "bufferView" in im:
                img = Image.open(io.BytesIO(self.view(im["bufferView"])))
            else:
                img = Image.open(im["uri"])
            self._img[i] = np.asarray(img.convert("RGBA"), dtype=np.float32) / 255.0
        return self._img[i]


def quat_mat(q) -> np.ndarray:
    x, y, z, w = q
    return np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                     [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                     [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])


def trs(t, r, s) -> np.ndarray:
    m = np.eye(4)
    m[:3, :3] = quat_mat(r) * np.asarray(s)[None, :]
    m[:3, 3] = t
    return m


def sample(times: np.ndarray, values: np.ndarray, t: float, path: str, interp: str):
    if t <= times[0]:
        return values[0]
    if t >= times[-1]:
        return values[-1]
    k = int(np.searchsorted(times, t) - 1)
    if interp == "STEP":
        return values[k]
    f = (t - times[k]) / max(times[k + 1] - times[k], 1e-9)
    a, b = values[k], values[k + 1]
    if path == "rotation":
        if np.dot(a, b) < 0:
            b = -b
        q = a * (1 - f) + b * f
        return q / np.linalg.norm(q)
    return a * (1 - f) + b * f


def pose(glb: Glb, clip: str | None, t: float) -> list[np.ndarray]:
    nodes = glb.g.get("nodes", [])
    local = [dict(t=n.get("translation", [0, 0, 0]), r=n.get("rotation", [0, 0, 0, 1]), s=n.get("scale", [1, 1, 1]),
                  m=n.get("matrix")) for n in nodes]
    if clip is not None:
        anims = [a for a in glb.g.get("animations", []) if a.get("name") == clip]
        if not anims:
            raise SystemExit(f"no animation named {clip!r}; have {[a.get('name') for a in glb.g.get('animations', [])]}")
        a = anims[0]
        for ch in a["channels"]:
            smp = a["samplers"][ch["sampler"]]
            path = ch["target"]["path"]
            v = sample(glb.acc(smp["input"])[:, 0], glb.acc(smp["output"]).astype(np.float64), t, path,
                       smp.get("interpolation", "LINEAR"))
            local[ch["target"]["node"]][{"translation": "t", "rotation": "r", "scale": "s"}[path]] = v
            local[ch["target"]["node"]]["m"] = None
    world = [None] * len(nodes)

    def visit(i, parent):
        L = local[i]
        m = np.array(L["m"], float).reshape(4, 4).T if L["m"] else trs(L["t"], L["r"], L["s"])
        world[i] = parent @ m
        for c in nodes[i].get("children", []):
            visit(c, world[i])
    scene = glb.g.get("scenes", [{}])[glb.g.get("scene", 0)]
    for r in scene.get("nodes", range(len(nodes))):
        visit(r, np.eye(4))
    return world


def gather(glb: Glb, world) -> list[dict]:
    """World-space triangles per primitive: positions, normals, uvs, material."""
    out = []
    for ni, n in enumerate(glb.g.get("nodes", [])):
        if "mesh" not in n or world[ni] is None:
            continue
        mesh = glb.g["meshes"][n["mesh"]]
        skin = glb.g["skins"][n["skin"]] if "skin" in n else None
        if skin:
            ibm = glb.acc(skin["inverseBindMatrices"]).reshape(-1, 4, 4).transpose(0, 2, 1).astype(np.float64) \
                if "inverseBindMatrices" in skin else np.tile(np.eye(4), (len(skin["joints"]), 1, 1))
            jm = np.stack([world[j] for j in skin["joints"]]) @ ibm
        for p in mesh["primitives"]:
            at = p["attributes"]
            pos = glb.acc(at["POSITION"]).astype(np.float64)
            nrm = glb.acc(at["NORMAL"]).astype(np.float64) if "NORMAL" in at else np.zeros_like(pos)
            uv = glb.acc(at["TEXCOORD_0"]).astype(np.float64) if "TEXCOORD_0" in at else np.zeros((len(pos), 2))
            ph = np.hstack([pos, np.ones((len(pos), 1))])
            if skin and "JOINTS_0" in at:
                j = glb.acc(at["JOINTS_0"]).astype(np.int64)
                w = glb.acc(at["WEIGHTS_0"]).astype(np.float64)
                m = np.einsum("vk,vkab->vab", w, jm[j])
            else:
                m = np.broadcast_to(world[ni], (len(pos), 4, 4))
            wp = np.einsum("vab,vb->va", m, ph)[:, :3]
            wn = np.einsum("vab,vb->va", m[:, :3, :3], nrm)
            wn /= np.maximum(np.linalg.norm(wn, axis=1, keepdims=True), 1e-9)
            idx = glb.acc(p["indices"]).reshape(-1, 3).astype(np.int64)
            out.append({"pos": wp, "nrm": wn, "uv": uv, "idx": idx, "material": p.get("material")})
    return out


def render(glb: Glb, clip=None, t=0.0, size=512, elevation=40.0, azimuth=35.0, up="y",
           bounds=None) -> np.ndarray:
    world = pose(glb, clip, t)
    prims = gather(glb, world)
    if not prims:
        raise SystemExit("nothing to draw")
    # camera basis: world up axis
    upv = {"y": np.array([0, 1.0, 0]), "z": np.array([0, 0, 1.0])}[up]
    fwd0 = {"y": np.array([0, 0, 1.0]), "z": np.array([0, -1.0, 0])}[up]
    side0 = np.cross(upv, fwd0)
    az, el = np.radians(azimuth), np.radians(elevation)
    horiz = np.cos(az) * fwd0 + np.sin(az) * side0
    eye_dir = np.cos(el) * horiz + np.sin(el) * upv           # from target towards camera
    right = np.cross(upv, eye_dir)
    right /= np.linalg.norm(right)
    cup = np.cross(eye_dir, right)
    allp = np.vstack([p["pos"] for p in prims])
    lo, hi = (allp.min(0), allp.max(0)) if bounds is None else bounds
    center = (lo + hi) / 2
    R = np.stack([right, cup, eye_dir])
    corners = np.array([[x, y, z] for x in (lo[0], hi[0]) for y in (lo[1], hi[1]) for z in (lo[2], hi[2])])
    cc = (corners - center) @ R.T
    half = max(np.abs(cc[:, 0]).max(), np.abs(cc[:, 1]).max()) * 1.08 + 1e-6
    img = np.zeros((size, size, 3), np.float32)
    img[:] = np.linspace(0.32, 0.18, size)[:, None, None] * np.array([0.9, 0.95, 1.0])
    zbuf = np.full((size, size), -np.inf)
    light = 0.6 * eye_dir + 0.8 * upv + 0.3 * right
    light /= np.linalg.norm(light)
    mats = glb.g.get("materials", [])
    for p in prims:
        v = (p["pos"] - center) @ R.T
        sx = (v[:, 0] / half * 0.5 + 0.5) * size
        sy = (0.5 - v[:, 1] / half * 0.5) * size
        sz = v[:, 2]
        tex = None
        if p["material"] is not None and p["material"] < len(mats):
            bct = mats[p["material"]].get("pbrMetallicRoughness", {}).get("baseColorTexture")
            if bct is not None:
                tex = glb.image(glb.g["textures"][bct["index"]]["source"])
        shade = np.clip(p["nrm"] @ light, 0, 1) * 0.75 + 0.35
        for a, b, c in p["idx"]:
            xs, ys = np.array([sx[a], sx[b], sx[c]]), np.array([sy[a], sy[b], sy[c]])
            x0, x1 = max(int(xs.min()), 0), min(int(xs.max()) + 1, size)
            y0, y1 = max(int(ys.min()), 0), min(int(ys.max()) + 1, size)
            if x0 >= x1 or y0 >= y1:
                continue
            den = (ys[1] - ys[2]) * (xs[0] - xs[2]) + (xs[2] - xs[1]) * (ys[0] - ys[2])
            if abs(den) < 1e-12:
                continue
            gx, gy = np.meshgrid(np.arange(x0, x1) + 0.5, np.arange(y0, y1) + 0.5)
            w0 = ((ys[1] - ys[2]) * (gx - xs[2]) + (xs[2] - xs[1]) * (gy - ys[2])) / den
            w1 = ((ys[2] - ys[0]) * (gx - xs[2]) + (xs[0] - xs[2]) * (gy - ys[2])) / den
            w2 = 1 - w0 - w1
            inside = (w0 >= -1e-6) & (w1 >= -1e-6) & (w2 >= -1e-6)
            if not inside.any():
                continue
            z = w0 * sz[a] + w1 * sz[b] + w2 * sz[c]
            zb = zbuf[y0:y1, x0:x1]
            vis = inside & (z > zb)
            if not vis.any():
                continue
            zb[vis] = z[vis]
            s = (w0 * shade[a] + w1 * shade[b] + w2 * shade[c])[vis]
            if tex is not None:
                u = (w0 * p["uv"][a, 0] + w1 * p["uv"][b, 0] + w2 * p["uv"][c, 0])[vis]
                vv = (w0 * p["uv"][a, 1] + w1 * p["uv"][b, 1] + w2 * p["uv"][c, 1])[vis]
                th, tw = tex.shape[:2]
                col = tex[(np.mod(vv, 1) * th).astype(int).clip(0, th - 1), (np.mod(u, 1) * tw).astype(int).clip(0, tw - 1), :3]
            else:
                col = np.full((int(vis.sum()), 3), 0.75)
            img[y0:y1, x0:x1][vis] = col * s[:, None]
    return (np.clip(img, 0, 1) * 255).astype(np.uint8)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("glb")
    ap.add_argument("out")
    ap.add_argument("--clip")
    ap.add_argument("--time", type=float, default=0.0)
    ap.add_argument("--size", type=int, default=512)
    ap.add_argument("--elevation", type=float, default=40.0)
    ap.add_argument("--azimuth", type=float, default=35.0)
    ap.add_argument("--up", choices=["y", "z"], default="y")
    a = ap.parse_args(argv)
    glb = Glb(Path(a.glb).read_bytes())
    Image.fromarray(render(glb, a.clip, a.time, a.size, a.elevation, a.azimuth, a.up)).save(a.out)


if __name__ == "__main__":
    main()
