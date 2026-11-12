# crforge-art: game-art cache for the realistic renderer

crforge's "realistic rendering" mode draws battles with Clash Royale's own sprites and animations.
That art belongs to Supercell and is **not** in this repository. This tool builds a local cache of it
from the files of **your own** Clash Royale installation, and the Java renderer reads that cache.

## Run it

```sh
cd tools/game-art
uv sync
uv run crforge-art extract --apk ~/cr/base.apk ~/cr/split_*.apk     # APK files
uv run crforge-art extract --apk ~/Downloads/clash-royale.xapk      # or a .xapk / .apkm bundle
uv run crforge-art extract --assets ~/cr/assets                     # or an extracted assets/ folder
uv run crforge-art extract --adb [SERIAL]                           # or pull the APKs from a device
```

You must give exactly one source:

| source | what it reads |
|---|---|
| `--apk PATH...` | APK files, read directly from the zips without unpacking them. Pass every split, because the art is in the asset pack (`split_install_time_asset_pack.apk`). For `.xapk`/`.apkm`/`.apks` bundles, the APKs inside are copied to a temp dir first. |
| `--assets DIR` | an `assets/` folder that is already extracted (the folder holding `fingerprint.json`, or its parent) |
| `--adb [SERIAL]` | runs `adb shell pm path com.supercell.clashroyale`, then `adb pull`s every split APK into a temp dir and reads them like `--apk`. Needs `adb` on PATH. |

Options:
- `--out DIR`: the cache root. Default `~/.crforge/art`.
- `--scope battle|all`: default `battle`, see below.
- `--workers N`: number of worker processes. Default is CPU count minus 2.
- `--force`: re-extract files that are already in the cache.
- `--only NAME...`: extract only the named `.sc` files, e.g. `--only chr_knight.sc`. Useful for debugging.

The run is incremental. A `.sc` file whose scene JSON already exists is skipped unless you pass `--force`. Files are processed in parallel. If one file fails, the run continues: failures are listed at the end and recorded in the manifest. The exit code is non-zero only if every file failed. Every output file is written to a temp name and then renamed into place. A file's PNGs are written before its JSON, so if the JSON exists, the entry is complete.

A full battle-scope extraction takes about 20 s on a 10-core laptop and uses about 1.2 GB. The battle scope covers about 290 `.sc` files.

## Cache layout

```
~/.crforge/art/
  current.json                 {"content_sha": ..., "version": ...} of the newest extraction
  <content_sha>/               one folder per game content version (assets/fingerprint.json "sha")
    manifest.json
    sc/<stem>.json             scene graph of sc/<stem>.sc
    sc/<texture>.png           its atlases (straight RGBA)
    client/csv_logic/...       decoded logic tables (same paths as assets/csv_logic)
    client/csv_client/...      decoded client tables (health bars, ...)
    client/legacy_vfx/         effects.json + emitters.json: the effects tables
    locations/...              per-arena composition (level_*.sc + export + object placements);
                               csv_logic/locations.csv "FileName" points here
    tilemaps/...               decoded tilemaps (locations.csv "TileDataFileName")
```

`manifest.json`:

```json
{"format": 1, "content_sha": "...", "version": "16.635.1", "source": "APK files: base.apk, ...",
 "created": "<ISO time>", "updated": "<ISO time>", "scope": "battle",
 "tables": ["client/csv_client/", "client/csv_logic/", "client/legacy_vfx/", "locations/", "tilemaps/"],
 "files": {"chr_knight": {"scene": "sc/chr_knight.json", "textures": ["sc/chr_knight_0.png"]}, ...},
 "errors": {"<stem>": "<message>"}, "missing": ["<referenced .sc not in the game files>"]}
```

All paths in the manifest are relative to the `<content_sha>/` folder. Table references inside the game data, such as `"sc/chr_knight.sc"`, `locations/bone_arena.csv` or `tilemaps/tilemap.csv`, resolve as `sc/<stem>.json`, `locations/...` and `tilemaps/...` in the cache.

The scene JSON format is documented in the docstring of [`crforge_art/scene.py`](crforge_art/scene.py). Each `.sc` file is translated 1:1 into exports, shapes (triangle strips with UVs), movie clips (children, per-frame matrix and color indices, labels, blend modes) and matrix/color banks. Nothing is pre-rendered. The game stores its atlases with premultiplied alpha. The PNGs are converted to **straight** alpha when written, as the format specifies.

