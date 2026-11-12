"""crforge-art: build crforge's realistic-render art cache from your own Clash Royale install.

    crforge-art extract --apk base.apk split_*.apk          # APKs (or one .xapk/.apkm bundle)
    crforge-art extract --assets path/to/assets             # an already-extracted assets/ folder
    crforge-art extract --adb [SERIAL]                      # pull the APKs from a connected device
"""
from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

DEFAULT_OUT = "~/.crforge/art"


def _du(path: Path) -> int:
    return sum(p.stat().st_size for p in path.rglob("*") if p.is_file())


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="crforge-art", description=__doc__.split("\n")[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    ex = sub.add_parser("extract", help="extract game art into the local cache")
    srcg = ex.add_mutually_exclusive_group(required=True)
    srcg.add_argument("--adb", nargs="?", const="", metavar="SERIAL",
                      help="pull the installed game's APKs over adb (optional device serial)")
    srcg.add_argument("--apk", nargs="+", metavar="PATH", help="APK files and/or .xapk/.apkm bundles")
    srcg.add_argument("--assets", metavar="DIR", help="an already-extracted assets/ folder")
    ex.add_argument("--out", default=DEFAULT_OUT, help=f"cache root (default {DEFAULT_OUT})")
    ex.add_argument("--scope", choices=["battle", "all"], default="battle",
                    help="battle: what a battle draws (default); all: every .sc file")
    ex.add_argument("--workers", type=int, default=max(1, (os.cpu_count() or 4) - 2),
                    help="parallel worker processes (default: cpu count - 2)")
    ex.add_argument("--force", action="store_true", help="re-extract files already in the cache")
    ex.add_argument("--only", nargs="+", metavar="NAME", help="extract just these .sc files (e.g. chr_knight.sc)")
    args = ap.parse_args(argv)

    from . import extract, sources

    if args.adb is not None:
        src = sources.from_adb(args.adb or None)
    elif args.apk:
        src = sources.from_apks(args.apk)
    else:
        src = sources.from_assets_dir(args.assets)
    try:
        res = extract.run(src, Path(args.out), scope=args.scope, workers=args.workers, force=args.force,
                          only=args.only)
    finally:
        src.close()

    print()
    print(f"done in {res.seconds:.1f}s: {res.extracted} extracted, {res.skipped} already present, "
          f"{len(res.errors)} failed, {len(res.missing)} referenced files missing")
    if res.errors:
        print("failures:")
        for stem, err in sorted(res.errors.items()):
            print(f"  {stem}: {err}")
    print(f"cache: {res.root} ({_du(res.root) / 1e6:.0f} MB)")
    return 1 if res.all_failed else 0


if __name__ == "__main__":
    sys.exit(main())
