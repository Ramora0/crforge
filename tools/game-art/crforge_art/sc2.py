"""SC2 (.sc v5/v6) parser -> plain Python objects.

File layout:
    "SC" | u32 version (BE<=4 => SC1, else LE) | [v6: u16 0]
    u32 header_size | Header flatbuffer
    zstd(payload) [compressed_size bytes, or rest of file] | [external matrix bank]
payload:
    u32 size | DataStorage flatbuffer          (strings, frame elements, vertices, matrix banks)
    at header.resources_offset: chunks of (u32 size | flatbuffer):
        ExportNames, TextFields, Shapes, MovieClips, MovieClipModifiers, Textures
"""
from __future__ import annotations

import struct
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np
import zstandard

from . import _GEN  # noqa: F401  (puts generated code on sys.path)
from sc.flash.SC2.DataStorage import DataStorage  # type: ignore
from sc.flash.SC2.ExportNames import ExportNames  # type: ignore
from sc.flash.SC2.Header import Header  # type: ignore
from sc.flash.SC2.MovieClips import MovieClips  # type: ignore
from sc.flash.SC2.Shapes import Shapes  # type: ignore
from sc.flash.SC2.Textures import Textures  # type: ignore

PRECISION_MUL = {0: 1.0, 1: 1.0, 2: 20.0, 3: 1024.0}  # None, Default, Twip, Optimized
NO_INDEX = 0xFFFF


@dataclass
class DrawCommand:
    texture: int
    xy: np.ndarray      # (n, 2) float32, display units (pixels at 1x)
    uv: np.ndarray      # (n, 2) float32 in [0, 1]


@dataclass
class Shape:
    id: int
    commands: list[DrawCommand]


@dataclass
class FrameElement:
    child: int          # index into MovieClip.children
    matrix: int         # index into matrix bank (0xFFFF = identity)
    color: int          # index into color bank (0xFFFF = identity)


@dataclass
class MovieClip:
    id: int
    fps: int
    bank: int
    children: list[int]                 # display object ids
    child_names: list[str]
    blend: list[int]
    frames: list[list[FrameElement]]
    labels: list[str]
    scaling_grid: tuple | None = None


@dataclass
class TextureRef:
    external: str | None
    width: int
    height: int
    pixel_type: int
    data: bytes | None = None


@dataclass
class MatrixBank:
    matrices: np.ndarray                # (n, 6) a b c d tx ty
    colors: np.ndarray                  # (n, 7) r_mul g_mul b_mul alpha r_add g_add b_add (uint8)


@dataclass
class SC2File:
    path: Path
    version: int
    exports: dict[str, int] = field(default_factory=dict)      # export name -> object id
    shapes: dict[int, Shape] = field(default_factory=dict)
    movieclips: dict[int, MovieClip] = field(default_factory=dict)
    textures: list[TextureRef] = field(default_factory=list)
    banks: list[MatrixBank] = field(default_factory=list)
    textfield_ids: set[int] = field(default_factory=set)


def _vec_str(fb, n_fn, get_fn) -> list[str]:
    return [get_fn(i).decode("utf-8", "replace") if get_fn(i) is not None else "" for i in range(n_fn())]


