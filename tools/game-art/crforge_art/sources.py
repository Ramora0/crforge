"""Where the game files come from: an extracted assets/ folder, APK files, or an APK bundle.

Every source is reduced to an index ``{rel: (container, member)}`` where ``rel`` is the path
relative to the game's ``assets/`` folder (``"sc/chr_knight.sc"``):
  * folder source: ``container`` is None and ``member`` is the absolute file path;
  * APK source:    ``container`` is the .apk path and ``member`` the zip member (``"assets/sc/..."``).
The index is plain data, so it can be handed to worker processes; members are streamed out of
the zips on demand (nothing is unzipped wholesale).
"""
from __future__ import annotations

import json
import shutil
import subprocess
import tempfile
import zipfile
from dataclasses import dataclass, field
from pathlib import Path

PACKAGE = "com.supercell.clashroyale"
BUNDLE_SUFFIXES = {".xapk", ".apkm", ".apks", ".zip"}

_ZIPS: dict[str, zipfile.ZipFile] = {}  # per-process cache of open APKs


def _zip(path: str) -> zipfile.ZipFile:
    z = _ZIPS.get(path)
    if z is None:
        z = _ZIPS[path] = zipfile.ZipFile(path)
    return z


@dataclass
class Source:
    index: dict[str, tuple[str | None, str]]
    description: str
    _tmp: list[tempfile.TemporaryDirectory] = field(default_factory=list, repr=False, compare=False)

    def __getstate__(self):  # temp dirs stay with the parent process
        return {"index": self.index, "description": self.description, "_tmp": []}

    def has(self, rel: str) -> bool:
        return rel in self.index

    def names(self, prefix: str = "") -> list[str]:
        return sorted(r for r in self.index if r.startswith(prefix))

    def read(self, rel: str) -> bytes:
        container, member = self.index[rel]
        if container is None:
            return Path(member).read_bytes()
        return _zip(container).read(member)

    def local_path(self, rel: str, tmp_dir: Path) -> Path:
        """A filesystem path holding the file: the original for folder sources, else a copy in tmp_dir."""
        container, member = self.index[rel]
        if container is None:
            return Path(member)
        dst = tmp_dir / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        with _zip(container).open(member) as src, open(dst, "wb") as out:
            shutil.copyfileobj(src, out, 1 << 20)
        return dst

    def fingerprint(self) -> dict:
        if not self.has("fingerprint.json"):
            raise SystemExit("assets/fingerprint.json not found in the source - is this a Clash Royale install?")
        fp = json.loads(self.read("fingerprint.json"))
        if not fp.get("sha"):
            raise SystemExit("assets/fingerprint.json has no 'sha'")
        return {"sha": fp["sha"], "version": fp.get("version", "unknown")}

    def close(self) -> None:
        for z in list(_ZIPS.values()):
            z.close()
        _ZIPS.clear()
        for t in self._tmp:
            t.cleanup()
        self._tmp.clear()


def from_assets_dir(path: str | Path) -> Source:
    root = Path(path).expanduser().resolve()
    if not (root / "fingerprint.json").exists() and (root / "assets" / "fingerprint.json").exists():
        root = root / "assets"
    if not root.is_dir():
        raise SystemExit(f"{path}: not a directory")
    index = {p.relative_to(root).as_posix(): (None, str(p)) for p in root.rglob("*") if p.is_file()}
    return Source(index, "extracted assets folder")


def from_apks(paths: list[str | Path]) -> Source:
    """APK files and/or bundles (.xapk/.apkm/.apks: zips of APKs). Earlier APKs win on duplicate assets."""
    index: dict[str, tuple[str | None, str]] = {}
    tmps: list[tempfile.TemporaryDirectory] = []
    names: list[str] = []
    apks: list[Path] = []
    for p in map(lambda x: Path(x).expanduser().resolve(), paths):
        if not p.is_file():
            raise SystemExit(f"{p}: no such file")
        if p.suffix.lower() in BUNDLE_SUFFIXES or (p.suffix.lower() != ".apk" and _is_bundle(p)):
            t = tempfile.TemporaryDirectory(prefix="crforge-art-bundle-")
            tmps.append(t)
            apks += _unpack_bundle(p, Path(t.name))
            names.append(p.suffix.lower().lstrip(".") + " bundle")
        else:
            apks.append(p)
            names.append(p.name)
    for apk in apks:
        try:
            z = zipfile.ZipFile(apk)
        except zipfile.BadZipFile as e:
            raise SystemExit(f"{apk.name}: not a zip/APK ({e})")
        with z:
            for info in z.infolist():
                if info.filename.startswith("assets/") and not info.is_dir():
                    index.setdefault(info.filename[len("assets/"):], (str(apk), info.filename))
    return Source(index, "APK files: " + ", ".join(sorted(set(names))), tmps)


def _is_bundle(p: Path) -> bool:
    try:
        with zipfile.ZipFile(p) as z:
            return any(n.lower().endswith(".apk") for n in z.namelist())
    except zipfile.BadZipFile:
        return False


def _unpack_bundle(bundle: Path, dst: Path) -> list[Path]:
    """Copies the APKs inside a bundle to dst (only the inner .apk members, streamed)."""
    out = []
    with zipfile.ZipFile(bundle) as z:
        for info in z.infolist():
            if info.filename.lower().endswith(".apk") and not info.is_dir():
                target = dst / Path(info.filename).name
                with z.open(info) as src, open(target, "wb") as f:
                    shutil.copyfileobj(src, f, 1 << 20)
                out.append(target)
    if not out:
        raise SystemExit(f"{bundle.name}: bundle contains no .apk files")
    # the base APK first, then asset packs/splits (order only matters for duplicate assets)
    return sorted(out, key=lambda p: (p.name != "base.apk", p.name))


def from_adb(serial: str | None) -> Source:
    """Pulls every split APK of the installed game from a connected device (adb must be on PATH)."""
    if shutil.which("adb") is None:
        raise SystemExit("adb not found on PATH")
    base = ["adb"] + (["-s", serial] if serial else [])
    r = subprocess.run(base + ["shell", "pm", "path", PACKAGE], capture_output=True, text=True)
    if r.returncode != 0:
        raise SystemExit(f"adb shell pm path failed: {(r.stderr or r.stdout).strip()}")
    remote = [ln.strip()[len("package:"):] for ln in r.stdout.splitlines() if ln.strip().startswith("package:")]
    if not remote:
        raise SystemExit(f"{PACKAGE} is not installed on the device")
    t = tempfile.TemporaryDirectory(prefix="crforge-art-adb-")
    local = []
    for rp in remote:
        dst = Path(t.name) / Path(rp).name
        print(f"adb pull {Path(rp).name}")
        p = subprocess.run(base + ["pull", rp, str(dst)], capture_output=True, text=True)
        if p.returncode != 0:
            t.cleanup()
            raise SystemExit(f"adb pull {rp} failed: {(p.stderr or p.stdout).strip()}")
        local.append(dst)
    src = from_apks(sorted(local, key=lambda p: (p.name != "base.apk", p.name)))
    src.description = f"adb device ({len(local)} APKs)"
    src._tmp.append(t)
    return src
