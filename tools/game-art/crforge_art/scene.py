"""Write one .sc file as a scene JSON + its atlases as PNG (the art cache format).

The scene is the .sc file's own content, translated 1:1 - nothing is pre-rendered:

{
  "format": 1,
  "file": "chr_knight.sc",
  "textures": [{"png": "chr_knight_0.png", "width": 1813, "height": 2041}],
  "exports": {"Knight_run1_5": 37, ...},              # export name -> object id
  "shapes": {"12": [{"tex": 0, "xy": [x0,y0,x1,y1,...], "uv": [u0,v0,...]}, ...]},
        # one entry per draw command: a triangle strip (triangles i,i+1,i+2), xy in display
        # units (y down), uv normalized 0..1 over the texture (v down)
  "clips": {"37": {"fps": 30, "bank": 0,
                   "children": [12, 13],              # object ids (shape, clip or text field)
                   "names": ["", ""],                 # instance names, "" if none
                   "blend": [0, 0],                   # 0 normal, 3 multiply, 4 screen, 8 add, ...
                   "frames": [[child, matrix, color, child, matrix, color, ...], ...],
                        # per frame, triples: child index, matrix index, color index
                        # (65535 = identity for matrix and color)
                   "labels": {"0": "action_frame"},   # frame index -> label (non-empty only)
                   "grid": [l, t, r, b] | null}},     # 9-slice scaling grid, if any
  "banks": [{"matrices": [a,b,c,d,tx,ty, ...],        # x' = a*x + c*y + tx ; y' = b*x + d*y + ty
             "colors": [r_mul,g_mul,b_mul,alpha,r_add,g_add,b_add, ...]}],   # bytes 0..255
  "text": [ids of text fields (not drawn)]
}
Atlas PNGs are straight (non-premultiplied) RGBA. (The game stores its atlases premultiplied;
they are un-premultiplied when written, see straight_alpha().)
"""
from __future__ import annotations

import json
import os
from pathlib import Path

import numpy as np

from . import sc2, sctx

FORMAT = 1


def straight_alpha(img: np.ndarray) -> np.ndarray:
    """Premultiplied RGBA uint8 -> straight RGBA uint8 (rgb = rgb * 255 / alpha, 0 where alpha is 0)."""
    a = img[..., 3:4].astype(np.uint32)
    rgb = (img[..., :3].astype(np.uint32) * 255 + a // 2) // np.maximum(a, 1)
    rgb = np.where(a > 0, np.minimum(rgb, 255), 0).astype(np.uint8)
    return np.ascontiguousarray(np.concatenate([rgb, img[..., 3:4]], axis=-1))


def _inline_rgba(ref: sc2.TextureRef, name: str) -> np.ndarray:
    """Texture pixels stored inside the .sc file (seen: pixel type 0 = raw RGBA8, rows top-down)."""
    n = ref.width * ref.height * 4
    if ref.data is None or ref.pixel_type != 0 or len(ref.data) < n:
        raise NotImplementedError(f"{name}: inline texture pixel type {ref.pixel_type} "
                                  f"({0 if ref.data is None else len(ref.data)} bytes) not supported")
    return np.frombuffer(ref.data[:n], np.uint8).reshape(ref.height, ref.width, 4)


def _r(a: np.ndarray, nd: int = 3) -> list:
    return [round(float(v), nd) for v in np.asarray(a).ravel()]


def scene_dict(f: sc2.SC2File) -> dict:
    textures = []
    for i, t in enumerate(f.textures):
        png = (Path(t.external).stem if t.external else f"{f.path.stem}_{i}") + ".png"
        textures.append({"png": png, "width": t.width, "height": t.height})
    shapes = {}
    for sid, s in f.shapes.items():
        shapes[str(sid)] = [{"tex": c.texture, "xy": _r(c.xy, 2), "uv": _r(c.uv, 5)} for c in s.commands]
    clips = {}
    for cid, c in f.movieclips.items():
        frames = []
        for fr in c.frames:
            flat = []
            for e in fr:
                flat += [e.child, e.matrix, e.color]
            frames.append(flat)
        clips[str(cid)] = {
            "fps": c.fps, "bank": c.bank, "children": c.children, "names": c.child_names,
            "blend": c.blend, "frames": frames,
            "labels": {str(i): l for i, l in enumerate(c.labels) if l},
            "grid": list(c.scaling_grid) if c.scaling_grid else None}
    banks = [{"matrices": _r(b.matrices, 5), "colors": [int(v) for v in b.colors.ravel()]} for b in f.banks]
    return {"format": FORMAT, "file": f.path.name, "textures": textures, "exports": f.exports,
            "shapes": shapes, "clips": clips, "banks": banks, "text": sorted(f.textfield_ids)}


def _tmp_name(p: Path) -> Path:
    return p.with_name(f".{p.name}.{os.getpid()}.tmp")


def write(sc_path: Path, out_dir: Path, overwrite: bool = False, f: sc2.SC2File | None = None) -> dict:
    """Writes <out>/<stem>.json and its atlases; returns a small summary.

    Atlases are read from sc_path's folder. Each output file is written to a temp name and
    renamed into place, PNGs before the JSON, so an existing JSON means a complete entry.
    `f` may be passed if the caller already parsed sc_path.
    """
    from PIL import Image

    out_dir.mkdir(parents=True, exist_ok=True)
    f = f if f is not None else sc2.load(sc_path)
    scene = scene_dict(f)
    target = out_dir / f"{sc_path.stem}.json"
    for t, ref in zip(scene["textures"], f.textures):
        png = out_dir / t["png"]
        if png.exists() and not overwrite:
            continue
        if ref.external is None:
            img = _inline_rgba(ref, sc_path.name)
        else:
            img = sctx.load(sc_path.parent / ref.external)
        tmp = _tmp_name(png)
        Image.fromarray(straight_alpha(img), "RGBA").save(tmp, format="PNG", compress_level=4)
        os.replace(tmp, png)
    tmp = _tmp_name(target)
    tmp.write_text(json.dumps(scene, separators=(",", ":")))
    os.replace(tmp, target)
    return {"file": sc_path.name, "exports": len(f.exports), "shapes": len(f.shapes),
            "clips": len(f.movieclips), "textures": len(scene["textures"]),
            "pngs": [t["png"] for t in scene["textures"]]}
