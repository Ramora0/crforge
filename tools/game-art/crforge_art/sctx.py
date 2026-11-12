"""SCTX texture reader -> RGBA numpy array.

Layout:  u32 n | Header flatbuffer ("SCTX") | u32 m | levels: (u32 k | MipMap flatbuffer)*
         | [pad to 16 if use_padding] | zstd(texture data) or raw
Pixel data is ASTC (decoded with texture2ddecoder) or raw RGBA/BGRA.
"""
from __future__ import annotations

import struct
from pathlib import Path

import numpy as np
import texture2ddecoder
import zstandard

from . import _GEN  # noqa: F401
from sc.texture.SCTX.Header import Header  # type: ignore
from sc.texture.SCTX.MipMap import MipMap  # type: ignore

_ASTC_BLOCKS = [(4, 4), (5, 4), (5, 5), (6, 5), (6, 6)] + [None] + \
               [(8, 5), (8, 6), (8, 8), (10, 5), (10, 6), (10, 8), (10, 10), (12, 10), (12, 12)]


def astc_blocks(pixel_type: int):
    for base in (186, 204):  # sRGB / linear ranges share the same block order
        i = pixel_type - base
        if 0 <= i < len(_ASTC_BLOCKS) and _ASTC_BLOCKS[i] is not None:
            return _ASTC_BLOCKS[i]
    return None


def load(path: str | Path, level: int = 0) -> np.ndarray:
    raw = Path(path).read_bytes()
    (n,) = struct.unpack_from("<I", raw, 0)
    hdr = Header.GetRootAs(raw[4:4 + n], 0)
    pos = 4 + n
    ptype, flags, tlen = hdr.PixelType(), hdr.Flags(), hdr.TextureLength()
    (m,) = struct.unpack_from("<I", raw, pos)
    pos += 4
    data_start = pos + m
    levels = []
    for _ in range(hdr.LevelsCount()):
        (k,) = struct.unpack_from("<I", raw, pos)
        mm = MipMap.GetRootAs(raw[pos + 4:pos + 4 + k], 0)
        levels.append((mm.Width(), mm.Height(), mm.Offset()))
        pos += 4 + k
    pos = data_start
    if flags & 8:  # use_padding
        pos = (pos + 15) & ~15
    blob = raw[pos:]
    if blob[:4] == b"\x28\xb5\x2f\xfd":
        data = zstandard.ZstdDecompressor().decompressobj().decompress(blob)
    else:
        data = blob[:tlen]
    w, h, off = levels[level] if levels else (hdr.Width(), hdr.Height(), 0)
    data = data[off:]

    blocks = astc_blocks(ptype)
    if blocks is not None:
        bx, by = blocks
        nbytes = ((w + bx - 1) // bx) * ((h + by - 1) // by) * 16
        bgra = texture2ddecoder.decode_astc(data[:nbytes], w, h, bx, by)
        img = np.frombuffer(bgra, np.uint8).reshape(h, w, 4)[:, :, [2, 1, 0, 3]]
    elif ptype in (70, 71):
        img = np.frombuffer(data[:w * h * 4], np.uint8).reshape(h, w, 4)
    elif ptype in (80, 81):
        img = np.frombuffer(data[:w * h * 4], np.uint8).reshape(h, w, 4)[:, :, [2, 1, 0, 3]]
    elif ptype == 1:  # A8
        a = np.frombuffer(data[:w * h], np.uint8).reshape(h, w)
        img = np.dstack([np.full_like(a, 255)] * 3 + [a])
    else:
        raise NotImplementedError(f"{path}: pixel type {ptype} not supported")
    return np.ascontiguousarray(img)


def info(path: str | Path) -> dict:
    raw = Path(path).read_bytes()
    (n,) = struct.unpack_from("<I", raw, 0)
    hdr = Header.GetRootAs(raw[4:4 + n], 0)
    return {"pixel_type": hdr.PixelType(), "w": hdr.Width(), "h": hdr.Height(), "levels": hdr.LevelsCount(),
            "flags": hdr.Flags(), "texture_length": hdr.TextureLength()}
