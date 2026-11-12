"""Builds the art cache  <out>/<content_sha>/  from a Source (see README.md for the layout)."""
from __future__ import annotations

import json
import os
import posixpath
import shutil
import tempfile
import time
import traceback
from concurrent.futures import ProcessPoolExecutor, as_completed
from concurrent.futures.process import BrokenProcessPool
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

from . import lzma_tables, scope as scope_mod
from .sources import Source

MANIFEST_FORMAT = 1
# assets/ subfolders decoded to text, and where they go in the cache (relative to <content_sha>/)
TABLE_DIRS = {
    "csv_logic/": "client/csv_logic/",
    "csv_client/": "client/csv_client/",
    "legacy_vfx/": "client/legacy_vfx/",   # effects.json / emitters.json: the effects tables
    "tilemaps/": "tilemaps/",
    "locations/": "locations/",            # per-arena composition, referenced by csv_logic/locations.csv
}


@dataclass
class Result:
    root: Path
    selected: int = 0
    extracted: int = 0
    skipped: int = 0
    errors: dict[str, str] = field(default_factory=dict)
    missing: list[str] = field(default_factory=list)
    seconds: float = 0.0

    @property
    def all_failed(self) -> bool:
        return bool(self.errors) and self.extracted == 0 and self.skipped == 0


