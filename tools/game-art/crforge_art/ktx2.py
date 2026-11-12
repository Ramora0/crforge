"""KTX2 texture reader (the sc3d .ktx files) -> RGBA numpy array of mip level 0.

Layout (Khronos KTX 2.0): 12-byte identifier | vkFormat, typeSize, width, height, depth, layers,
faces, levels, supercompression (u32 each) | dfd/kvd offsets+lengths (u32) | sgd offset+length (u64)
| level index: (offset, length, uncompressed length) u64 per level.

Clash Royale ships only ASTC LDR blocks (vkFormat 157..184), no supercompression, with
``KTXorientation = "rd"`` (rows top to bottom), i.e. glTF's texture orientation.
Uncompressed RGBA8/BGRA8 is handled too.
"""
from __future__ import annotations

import struct

import numpy as np
import texture2ddecoder

IDENT = b"\xabKTX 20\xbb\r\n\x1a\n"
# VK_FORMAT_ASTC_*_BLOCK: 157 + 2*i (UNORM) / +1 (SRGB)
_ASTC = [(4, 4), (5, 4), (5, 5), (6, 5), (6, 6), (8, 5), (8, 6), (8, 8), (10, 5), (10, 6), (10, 8),
         (10, 10), (12, 10), (12, 12)]
_RGBA8 = range(37, 44)    # VK_FORMAT_R8G8B8A8_*
_BGRA8 = range(44, 51)    # VK_FORMAT_B8G8R8A8_*


def header(data: bytes) -> dict:
    if data[:12] != IDENT:
        raise ValueError("not a KTX2 file")
    vk, type_size, w, h, depth, layers, faces, levels, scheme = struct.unpack_from("<9I", data, 12)
    kvd_off, kvd_len = struct.unpack_from("<II", data, 56)
    lv = [struct.unpack_from("<3Q", data, 80 + 24 * i) for i in range(max(1, levels))]
    kv = {}
    p, end = kvd_off, kvd_off + kvd_len
    while p + 4 <= end:
        (n,) = struct.unpack_from("<I", data, p)
        entry = data[p + 4:p + 4 + n]
        k, _, v = entry.partition(b"\x00")
        kv[k.decode("utf-8", "replace")] = v.rstrip(b"\x00").decode("utf-8", "replace")
        p += 4 + n + (-n % 4)
    return {"vkFormat": vk, "width": w, "height": h, "depth": depth, "layers": layers, "faces": faces,
            "levels": levels, "supercompression": scheme, "level_index": lv, "kv": kv,
            "srgb": vk in range(158, 185, 2) or vk in (43, 50)}


def astc_block(vk: int):
    i = vk - 157
    return _ASTC[i // 2] if 0 <= i < 2 * len(_ASTC) else None


def decode(data: bytes) -> np.ndarray:
    """RGBA uint8 array (h, w, 4) of mip level 0, rows top to bottom."""
    hd = header(data)
    if hd["supercompression"] != 0:
        raise NotImplementedError(f"KTX2 supercompression {hd['supercompression']} not supported")
    w, h = hd["width"], hd["height"]
    off, length, _ = hd["level_index"][0]
    payload = data[off:off + length]
    vk = hd["vkFormat"]
    blk = astc_block(vk)
    if blk is not None:
        bx, by = blk
        need = ((w + bx - 1) // bx) * ((h + by - 1) // by) * 16
        if len(payload) < need:
            raise ValueError(f"ASTC level 0 too short: {len(payload)} < {need}")
        bgra = texture2ddecoder.decode_astc(payload[:need], w, h, bx, by)
        img = np.frombuffer(bgra, np.uint8).reshape(h, w, 4)[:, :, [2, 1, 0, 3]]
    elif vk in _RGBA8:
        img = np.frombuffer(payload[:w * h * 4], np.uint8).reshape(h, w, 4)
    elif vk in _BGRA8:
        img = np.frombuffer(payload[:w * h * 4], np.uint8).reshape(h, w, 4)[:, :, [2, 1, 0, 3]]
    else:
        raise NotImplementedError(f"KTX2 vkFormat {vk} not supported")
    if hd["kv"].get("KTXorientation", "rd").startswith("ru"):
        img = img[::-1]
    return np.ascontiguousarray(img)
