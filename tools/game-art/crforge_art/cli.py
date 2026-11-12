"""crforge-art: build crforge's realistic-render art cache from your own Clash Royale install.

    crforge-art extract --apk base.apk split_*.apk          # APKs (or one .xapk/.apkm bundle)
    crforge-art extract --assets path/to/assets             # an already-extracted assets/ folder
    crforge-art extract --adb [SERIAL]                      # pull the APKs from a connected device
    crforge-art extract ... --audio                         # also decode the battle sounds
    crforge-art audio battle.mp4                            # mix a recording's sounds into it
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
    ex.add_argument("--audio", action="store_true",
                    help="also decode the battle sound banks (needs libvorbis)")
    ex.add_argument("--force-audio", action="store_true",
                    help="with --audio: decode every bank again (e.g. after a tool update)")
    au = sub.add_parser("audio", help="mix a recording's sound events (VIDEO.sounds.jsonl) into it")
    au.add_argument("video", help="a video written by the desktop's --record")
    au.add_argument("--out", help="output video (default: <video>_audio.mp4)")
    au.add_argument("--cache", default=DEFAULT_OUT, help=f"cache root (default {DEFAULT_OUT})")
    au.add_argument("--no-music", action="store_true", help="sound effects only")
    au.add_argument("--overtime-at", type=float, metavar="SECONDS",
                    help="switch to the sudden-death music at this video time")
    au.add_argument("--sfx-db", type=float, default=0.0, help="sound effects gain, dB (default 0)")
    au.add_argument("--music-db", type=float, default=0.0, help="music gain, dB (default 0)")
    au.add_argument("--seed", type=int, default=0, help="random seed of sample/pitch choices")
    au.add_argument("--no-normalize", action="store_true",
                    help="keep the game's absolute levels (default: normalize to about -16 LUFS)")
    au.add_argument("--clock", type=float, metavar="SECONDS_LEFT",
                    help="seconds left in regulation at the video's start (180 = battle start): adds "
                         "the game's timed sounds (60/30 s warnings, countdown, music changes)")
    au.add_argument("--location", default="PvP_royal",
                    help="locations.csv row for the start jingle / overtime music (default PvP_royal)")
    au.add_argument("--report", action="store_true",
                    help="list each recorded event and how it was resolved (exact / fallback)")
    args = ap.parse_args(argv)

    if args.cmd == "audio":
        import json as _json

        from . import audio

        root = Path(args.cache).expanduser()
        current = root / "current.json"
        if current.exists():
            root = root / _json.loads(current.read_text())["content_sha"]
        audio.mix(Path(args.video), root, Path(args.out) if args.out else None, music=not args.no_music,
                  sfx_db=args.sfx_db, music_db=args.music_db, seed=args.seed,
                  overtime_at=args.overtime_at, normalize=not args.no_normalize, report=args.report,
                  clock=args.clock, location=args.location)
        return 0

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
        if args.audio:
            from . import audio

            audio.extract(src, res.root, force=args.force_audio)
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