def _atomic_write(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(f".{path.name}.{os.getpid()}.tmp")
    tmp.write_bytes(data)
    os.replace(tmp, path)


def _write_json(path: Path, obj) -> None:
    _atomic_write(path, (json.dumps(obj, indent=1) + "\n").encode())


def decode_tables(src: Source, root: Path, log) -> dict[str, str]:
    """Decodes every table folder into the cache; returns {rel to assets/: text} for scope selection."""
    tables: dict[str, str] = {}
    counts: dict[str, int] = {}
    for rel in src.names():
        prefix = next((p for p in TABLE_DIRS if rel.startswith(p)), None)
        if prefix is None:
            continue
        try:
            data = lzma_tables.decode(src.read(rel))
        except Exception as e:  # noqa: BLE001
            log(f"  warning: could not decode {rel}: {e}")
            continue
        _atomic_write(root / (TABLE_DIRS[prefix] + rel[len(prefix):]), data)
        tables[rel] = data.decode("utf-8", "replace")
        counts[prefix] = counts.get(prefix, 0) + 1
    log("tables: " + ", ".join(f"{TABLE_DIRS[p]} {n}" for p, n in counts.items()))
    return tables


# ---------------------------------------------------------------- worker side
_SRC: Source | None = None


def _init_worker(src: Source) -> None:
    global _SRC
    _SRC = src


def _extract_one(name: str, sc_dir: str, force: bool, tmp_root: str) -> dict:
    from . import sc2, scene  # imported in the worker

    t0 = time.time()
    try:
        with tempfile.TemporaryDirectory(dir=tmp_root) as td:
            sc_path = _SRC.local_path(f"sc/{name}", Path(td))
            f = sc2.load(sc_path)
            for ref in f.textures:
                if ref.external is None:
                    continue
                if (Path(sc_dir) / (Path(ref.external).stem + ".png")).exists() and not force:
                    continue  # scene.write keeps the existing atlas
                rel = posixpath.normpath(posixpath.join("sc", ref.external))
                if not _SRC.has(rel):
                    raise FileNotFoundError(f"texture {rel} not in the game files")
                _SRC.local_path(rel, Path(td))
            summary = scene.write(sc_path, Path(sc_dir), overwrite=force, f=f)
        summary["seconds"] = round(time.time() - t0, 2)
        return summary
    except Exception as e:  # noqa: BLE001
        return {"file": name, "error": f"{type(e).__name__}: {e}",
                "trace": traceback.format_exc(limit=3), "seconds": round(time.time() - t0, 2)}


# ---------------------------------------------------------------- parent side
def _textures_from_json(path: Path) -> list[str]:
    with open(path) as fh:
        return [t["png"] for t in json.load(fh)["textures"]]


def run(src: Source, out: Path, scope: str = "battle", workers: int | None = None, force: bool = False,
        only: list[str] | None = None, log=print) -> Result:
    t_start = time.time()
    fp = src.fingerprint()
    root = Path(out).expanduser() / fp["sha"]
    sc_dir = root / "sc"
    sc_dir.mkdir(parents=True, exist_ok=True)
    res = Result(root=root)
    log(f"game version {fp['version']}  content sha {fp['sha']}")
    log(f"source: {src.description}")
    log(f"cache:  {root}")

    tables = decode_tables(src, root, log)
    available = [r[3:] for r in src.names("sc/") if r.endswith(".sc") and r.count("/") == 1]
    selected, missing = scope_mod.select(scope, available, tables)
    if only:
        want = {n if n.endswith(".sc") else n + ".sc" for n in only}
        unknown = sorted(want - set(available))
        selected = sorted(want & set(available))
        missing = unknown
    res.selected, res.missing = len(selected), missing
    log(f"scope {scope}: {len(selected)} .sc files selected"
        + (f" (of {len(available)} in the game)" if not only else ""))
    for n in missing:
        log(f"  warning: referenced file {n} is not in the game files (skipped)")

    manifest_path = root / "manifest.json"
    old = {}
    if manifest_path.exists():
        try:
            old = json.loads(manifest_path.read_text())
        except ValueError:
            old = {}
    files: dict[str, dict] = {}
    for stem, e in (old.get("files") or {}).items():  # keep earlier entries that are still on disk
        if (root / e.get("scene", "")).exists():
            files[stem] = e

    todo = []
    for name in selected:
        stem = name[:-3]
        if (sc_dir / f"{stem}.json").exists() and not force:
            if stem not in files:
                try:
                    files[stem] = {"scene": f"sc/{stem}.json",
                                   "textures": [f"sc/{p}" for p in _textures_from_json(sc_dir / f"{stem}.json")]}
                except Exception:  # noqa: BLE001  (unreadable leftover: redo it)
                    todo.append(name)
                    continue
            res.skipped += 1
        else:
            todo.append(name)
    if res.skipped:
        log(f"{res.skipped} already extracted (use --force to redo)")

    tmp_root = root / ".tmp"
    tmp_root.mkdir(exist_ok=True)
    workers = max(1, workers or max(1, (os.cpu_count() or 4) - 2))
    sc_index = {r: loc for r, loc in src.index.items() if r.startswith("sc/")}
    worker_src = Source(sc_index, src.description)
    crashes: dict[str, int] = {}
    pending = list(todo)
    done = 0
    total = len(todo)
    while pending:
        retry = []
        with ProcessPoolExecutor(max_workers=min(workers, len(pending)), initializer=_init_worker,
                                 initargs=(worker_src,)) as pool:
            futs = {pool.submit(_extract_one, n, str(sc_dir), force, str(tmp_root)): n for n in pending}
            for fut in as_completed(futs):
                name = futs[fut]
                try:
                    r = fut.result()
                except BrokenProcessPool:
                    crashes[name] = crashes.get(name, 0) + 1
                    if crashes[name] >= 2:
                        r = {"file": name, "error": "worker process crashed (out of memory or native decoder crash)"}
                    else:
                        retry.append(name)
                        continue
                done += 1
                stem = name[:-3]
                if "error" in r:
                    res.errors[stem] = r["error"]
                    log(f"[{done}/{total}] {name}  FAILED: {r['error']}")
                else:
                    res.extracted += 1
                    files[stem] = {"scene": f"sc/{stem}.json", "textures": [f"sc/{p}" for p in r["pngs"]]}
                    log(f"[{done}/{total}] {name}  {r['textures']} tex, {r['clips']} clips, {r['seconds']}s")
        pending = retry
    shutil.rmtree(tmp_root, ignore_errors=True)

    now = datetime.now(timezone.utc).isoformat(timespec="seconds")
    manifest = {
        "format": MANIFEST_FORMAT,
        "content_sha": fp["sha"],
        "version": fp["version"],
        "source": src.description,
        "created": old.get("created", now),
        "updated": now,
        "scope": scope if not only else "only",
        "tables": sorted(set(TABLE_DIRS.values())),
        "files": dict(sorted(files.items())),
        "errors": res.errors,
        "missing": missing,
    }
    _write_json(manifest_path, manifest)
    if files:
        _write_json(Path(out).expanduser() / "current.json", {"content_sha": fp["sha"], "version": fp["version"]})
    res.seconds = time.time() - t_start
    return res
