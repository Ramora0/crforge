"""Decode Clash Royale logic data from the user's own installed APK.

The asset pack's csv_logic/ files are LZMA-compressed (Supercell's variant of
the .lzma "alone" header with a 4-byte size field). This is plain compression,
not encryption. Output stays under game_assets/ (git-ignored; Supercell IP).

    uv run python -m crharness.gamedata game_assets/pack/assets/csv_logic game_assets/decoded
"""
from __future__ import annotations

import lzma
import sys
from pathlib import Path


def decode(raw: bytes) -> bytes:
    if raw[:1] != b"\x5d":
        return raw  # already plain
    props, size4, body = raw[:5], raw[5:9], raw[9:]
    hdr = props + size4 + b"\x00\x00\x00\x00"  # widen the size field to the standard 8 bytes
    try:
        return lzma.decompress(hdr + body, format=lzma.FORMAT_ALONE)
    except lzma.LZMAError:
        # some writers put a wrong size; use an "unknown size" header and trim at end-of-stream
        d = lzma.LZMADecompressor(format=lzma.FORMAT_ALONE)
        return d.decompress(props + b"\xff" * 8 + body)


def main(src: str, dst: str) -> None:
    src_p, dst_p = Path(src), Path(dst)
    ok = fail = 0
    for f in sorted(src_p.rglob("*")):
        if not f.is_file():
            continue
        out = dst_p / f.relative_to(src_p)
        out.parent.mkdir(parents=True, exist_ok=True)
        try:
            data = decode(f.read_bytes())
            data.decode("utf-8")  # sanity: logic tables are text
            out.write_bytes(data)
            ok += 1
        except Exception as e:  # noqa: BLE001
            print(f"skip {f.relative_to(src_p)}: {e}")
            fail += 1
    print(f"decoded {ok} files, {fail} skipped -> {dst_p}")


if __name__ == "__main__":
    main(*sys.argv[1:3])