## Scope

`battle` (the default) extracts:
- every `.sc` file referenced by the battle tables:
  - `characters*`, `characters/*`, `buildings*`, `projectiles*`, `area_effect_objects*`, `spells_*`, `character_buffs*`, `locations.csv`, `arenas*`, every `locations/*` file and `legacy_vfx/effects.json`;
  - the health bar tables, and the battle entries of `client_globals.csv`;
  - card-icon, menu and prestige columns are ignored;
- plus all `chr_*`, `building_*`, `level_*`, `arena_*` and `effects*` files;
- plus `ui.sc` and the in-battle UI files: `ui_spells`, `ui_ability_button`, `ui_spectate_hud` and `ui_battle*`;
- minus cosmetic-only files: `*prestige*`, tower skins, emotes, chests, shop and battle pass.

`all` extracts every `.sc` file in the game. If a referenced file is missing, it is reported as a warning. This is normal, because some arenas ship as later downloads that are not in the APK.

## Sound

```sh
uv run crforge-art extract --apk ... --audio [--force-audio]   # decode the battle sound banks
uv run crforge-art audio battle.mp4 [--clock 180] [--overtime-at S] [--report]
```

`--audio` decodes the battle sound banks (`card_*`, `ui_battle_*`, the general battle music, the arena jingles, `ui_menu_general`, `uncategorized`, `emoji`, `ui_hero_form`) into `<content_sha>/audio/<bank>/<sample>.ogg`. It also reads the banks' FMOD Studio metadata and resolves every event into `audio/index.json`. The metadata gives the samples each event plays (and the weight of each one in a random pick), the volume (instrument, audio track, event master and mixer buses, in dB), the pitch, the random volume and pitch modulators, the trigger delay and probability, the max instances and the cooldown. The format notes are in [`crforge_art/fmod.py`](crforge_art/fmod.py). The index layout is in the docstring of [`crforge_art/audio.py`](crforge_art/audio.py). Event paths come from `clashroyale.strings.bank` and the mixer bus volumes from `clashroyale.bank`. Older caches (index without `"format": 2`) are redone on the next `--audio` run.

`audio VIDEO` mixes `VIDEO.sounds.jsonl` (written by the desktop recorder) into `VIDEO_audio.mp4`. Each `soundbanks/<bank>/<event>` reference is looked up as `<bank>/<event>` in the events table. A reference that isn't in the table falls back to a guess from the sample names. Instances follow the event's max-instances limit (the oldest one is cut) and its cooldown. On top of that, one event can't restart within 30 ms, so a volley plays once. Music events (banks `music_arena_*`) switch the looping battle music. Without them, `music_arena_general_battle` loops from 0. `--clock SECONDS_LEFT` adds the sounds the game times itself, from `client_globals.csv` and `music.csv`: the 60 s / 30 s warnings, the 10..1 countdown, BattleMusic → 60sec_loop → 30sec_loop, and the location's start jingle. `--overtime-at` adds the sudden-death music and the extra-time jingle. The mix is normalized to about -16 LUFS and peak-limited at -1 dBFS (`--no-normalize` keeps the game's absolute levels). `--report` lists how each recorded event was resolved.

Known gaps: parameter sheets play their first instrument, nested events only their first layer, and snapshots, VCAs, effects and 3D panning are ignored. The stealing mode is inferred: flags 0 drop the new instance, any other value cuts the oldest.

## Tests

```sh
uv run pytest -q
```

The tests use the real game APKs and are skipped when no APKs are found. Set `CRFORGE_ART_APK_DIR` to a folder holding `base.apk` and the `split_*.apk` files.

## Legal

Clash Royale and all of its art, animation and data are the property of Supercell. This tool contains no game data. It only reads the files of an installation that you own and writes them into a private local cache, for your personal, non-commercial use under [Supercell's Fan Content Policy](https://supercell.com/en/fan-content-policy/). **Never commit, upload or redistribute the cache or any file extracted from it.** That includes screenshots or recordings made for anything other than fan content allowed by that policy. This folder's `.gitignore` excludes images and game files as a safeguard. Keep the cache outside the repository; the default is `~/.crforge/art`.

The SC2/SCTX file format knowledge comes from the MIT-licensed [sc-workshop](https://github.com/sc-workshop) schemas. See `crforge_art/schemas/LICENSE-sc-workshop.txt`.