def load(path: str | Path) -> SC2File:
    path = Path(path)
    raw = path.read_bytes()
    if raw[:2] != b"SC":
        raise ValueError(f"{path}: not an SC file")
    v_be = struct.unpack(">I", raw[2:6])[0]
    if v_be <= 4:
        raise ValueError(f"{path}: SC1 file (version {v_be}) not supported")
    version = struct.unpack("<I", raw[2:6])[0]
    pos = 6
    if version == 6:
        pos += 2
    (hsize,) = struct.unpack_from("<I", raw, pos)
    pos += 4
    hdr = Header.GetRootAs(raw[pos:pos + hsize], 0)
    pos += hsize

    csize = hdr.CompressedSize()
    blob = raw[pos:pos + csize] if csize else raw[pos:]
    if blob[:4] == b"\x28\xb5\x2f\xfd":
        payload = zstandard.ZstdDecompressor().decompressobj().decompress(blob)
    else:
        payload = blob
    if hdr.ExternalMatrixBankSize():
        raise NotImplementedError(f"{path}: external matrix banks not implemented")
    if version == 6:
        raise NotImplementedError(f"{path}: v6 compressed movieclips not implemented")

    out = SC2File(path=path, version=version)

    # ---- data storage
    (ds_size,) = struct.unpack_from("<I", payload, 0)
    ds = DataStorage.GetRootAs(payload[4:4 + ds_size], 0)
    strings = [ds.Strings(i).decode("utf-8", "replace") for i in range(ds.StringsLength())]
    elements = ds.MovieclipsFrameElementsAsNumpy() if ds.MovieclipsFrameElementsLength() else np.zeros(0, np.uint16)
    vbuf = ds.ShapesBitmapPoinsAsNumpy().tobytes() if ds.ShapesBitmapPoinsLength() else b""
    vertex_dtype = np.dtype([("x", "<f4"), ("y", "<f4"), ("u", "<u2"), ("v", "<u2")])
    verts = np.frombuffer(vbuf, dtype=vertex_dtype) if vbuf else np.zeros(0, vertex_dtype)
    rects = [(ds.Rectangles(i).Left(), ds.Rectangles(i).Top(), ds.Rectangles(i).Right(), ds.Rectangles(i).Bottom())
             for i in range(ds.RectanglesLength())]

    sm = PRECISION_MUL.get(hdr.ScalePrecision(), 1.0)
    tm = PRECISION_MUL.get(hdr.TranslationPrecision(), 1.0)
    for b in range(ds.MatrixBanksLength()):
        bank = ds.MatrixBanks(b)
        if bank.MatricesLength():
            m = np.array([[bank.Matrices(i).A(), bank.Matrices(i).B(), bank.Matrices(i).C(), bank.Matrices(i).D(),
                           bank.Matrices(i).Tx(), bank.Matrices(i).Ty()] for i in range(bank.MatricesLength())],
                         np.float32)
        elif bank.HalfMatricesLength():
            h = np.array([[bank.HalfMatrices(i).A(), bank.HalfMatrices(i).B(), bank.HalfMatrices(i).C(),
                           bank.HalfMatrices(i).D(), bank.HalfMatrices(i).Tx(), bank.HalfMatrices(i).Ty()]
                          for i in range(bank.HalfMatricesLength())], np.float32)
            m = np.concatenate([h[:, :4] / sm, h[:, 4:] / tm], axis=1)
        else:
            m = np.zeros((0, 6), np.float32)
        c = np.array([[bank.Colors(i).RMul(), bank.Colors(i).GMul(), bank.Colors(i).BMul(), bank.Colors(i).Alpha(),
                       bank.Colors(i).RAdd(), bank.Colors(i).GAdd(), bank.Colors(i).BAdd()]
                      for i in range(bank.ColorsLength())], np.uint8).reshape(-1, 7)
        out.banks.append(MatrixBank(m, c))

    # ---- resource chunks
    pos = hdr.ResourcesOffset()
    chunks = []
    for _ in range(6):
        (n,) = struct.unpack_from("<I", payload, pos)
        chunks.append(payload[pos + 4:pos + 4 + n])
        pos += 4 + n
    c_exports, c_text, c_shapes, c_movies, _c_mods, c_tex = chunks

    ex = ExportNames.GetRootAs(c_exports, 0)
    for i in range(ex.ObjectIdsLength()):
        out.exports[strings[ex.NameRefIds(i)]] = ex.ObjectIds(i)

    if c_text:
        from sc.flash.SC2.TextFields import TextFields  # type: ignore
        tf = TextFields.GetRootAs(c_text, 0)
        for i in range(tf.TextfieldsLength()):
            out.textfield_ids.add(tf.Textfields(i).Id())

    sh = Shapes.GetRootAs(c_shapes, 0)
    for i in range(sh.ShapesLength()):
        s = sh.Shapes(i)
        cmds = []
        for c in range(s.CommandsLength()):
            cmd = s.Commands(c)
            o, n = cmd.PointsOffset(), cmd.PointsCount()
            v = verts[o:o + n]
            xy = np.stack([v["x"], v["y"]], 1).astype(np.float32)
            uv = (np.stack([v["u"], v["v"]], 1).astype(np.float32) / 65535.0)
            cmds.append(DrawCommand(cmd.TextureIndex(), xy, uv))
        out.shapes[s.Id()] = Shape(s.Id(), cmds)

    mv = MovieClips.GetRootAs(c_movies, 0)
    for i in range(mv.MovieclipsLength()):
        m = mv.Movieclips(i)
        n_child = m.ChildrenIdsLength()
        children = [m.ChildrenIds(c) for c in range(n_child)]
        names = ([strings[m.ChildrenNameRefIds(c)] for c in range(m.ChildrenNameRefIdsLength())]
                 if m.ChildrenNameRefIdsLength() else [""] * n_child)
        blend = [m.ChildrenBlending(c) for c in range(m.ChildrenBlendingLength())] or [0] * n_child
        counts, labels = [], []
        if m.FramesLength():
            for f in range(m.FramesLength()):
                fr = m.Frames(f)
                counts.append(fr.UsedTransform())
                labels.append(strings[fr.LabelRefId()] if fr.LabelRefId() < len(strings) else "")
        else:
            for f in range(m.ShortFramesLength()):
                counts.append(m.ShortFrames(f).UsedTransform())
                labels.append("")
        off = m.FrameElementsOffset()
        frames = []
        for cnt in counts:
            els = []
            for _ in range(cnt):
                els.append(FrameElement(int(elements[off]), int(elements[off + 1]), int(elements[off + 2])))
                off += 3
            frames.append(els)
        sg = m.ScalingGridIndex()
        out.movieclips[m.Id()] = MovieClip(
            id=m.Id(), fps=m.Framerate() or 24, bank=m.MatrixBankIndex(), children=children, child_names=names,
            blend=blend, frames=frames, labels=labels,
            scaling_grid=rects[sg] if sg is not None and sg < len(rects) else None)

    tx = Textures.GetRootAs(c_tex, 0)
    for i in range(tx.TexturesLength()):
        t = tx.Textures(i).Highres()
        ext = t.ExternalTexture()
        out.textures.append(TextureRef(
            external=ext.decode() if ext else None, width=t.Width(), height=t.Height(), pixel_type=t.PixelType(),
            data=None if ext else (t.DataAsNumpy().tobytes() if t.DataLength() else None)))
    return out
