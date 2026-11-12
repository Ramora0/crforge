# Realistic rendering

crforge's desktop module can draw a battle with the game's own art, sounds and HUD in place of the debug visualizer's shapes. The art is never part of this repository: it is extracted from the user's own Clash Royale installation into a local cache, the way the game tables are built from the game's files (see [Game tables](game-tables.md)).

<p align="center"><i>Arena, towers, troops, shadows, effects and HUD below are the game's own movie clips, played by the simulator.</i></p>

## Using it

1. **Build the art cache** from an installation (once per game version). The tool lives in `tools/game-art` (Python, [uv](https://docs.astral.sh/uv/)):

   ```bash
   cd tools/game-art
   uv run crforge-art extract --adb                        # a phone with the game, over adb
   uv run crforge-art extract --apk base.apk split_*.apk   # or the APKs (or an .xapk/.apkm bundle)
   uv run crforge-art extract --assets path/to/assets      # or an already-extracted assets/ folder
   # add --audio to also decode the battle sounds (needs libvorbis: brew install libvorbis)
   ```

   It writes `~/.crforge/art/<content sha>/` (about 1.3 GB): each SC2 file's scene graph as JSON and its atlases as PNG, the client tables, the arena location files and, with `--audio`, the sounds. Nothing is pre-rendered: the desktop plays the clips itself. `tools/game-art/README.md` has the details and the legal note: the art is Supercell's, for personal use under its Fan Content Policy, and must never be committed or redistributed.

2. **In the visualizer**, press **X** (or start it with `--args="--realistic"`) to swap the debug shapes for the game's art. The debug overlays and inspector keep working on top.

3. **Record a video** of a replay, offscreen, framed like a phone (1080x2340, 60 fps, HUD included):

   ```bash
   ./gradlew :desktop:run --args="--replay examples/replays/ladder.json --record battle.mp4"
   #   [--size 1080x2340] [--fps 60] [--from SECONDS] [--seconds N] [--bottom 0|1] [--location PvP_royal]
   #   a .png target writes one still at --from
   cd tools/game-art && uv run crforge-art audio ../../battle.mp4   # adds the game's sounds and music
   ```

   Positions are interpolated between the battle's 50 ms ticks, so the video plays smoothly at 60 fps.

## How it is drawn

`desktop/src/main/java/org/crforge/desktop/render/art`:

| Class | Role |
|---|---|
| `ArtCache`, `ScScene` | find the cache; read a scene JSON (shapes, movie clips, matrix banks) |
| `ScDrawer` | play a movie clip at a frame: each child at its matrix and colour transform (multiply and add), each shape as textured triangle strips, the clip's blend modes |
| `ArtProjection` | the arena art's anisotropic projection (§3) |
| `ArenaScene` | the arena from its location file: background plus decorations (§4) |
| `RealisticRenderer` | every troop, building, tower and projectile: animation, facing, shadows, deploy filter (§1, 2, 5, 6, 8) |
| `EffectLayer`, `EffectDefs`, `EffectDrawer` | the effects table's effects with a particle simulator, tower ruins, and the sounds the effects start (§7) |
| `BattleHud`, `HudArt`, `HudFonts` | health bars, tower bars, hands, elixir, timer and crowns from the game's UI art |
| `CinematicRecorder` | `--record`: the offscreen video and its sound events |
| `MatchEnd` | crown sounds, the freeze at the match's end and the replay end screen (§9) |
| `RealisticView` | the visualizer's X mode |

The rest of this document is the evidence behind the rules those classes follow.

# What to draw and where

This spec covers the realistic-render mode, which draws each simulated entity with the game's own SC2 movie clips. For every entity it says which export to play, at which frame, where to put it, how big to draw it and in what order. Each rule carries a confidence label:

- **DATA**: read directly from a game table or an `.sc` file.
- **INFERRED**: a pattern that holds across many units, or that a column name implies.
- **GUESS**: no evidence yet. The rule says what would confirm it.

Sources (paths in the game's assets, as the art cache keeps them):

| Name | Path |
|---|---|
| sc | `assets/sc/*.sc`, read with `tools/game-art` `crforge_art.sc2` |
| tables | the game tables the simulator runs (`~/.crforge/tables/<version>/*.json`) |
| toml | `assets/csv_logic`, decoded into the art cache's `client/csv_logic` |
| vfx | `assets/legacy_vfx/effects.json` and `emitters.json` (the cache's `client/legacy_vfx`) |

Display units (**du**) are the SC2 coordinate units. Clip matrices and shapes are in du, and 1 du is one pixel at 1x.

---

## 1. Troop animations

### 1.1 Export names

**Rule.** For a row and a team colour, the base name is `BlueExportName` when the entity's side is drawn blue (`ViewOrientation.blue(side)`), else `RedExportName`. The clip for an action, direction and team is:

```
<base>_<action><n>_<dir>      e.g. Knight_attack1_3, chr_musketeer_blue_run1_9, hog_rider_red_idle1_5
```

`<action><n>` is the action name plus a variant number, for example `attack1`, `attack2` or `run1`. `<dir>` is 1..N (see §2). Look it up in the sc file named by the row's `FileName` column. When `<base>_<action>1_<dir>` is missing, fall back in this order:

1. `idle1`.
2. `<base>` itself, used as a single clip with no action or direction. Buildings such as `building_cannon`, `tesla1_blue` and `KingTower_blue` work this way.

**Evidence.**

- The 613 character and building rows that have a `FileName` all resolve to an existing `.sc` file.
- `CustomAnimationPostfix` values are `_ability1_`, `_landing1_` and `_attack2_`. They show that the client builds the name as `base + "_" + action + "_" + dir`.
- Blue and red bases differ per unit and are not derivable:

  | Unit | Blue base | Red base |
  |---|---|---|
  | Knight | `Knight` | `Knight_enemy` |
  | Archers | `archer1` | `archer1_enemy` |
  | Musketeer | `chr_musketeer_blue` | `chr_musketeer` |
  | Giant | `giant1` | `giant1_enemy` |
  | Hog Rider | `hog_rider` | `hog_rider_red` |
  | Skeleton | `skeleton1` | `skeleton1` |

  Always read both columns.
- Warning: a blue base can be a prefix of the red one (`Knight`, `Knight_enemy`). Match exports by the exact pattern `^<base>_<action>\d*_\d+$`, not by `startswith`.

**Confidence.** DATA.

**Hero rows (INFERRED).** A hero row (`TombstoneHero`, `KnightHero`, `TombstoneHeroSkeleton`, …) has an empty `FileName` and export names: the game draws it with the 3D model its `PrefabAsset` names (`sc3d/...`), which the 2D art cache does not hold (`crforge-art sc3d` now extracts these models as glTF, §10). Such a row, when it has a `PrefabAsset` and a `Base` (`BUILDING.Tombstone`, `CHARACTER.Skeleton`), is drawn with its `Base` row's SC2 art, followed recursively. A row without a `Base` stays undrawn: the Hero Tombstone's `TombstoneHero_visual_dummy`, `TombstoneHero_Monster_Passive` and its Skeleton Queen (`TombstoneHero_Monster_Active`) have no SC2 art at all. The hero deploy effects themselves (`vfx_tombstone_hero_deploy`, `vfx_megaminion_hero_deploy`) are SC2 and are drawn as the data gives them: tall pillars (about 250 du, 8 tiles) and a dark navy back column (`vmc_megaminion_hero_deploy_backglow`, colour multiplier 0,13,51) for about a second.

### 1.2 Which actions exist

These counts are over the rows' blue exports. Variants of one card (`_crazy_*`, `_chaos_*`, `_Chess`) are counted separately.

| Action | Rows | Typical frames (median) | fps | Labels (frame index, 0-based) |
|---|---|---|---|---|
| `idle1` | 469 | 29 (102 rows have a 1-frame idle) | 24 / 30 / 60 (103 / 243 / 123) | usually none; some `action_frame1/2` |
| `attack1` | 460 | 30 | same mix | `action_frame`: the hit or launch moment; ~4000 clips |
| `run1` | 451 | 28 | same mix | `action_frame1`, `action_frame2`: the two footfalls |
| `attack2`, `attack3`, `attack4` | 37 / 8 / 1 | 24 | 30 / 60 | `action_frame` |
| `deploy1` | 21 | 38 | | `action_frame` or `action_frame1/2` |
| `charge1` | 18 | 16 | | `action_frame1/2` (Prince, Dark Prince, Battle Ram, Ram Rider) |
| `ability1` | 18 | 23 | | `action_frame` (champions, Monk, Golden Knight, Archer Queen…) |
| `jump1` | 16 | 85 | | `charge_start/end`, `jump_start/end`, `recover_start/end` (Mega Knight, Royal Hogs) |
| `dash1` to `dash4` | 10 | 27 | | `action_frame` (Bandit, Golden Knight, Guardienne) |
| `attacking1` | 6 | 62 | | `action_frame`, `action_frame_end` (Executioner: axe out and back) |
| `loading1` | 6 | 96 | | `loop_start/end`, `throw_*`, `pull_*` (Fisherman) |
| `reborn1` | 7 | | | Phoenix egg hatch |
| `die1`, `falling1` | 4 | | | Suspicious Bush only |
| `idle2`, `run2`, `idlebreak1` | 1–2 | | | Chef, Monk |

There are no general `death` clips. A death is an effect (`DeathEffect`, §7). Deploy clips are rare, and the usual deploy look comes from `DeployBaseAnimExportName` (§8).

The fps value is per clip (`MovieClip.fps`). All directions of one action share the frame count and label frames, with 31 exceptions out of 1584 action sets. Blue and red share timing, with 41 exceptions out of 1559.

Examples, using direction 1 of the blue export:

| Unit | idle1 | run1 | attack1 | HitSpeed / LoadTime |
|---|---|---|---|---|
| Knight (`chr_knight.sc`, Scale 98) | 1 f @30 | 26 f @30, steps at 0 and 13 | 35 f @30, `action_frame` 22 (733 ms) | 1200 / 700 |
| Archer (`chr_archer.sc`, 87) | 1 @24 | 16 @24, steps 5 and 13 | 24 @24, af 14 (583 ms) | 900 / 400 |
| Musketeer (`chr_musketeer.sc`, 100) | 1 @30 | 28 @30 | 30 @30, af 14 (467 ms) | 1000 / 300 |
| Giant (`chr_giant.sc`, 100) | 35 @30 | 30 @30 | 28 @30, af 13 (433 ms) | 1500 / 1000 |
| Hog Rider (`chr_hog_rider.sc`, 95) | 1 @24 | 17 @24, step 15 | 20 @24, af 12 (500 ms) | 1600 / 1000 |
| Tower Princess (`chr_princess.sc`) | 30 @24 | 18 @24 | 40 @24, af 26 (1083 ms) | 1200 / 1000 |
| Minion (`chr_minion.sc`, 85, flying) | 16 @24 | 16 @24 | 14 @24, af 10 | 1200 / 700 |

**Confidence.** DATA.

### 1.3 Simulator state to action

`EntityView.state` is `GridEntityState` (`core/.../pathfinding/GridEntityState.java`).

| state | name | Draw |
|---|---|---|
| 0 | STANDING | `idle1`, looped |
| 1, 6, 7, 15 | MOVING, SPAWN_PATHFIND, INGAME_PATHFIND, ROUTE_FOLLOWING_ALTERNATE | `run1`, looped at native fps. If `speed == 0` for the tick, draw `idle1`, unless the row has `DontStopMoveAnim` (Golem, Ice Golem): keep `run1` |
| 2 | ATTACKING | `attack<k>`, timed to the hit (§1.4). Between hits, after the clip ends: `idle1`. If `AnimationsKeepLastFrame` is set: hold the last attack frame |
| 3 | DASHING | a leap (row `JumpHeight` > 0 and a `jump1` clip: Mega Knight, its evolution): `jump1` timed to the dash (§1.6); else `dash1` (chained dashes take `dash1`…`dash4` in turn), else `charge1`, else `run1` |
| 4, 11, 8 | DEPLOYING, WAITING_TO_DEPLOY, CLONE_SETUP | `deploy1` if present, stretched over `DeployTime` (row `DeployAnimationOverride` names another action, e.g. `_reborn1_`); else `idle1`, with the deploy base animation under it (§8). WAITING_TO_DEPLOY units are not visible yet in the game (GUESS: draw nothing until DEPLOYING) |
| 5 | JUMPING | `jump1` if present (Royal Hogs), its landing frame `action_frame1` at the landing (§1.6); else `run1`. Lifted by the core's arc either way |
| 10, 16 | CASTING, ABILITY_FOLLOW_UP | `ability1` if present (or the row's `CustomAnimationPostfix`), else `idle1` |
| 9 | MORPHING | `idle1` |
| 12, 13 | FOLLOWING_REMOVED(_BUILDING) | only the Fisherman's hook sets them (`ProjectileFlight`): a troop it pulls (12) is drawn `idle1` where the hook has it, with its bar; a Fisherman it drags to a building (13) as in 14 |
| 14 | COMPONENTS_DISABLED | the Fisherman held while his hook pulls: `loading1`'s `pull_start`…`pull_end` at the clip's rate, held on its last frame; else `idle1` |
| -1 | any non-character | n/a |

Two further rules apply to every state:

- A charging unit (Prince, Dark Prince, Battle Ram, Ram Rider's Ram, evolved Royal Recruits) uses `charge1` instead of `run1` once its charge is complete (`chargeProgress` ≥ 10000, read through `BattleAdapter.unitMotion`), until the charge is lost.
- A unit winding up a dash (`dashWindupMs` > 0: Bandit, Mega Knight, …) stands: `idle1`, or the crouch of its `jump1` (§1.6).
- A stunned or frozen unit (`statuses` STUNNED/FROZEN) holds its current frame: playback rate 0.

**Evidence.**

- The action names and their counts are in §1.2. The state numbers are in `GridEntityState`.
- `DontStopMoveAnim` is set on 8 rows, all of them Golem or Ice Golem variants. That implies the default is to stop the run clip when the unit does not move.
- `AnimationsKeepLastFrame` is set on 11 rows (Berserker, Little Prince, Mega Knight evo, Dagger Duchess).
- `DeployAnimationOverride = "_reborn1_"` is on PhoenixNoRespawn.

**Confidence.** The actions that exist are DATA. The mapping is INFERRED from the state names and column names. The stun freeze and the WAITING_TO_DEPLOY handling are GUESSes.

### 1.4 Attack timing: line the `action_frame` up with the logic hit

**What the clip shows.** `Knight_attack1_5` starts and ends in the ready pose, sword forward (frames 0–6 and 24–34). Frames 9–21 are the wind-up, with the sword raised behind. Frame 22, the `action_frame`, is the strike. So `action_frame` is the moment of impact (melee) or release (ranged), and the clip runs ready → wind-up → impact → ready.

**Model in the core (DATA, `AttackTimerAdvance.java`).**

- The attack timer `t` (`TargetingState.attackTimerMs`) starts at `LoadTime` when the unit locks on (less any load countdown still running).
- `t` grows by 50 ms each tick, scaled by buffs.
- A hit fires each time `t` crosses a multiple of `HitSpeed`.
- So the first hit comes `HitSpeed - LoadTime` after the lock (Knight: 500 ms), and later hits come every `HitSpeed`.
- `attackSequenceIndex` selects `attack1`, `attack2` or `attack3`. Monk and Little Prince have `AttackSequence = [0,1,2]`, and Archer EV1's `ActionSetAttackSequenceIndex AttackIndex=1` selects its `attack2`.

**What the client does (INFERRED).** It matches the clip's `action_frame` to the hit. The column `DisableAttackAnimationFrameMatching = true` exists to turn that matching off on 2 hero attack actions (`electro_wizard_hero.toml`), so frame matching is the default.

Other columns fit that reading:

- `VisualHitSpeed` (Inferno Dragon 1200 against HitSpeed 400, and Mighty Miner) replays the clip at a slower cadence than the hits. The game needs it because the clip is otherwise played once per hit.
- `TryToFinishAttackAnimation` and `TryToFinishAttackAnimationBeforeNextAttack` (Valkyrie, Mega Knight evo) say the clip is normally cut off when the attack stops or the next one begins.
- In 76 of 421 attack clips the clip is longer than `HitSpeed`. In 21 the `action_frame` comes later than `HitSpeed` (e.g. Magic Archer `EliteArcher`: action frame at 1333 ms, HitSpeed 1100). So the playback rate must sometimes go above 1.

**Rule.**

Definitions:

- `af = action_frame / fps`, in seconds.
- `L = frames / fps`, the clip length.
- `HS = HitSpeed` in seconds; use `VisualHitSpeed` when the row has one.
- `p = (t mod HS)`: the time since the last hit boundary.
- `toHit = HS - p`: the time until the next hit.

Each frame:

1. **Wind-up.** If `toHit <= af`, show clip time `c = af - toHit`. The clip then reaches `action_frame` exactly when the hit fires.
   - If the unit only just started attacking and `af > HS - LoadTime` (the time available for the first hit), start the clip at `af - toHit`, skipping its start, or play the wind-up faster at `rate = af / (HS - LoadTime)`. Both look acceptable; the GUESS is that the game skips.
   - If `af > HS` (Magic Archer, Elixir Golem…), use `rate = af / HS` for the whole clip.
2. **Follow-through.** Otherwise, if a hit happened within the last `(L - af)` seconds, show `c = af + p`. The clip plays out after the hit. A new wind-up overrides it, unless `TryToFinishAttackAnimationBeforeNextAttack` is set.
3. **Otherwise**, show `idle1`, the ready pose.

Examples:

| Unit | HS | First hit after lock | Clip start | Clip end |
|---|---|---|---|---|
| Knight | 1.2 s | 0.5 s | 0.733 s before each hit (first time: skip 0.233 s) | 0.433 s after the hit; then idle for 1.2 − 0.733 − 0.433 = 0.033 s |
| Giant | 1.5 s | | 0.433 s before the hit | after a 0.5 s follow-through; idle for the remaining 0.57 s |

To implement this, add `attackTimerMs`, `hitSpeedMs` (the effective one) and `attackSequenceIndex` to `EntityView`. They come from `TargetingState.getAttackTimerMs()`, `TargetingConfig.hitSpeed()` and `getAttackSequenceIndex()`. A per-entity "hit landed this tick" event also works for the follow-through, but the wind-up needs `toHit` in advance.

**Confidence.** The core model is DATA. Frame matching is INFERRED (a column name plus the timing data). The exact handling when the time is too short is a GUESS. Frame-stepping a 30 fps capture of a Knight against a tower would confirm it.

### 1.5 Run, idle and footfall labels

- Loop `run1` and `idle1` at the clip fps. **DATA** (fps); INFERRED (looping).
- `run1` labels `action_frame1` and `action_frame2` are the two footfalls, about half a cycle apart. That is where `MoveEffect` (e.g. `knight_steps`: dust particles and a step sound, Layer Ground) is played.
- Fixed-rate playback looks right. The Knight covers 0.87 tiles per run cycle at Speed 60 (1 tile/s). Scaling the rate by the unit's current speed share keeps slows and rage consistent. That scaling is a GUESS.

### 1.6 Dashes, leaps, river jumps and charges

**What the core gives (DATA, read-only through `BattleAdapter.unitMotion`).** Per character: its live height `z + heightOffset` (`GridEntity.getZTotal`), the dash time left, the charge progress, the dash wind-up left and the landing hold. The renderer reads them each tick in its `WorldObserver.afterPostHooks` and interpolates the height between the last two ticks; the effect layer listens to `dashStarted`, `dashLanded`, `movementStateRequested` (JUMPING → other: a river jump landing) and `chargeCompleted`.

- **Leap (Mega Knight).** The wind-up runs `DashCooldown` (900 ms) with the unit still, then DASHING for `DashConstantTime` (800 ms) in the air, then a landing hold, still DASHING, until it walks (`DashLandingTime` 300: the hold reads 100…250, about 200 ms). The core's height profile is piecewise linear: 2/3 of `JumpHeight` at a quarter of the constant time, `JumpHeight` (3000) at half, 2/3 at three quarters, 0 at the end (`RouteFollower.dashVisit`). The example replay's red Mega Knight leaps at 13.40, 15.60, 86.70, 88.60 and 90.50 s (battle ticks 268, 312, 1734, 1772, 1810).
- **River jump (Hog Rider, Prince, Dark Prince, Ram, Royal Hogs, …, `JumpEnabled`).** JUMPING while it crosses water at `JumpSpeed` (game units per tick); the arc `(p − p²/64) · JumpHeight / 16` (`p` up to 32) is sampled into the height offset, peaking at about `JumpHeight` (4000) on a long jump (`RouteFollower.jumpVisit`). Speed during both is `JumpSpeed` (DATA, `SpeedBudget`).

**Drawing (§3.4 for the factor).**

| What | Rule | Confidence |
|---|---|---|
| Lift | the sprite at the ground point raised by the core's height × `ArtProjection.DU_PER_HEIGHT_UNIT` (as flying units and projectiles); the shadow stays at the ground point; the bar rises with it. A rider attached to a mount takes its mount's height | the height is DATA, the factor INFERRED (§3.4). A hog's 4000 lifts it 70 du, higher than a Balloon flies (52 du): check against a capture |
| Layer | a ground unit off the ground is drawn on the air layer, over the ground units it passes | INFERRED (the core moves it off the ground layer for contact, `GridEntity.getZTotal`) |
| Mega Knight `jump1` (85 f @60: `charge_start` 0, `charge_end` 13, `jump_start` 14, `jump_end` 53, `recover_start` 54, `recover_end` 84) | the crouch (0–13) at the clip's rate over the last 233 ms of the wind-up (idle before); the flight (14–53) stretched over `DashConstantTime`; the recover (54–84) stretched over the landing hold. The evolution's 30 fps clip is stretched the same way | labels DATA; mapping INFERRED from the labels and the columns; the crouch's placement at the wind-up's end is a GUESS |
| `dash1` (Bandit, Boss Bandit, Golden Knight, the Little Prince's guard) | from the dash's start at the clip's rate. The Golden Knight's chained dashes take `dash1`…`dash4` in turn by the chain's count (`chainDashed`) | INFERRED; the turn order is a GUESS |
| Royal Hogs' `jump1` (35 f @60, `action_frame1` 29) | played so `action_frame1` comes as the jump lands: duration = jump distance / `JumpSpeed` ticks | INFERRED (the label as the landing) |
| Evolved Royal Hogs' `landing1` | once, after the jump ends | GUESS |
| `charge1` | while the charge is complete (above) | INFERRED (state and column names) |

**Effects (`EffectLayer`, sounds into the sounds file as other unit effects).**

| Column | When | Example | Confidence |
|---|---|---|---|
| `DashStartEffect` | at the dash's start (`dashStarted`), where the unit stood | `mega_knight_dash_start`: `card_legendary_mega_knight_charge` and the `assassin_buff` glow | INFERRED: the evolution's `vfx_megaknight_evo_dash_start` has parts at `Time` 800 = `DashConstantTime`, the landing, so the effect starts at the leap |
| `DashEffect` | at the dash's start, following the unit (`FollowParent`) until it lands | `mega_knight_dashing` (a sound), `assassin_dashing` (a ground trail) | INFERRED |
| `LandingEffect` | at a dash's landing (`dashLanded`) and a river jump's (JUMPING → other) | `mega_knight_dash_end` (`mega_knight_deploy_compile` at 25 %, `ShakeScreen` 350), `hog_rider_landing` | DATA (core events); the column on the `JumpEnabled` rows ties it to the jump |
| `DashFilter` | on the unit's sprite from the dash's start while it is DASHING (cut when it walks) | `filter_mega_knight_jump` (90 f @60: alpha pulsing 1 → 0.6, then from frame 50 a ±1 du jitter), `filter_bandit_charge` (60 f) | the clips are DATA. Starting at the dash is INFERRED: the Mega Knight's jitter starts at frame 50 (833 ms), as it lands (800 ms). It makes the Mega Knight translucent in the air; confirm against a capture |
| `ChargeEffect` | when the charge completes (`chargeCompleted`), following the unit; its `Filter` component (`filter_protection`) on the unit | `prince_charge`: dust, `card_epic_prince_charge`, the `effect_charge` badge | INFERRED |

**Not handled.** The Fisherman's throw (`loading1` `throw_*`): his special shows `attack1`. The Mega Knight evolution's and the Golden Knight's art was not checked in a recording (no battle had them). `AttackDashTime` (Bats) is a lunge inside the attack, not a state. The Royal Ghost, Mini P.E.K.K.A, Monk and Mighty Miner have no dash or jump state (the Monk's and the Mighty Miner's abilities are CASTING, §1.3); the Goblin Barrel is a projectile (§6).

---

## 2. Facing directions

**Rule (the 9-direction standard, used by 416 of the ~470 rows with directional idles):**

- Exports `_1` to `_9` cover the right half of the circle in 22.5° steps:
  - `1` = facing up the screen, away from the viewer.
  - `5` = facing right.
  - `9` = facing down the screen, toward the viewer.
- The left half reuses the same exports mirrored horizontally (scale x by -1 about the clip origin).

Take the facing vector in **drawn screen space with y pointing up the screen**. In the desktop's window coordinates, whose y grows up:

```
fx = view.dx(headingX)          // ViewOrientation mirrors the width in the view of side 0
fy = view.dy(headingY)          // and the length in the view of side 1
θ  = atan2(fx, fy) in degrees   // 0 = up, +90 = right, ±180 = down
N  = number of direction exports for this action
step = 180 / (N - 1)            // 22.5 for 9, 10.588 for 18, 45 for 5
dir   = round(|θ| / step) + 1   // 1..N
flipX = θ < 0
```

Facing input: `headingX/Y` is the logic **facing**, not just the velocity, so use it as it is.

- At creation, `CharacterEntity` sets it to `(0, ±256)` toward the enemy, so a fresh unit shows `dir 1` at the bottom and `dir 9` at the top.
- While the unit targets, `CharacterEntity.turnToward(reference)` turns it to the vector to the reference. Crown towers are not characters: their facing stays `(0, 0)` (only the Royal Chef's throw calls `TowerEntity` `turn()`), so a tower's turret and rider aim at the reference it holds (`targetX/Y`), keeping the last aim without one (DATA, from the core code and a facing dump of the King tower).
- Movement updates it, except in the `FACING_GATE_MASK` states (standing, attacking, deploying…).

So during an attack it already points at the target (DATA, from the core code). Only when it is (0,0), which happens when a reference stands on the unit's own spot, keep the last facing.

Use the game-space (unsquashed) vector, not one squashed by the 0.81 y factor of §3. The direction sprites are 3D renders of the model turned by `k·22.5°` on the ground plane, so the index is a ground angle (INFERRED). Using the squashed vector would shift some 45° headings by one index.

**Variants (DATA counts; visual checks):**

| Directions | Units | Notes |
|---|---|---|
| 9 | almost everything (idle 416, run 414, attack 406) | `knight_run.png`, `hog_run.png`, `musk_attack.png`, `giant_run.png` confirm 1 = up, 5 = right, 9 = down |
| 18 | Sparky (`ZapMachine*`), Cannon Cart (`MovingCannon`, `BrokenCannon`), Flying Machine (`DartBarrell`), Cannon evo, Mini Zap Machine | still a half circle: 1 = up, 9 and 10 straddle right, 18 = down (`mcannon18.png`), so `step = 180/17`, with mirroring. These rows also have `RotateAngleSpeed` (600 or 224 °/s?) or turn smoothly |
| 5 | Mortar, Barbarian Launcher (`building_mortar.sc`), Goblin Cannon | `step = 45`, mirrored. The clips are near-identical (`mortar5.png`) |
| 2 | Suspicious Bush | 2 variants, not directions |
| 1 | Balloon, Skeleton Balloon, Inferno Tower | no direction; never flip |
| 0 (single clip) | Cannon, Tesla, Bomb Tower, King/Princess towers, most buildings | Cannon (`building_cannon`, 19 frames) and King Tower (`KingTower_blue`, child `turret` with 36 frames) carry the turret angle **on the timeline**. `HasRotationOnTimeline = true` on 12 character rows and 16 building rows. Cannon: `frame = round(|θ|/10)`, 0 = up, 9 = right, 18 = down; flip for left (`cannon_full.png`). `TurretMovement` (5, 10, 15) is the turret's angular step (INFERRED) |

**Confidence.** The 9-direction mapping is DATA, checked visually on Knight, Hog Rider, Musketeer and Giant. Mirroring is DATA: no left-facing exports exist. Ground-angle versus screen-angle is INFERRED. The facing source is DATA (core code). The client may smooth turns, as rows with `RotateAngleSpeed` suggest; snapping the index each frame is fine.

The Lava Hound flying north (ladder replay, 66–80 s and 131–148 s) keeps one heading (`(53, 250)` to `(57, 249)`, export 2 mirrored) every tick: its sway is the `run1` clip's own wing flap (18 frames @24), not a facing flip, so no smoothing is applied.

---

## 3. Scale and anchor

### 3.1 Arena display units per tile

Measured by the Q4 work on the arena art; see §4 for the method.

- 1 tile is **29.75 du across** (x) and **24.12 du along the length** (y). The arena art is a 2D picture taken by a tilted camera, so the length is squashed to 0.811 of the width (= cos φ, camera tilt φ ≈ 35.8°).
- Display origin of the arena export: x = 0 is the centre line (game x = 9000); y = 0 is the far edge (game y = 32000 in the bottom player's frame); y grows down the screen.

```
// gx, gy: game units after ViewOrientation (bottom player's baseline at gy = 0)
du_x = (gx - 9000)  * 0.02975
du_y = (32000 - gy) * 0.02412 - 3      // the -3 is good to about 2 du
```

### 3.2 Sprite size

**Rule.** A character or building clip is drawn in the same du space as the arena. It is scaled **uniformly** by `Scale / 100` (the row's `Scale` column, default 100) about its origin. The y squash applies to positions only, never to sprites, whose art already carries the perspective. The same holds for projectiles and area effects with their own `Scale` columns; see §6 and §7.

On screen, with `P` pixels per tile horizontally:

```
s       = P / 29.75                     // screen px per du
spriteM = translate(screenX, screenY) · scale(s · Scale/100 · (flipX ? -1 : 1), s · Scale/100)
screenX = P * gx/1000                   // after ViewOrientation
screenY = P * 0.811 * gy/1000           // the tile is drawn 0.811 as tall as wide; y as your canvas has it
```

The current desktop renderer uses square tiles (`RenderConstants.TILE_PIXELS`). Realistic mode should draw tiles `P × 0.811·P` so that the arena art lines up. If the arena art is instead stretched to fit square tiles, it is distorted, and the sprites must be stretched the same way (23% taller) to fit it.

**Evidence for `Scale`.** These are blue `idle1_5` bounds, at all frames, in du:

| Unit | Scale | Raw size w × h | Scaled w × h |
|---|---|---|---|
| Valkyrie | 140 | 20 × 38 | 29 × 53 |
| Skeleton | 130 | 16 × 24 | 20 × 32 |
| Spear Goblin | 133 | 31 × 34 | 42 × 46 |
| Knight | 98 | | 84 × 61 (sword included) |
| Giant | 100 | 57 × 109 | |
| P.E.K.K.A | 106 | | 63 × 99 |
| Golem | 130 | | 91 × 105 |

The rows with Scale 130–140 are the ones whose raw art is visibly undersized: raw Valkyrie is about half the Knight's height. After `Scale/100` the sizes match the game (Valkyrie ≈ Knight, Skeleton small, Golem large). For scale against the tile size, a Giant is about 1.9 tiles wide and 4.5 tile rows tall.

### 3.3 Anchor

**Rule.** The clip origin (0,0) is the unit's ground point under its feet, which is the collision circle's centre. Place it at the projected game position and draw nothing extra.

**Evidence.**

- In renders with clip (0,0) marked, it sits at the feet or the base centre for all directions.
- The crai renderer documents the same thing ("origin is … the unit's ground anchor").
- Pre-rendered sheets store it per frame as `ax, ay`.

**Confidence.** The scale numbers are DATA, measured. `Scale/100` is INFERRED (strong). The anchor is DATA, checked visually.

### 3.4 Heights: flying units and attached characters

**Rule (INFERRED).** A logic height `h` in game units (`FlyingHeight`, `AttachedCharacterHeight`, a projectile's z) lifts the sprite by `h · 0.01742` du up the screen. That is the orthographic camera factor `0.02975 · sin φ`, with cos φ = 0.811.

**Evidence.**

- The Tower Princess composited on `StarTower_base` stands on the tower's wooden floor at dy −34 to −38 du (§5.1). With `AttachedCharacterHeight 2200` that gives 2200 × 0.01742 = 38.
- An earlier reading from the King tower's `turret` child (ty −57) is not a height measurement: the same child sits at −33 in `KingTower_red`.

Examples:

| Unit | FlyingHeight | Lift |
|---|---|---|
| Minion | 1500 | 26 du |
| Balloon | 3000 | 52 du |
| Baby Dragon | 3500 | 61 du |

The ground point, and with it the shadow (§8), stays at the logic position.

**Independent cross-check of §3.1.** The spell radius rings in `effects.sc` measure about 30 du of horizontal radius per tile of `Radius`:

| Ring | Radius | Horizontal radius |
|---|---|---|
| Poison | 3.5 tiles | 105.2 du |
| Freeze | 3 tiles | 88.9 du |
| Rage | 3 tiles | 97 du |
| Zap | 2.5 tiles | 68 du |

Every ring is an ellipse with height/width 0.81–0.82: poison 85.9/105.2, freeze 71.6/88.9, zap 55/68.2. Both numbers match 29.75 du/tile and 24.12/29.75 = 0.811. **DATA.**


---

## 4. Arena background

### 4.1 Which art makes up an arena

**Rule.** Follow this chain:

1. `arenas.csv`: the arena row's `PvpLocation` names a row of `locations.csv`.
2. That row's `FileName` names `locations/<x>.csv` under `assets/locations/`.
3. The file is LZMA-compressed with Supercell's old header: insert 4 zero bytes after the 9-byte header, then decode it as LZMA "alone". `crforge_art.lzma_tables` and `crharness.gamedata` both do this.
4. The decoded file holds:
   - `FileName`: one (sc, ExportName) pair, the **ground**.
   - `Objects`: a list of decoration names, each with one or more (x, y) positions in game units.
5. Each decoration is a row of `csv_logic/decos.csv`. That row gives `FileName`, `ExportName`, `Layer` (Ground, Object or Above) and its own `Shadow*` columns.

Draw order:

1. The ground export.
2. `Layer=Ground` decorations.
3. `Layer=Object` decorations, y-sorted together with the units.
4. `Layer=Above` decorations. Skip the `foldable*Frame` pieces, which are frames for foldable phones.

| Battle | Location file | Ground | Decorations |
|---|---|---|---|
| Training Camp (`arenas.csv` TrainingCamp; `locations.csv` TrainingCamp / FirstMatch) | `locations/training_arena.csv` | `sc/arena_training.sc` / `training_area_bg` | about 40 kinds (rocks, `training_cliff*`, trees, fences) |
| Ladder, Arena 1 (`PvP_goblin`), **recommended default** | `locations/goblin_arena.csv` | `sc/level_goblin_arena.sc` / `goblin_bgr` | 30 kinds (stands, roof shades red at top and blue at bottom, trees, water wheel) |
| Legendary (`PvP_legendary`) | `legendary_arena.csv` | `sc/level_legendary_arena.sc` / `LegendaryArena_ground_layer` | 23 |
| Bone, Royal, Champion | `bone_arena.csv`, `royal_arena.csv`, `champion_arena.csv` | `bone_arena_bgr`, `royal_base_bgr`, `champion_base_01` | – |

Every ground export checked has a single frame, so the ground is static and the water is not animated.

crforge never picks a location; nothing in core or desktop reads `locations`. Use `PvP_goblin` for Ladder and `TrainingCamp` for Training Camp.

**Other layers, from the same `locations.csv` row:**

- Clouds drift over everything:
  - `CloudFileName = sc/effects.sc`, `CloudExportName = cloud_1` (some arenas use `asteroid_shadow_anim_01`).
  - `CloudCount = 4`, scale 54–114 %, speed 25–154, alpha 7–10, random flip.
- `ShadowR/G/B/A` and `ShadowOffsetX/Y` are a per-arena shadow tint and offset, used for §8 shadows. Training Camp has A = 100 and offset (60, 100).
- `OverlaySC`/`OverlayExportName` and `arenas.csv` `BackgroundSWF` are empty for these arenas.

**Confidence.** DATA.

### 4.2 Lining the art up with the 18×32 tile grid

**Rule.**

- The location art uses du with y pointing down, and its `Objects` coordinates are in the same frame.
- The art is **always** authored with blue at the bottom: the blue roofs are at the bottom, and the `goblin_blue_tower` decoration is at ly 38500. The game never mirrors it. Draw it the same way whichever side is at the bottom; only unit positions go through `ViewOrientation`.

With `lx = v.x(gx)` and `ly = 32000 - v.y(gy)` ("screen tiles": x from 0 to 18000 left to right, y from 0 to 32000 top to bottom):

```
du_x = (lx - 9000) * 0.02975      // 29.75 du per tile across
du_y =  ly         * 0.02412 - 3  // 24.12 du per tile down (the -3 is good to about 2 du)
```

- Display origin (0, 0) is the middle of the far (top) edge of the arena.
- The playfield is 535.5 × 772 du.
- The river centre is at du y ≈ 383.
- The bridges sit at tiles 2.55–4.5 across, as in the tilemap's bridge cells 2500–4500.

**Evidence.**

- **Training Camp tower pads.** The tower pads in `training_area_bg` were picked out as sand-coloured regions.
  - King pads are centred at (0.5, 71.2) and (0.4, 698.4) and measure 116.5 × 97 du, a 4-tile footprint.
  - Princess pads are centred at x −164.2 / +162 and y 157.3 / 615, and measure about 88 × 71.5 du, a 3-tile footprint.
  - Spawn group `King_PrincessTowers` puts the kings at (9000, 3000/29000) and the princesses at x 3500/14500, y 6500/25500.
  - So the x scale is 326.6 du over 11 tiles = 29.7, and the y scale is 627.2 du over 26 tiles = 24.12 (and 457.8 du over 19 tiles = 24.09).
- **Goblin arena checkerboard.** A period and phase fit of the tile checker in `goblin_bgr` gives:
  - Tx = 29.75. One tile edge lies on the centre line.
  - Ty = 24.15 (top half) and 24.02 (bottom half). The river art is slightly off 2 tiles.
  - The far edge is at −3.6 and the near edge at 768.8.
- **Full composite.** Ground, decorations and a red 18×32 grid line up with the checker, river, bridges and pads. All six towers drawn at their logic positions sit on their pads (`q45/goblin_full.png`, `towers_on_goblin.png`).
- **Independent check: the spell rings in §3.**

**Confidence.** INFERRED from measurement, to about 0.05 du per tile. Camera tilt φ = acos(0.811) ≈ 35.8°.

---

## 5. Towers

The tower's logic position, put through §4.2, is the sprite origin. It lands on the pad centre (checked visually). Colour comes from the screen side: the bottom side uses the Blue exports.

### 5.1 Princess tower (building row `PrincessTower`)

**Rule.** Draw these at the tower point, in this order:

1. `building_tower.sc` `StarTower_base_{blue|red}`: 1 frame, bounds [−54, −65, 54, 49] du.
2. The attached character `TowerPrincess`: `sc/chr_princess.sc`, base `princess_tower` (blue) or `princess_tower_red` (red).
   - Actions `idle1` (30 f), `attack1` (40 f @24, `action_frame` 26) and `run1`, each with directions `_1`…`_9`. The §2 rule applies, using the tower's `headingX/Y`, which the core turns toward the target.
   - Lift it by `AttachedCharacterHeight 2200 × 0.01742 ≈ 38 du`.
3. `StarTower_top_{blue|red}` at the tower point with no offset: the front battlement, which hides the princess's lower body.

The base is 108 du wide against a 3-tile footprint of 89 du, because the battlements overhang.

**Evidence.**

- `buildings.json` PrincessTower: `FileName sc/building_tower.sc`, Blue/Red(Top)ExportName as above, `AttachedCharacter TowerPrincess`, `AttachedCharacterHeight 2200`, `DeathEffect TowerExplode`.
- `characters.json` TowerPrincess.
- Composites at dz 0, −25, −34, −45 and −53: the princess stands on the floor at −34 to −38 (`q45/pt_comp.png`).

**Confidence.** The exports and their order are DATA. The −38 offset is INFERRED.

### 5.2 King tower (building row `KingTower`) and the King

**Rule.**

- **Tower clip.** `building_tower.sc` `KingTower_{blue|red}`: 98 frames @30, with labels:

  | Label | Frames | Meaning |
  |---|---|---|
  | `idle` | 0 | hatch closed |
  | `start` | 1–96 | the hatch opens and the cannon rises |
  | `stop` | 97 | cannon out |

  Play it like this:
  - Inactive: hold frame 0.
  - On activation: play 1 → 97 at 30 fps. That takes 3.2 s; `king_tower.toml` uses `ActionDuration 3300`.
  - Active: hold frame 97.
- **Child `turret`.** It sits at (0, −57) on blue and (0, −33) on red. Its 36 frames are **rotation, not time** (`HasRotationOnTimeline = true`):
  - 0 = muzzle up the screen.
  - 18 = right.
  - ~35 = down.
  - About 5° per frame. For angles on the left, use the mirrored frame and flip x.
  - Same scheme as the Cannon's 19 frames at 10° (§2). Aim it at the tower's reference (`targetX/Y`): the core never turns a crown tower's `headingX/Y` (§2).
  - Apply the parent frame's colour transforms. They hide the turret while the tower is idle.
- **Child `king_dummy`.** An empty placeholder with the identity matrix. Draw the King there, at its place in the frame's element order: blue frames list the king after the turret, red ones before it.
  - The King is `sc/chr_king.sc` `King_blue` (seen from behind) or `King_red` (facing the camera).
  - It has 98 frames with the same labels, played in lockstep with the tower.
  - There is **no extra offset**: its bounds run from y −107 to 2, so the height is baked into the art.
  - Frames 0 and 97 contain a nested `king_idle` child (86 or 99 frames) that loops on its own clock.
  - The King has no attack animation. Only the cannon fires (`KingProjectile`).
- **Size check.** 120 du wide against 4 tiles = 119 du. The front edge at +50 sits at 2 × 24.12 = 48.
- **Gap in the view.** `EntityView` has no activation phase. Core already raises `ActivationEvent` (`ACTIVATING_STARTED` / `ACTIVATING_FINISHED`) in `TowerEntity`, so add a `kingPhase` and the ms elapsed.

**Evidence.**

- `buildings.json` KingTower: `HasRotationOnTimeline true`, `TurretMovement 5`, `AttachedCharacterHeight 2400`, no `AttachedCharacter`.
- `king_tower.toml`: `WaitForKingTowerActivation` → `ActionDuration 3300` → `KingTowerActivationEffect` (sound only).
- Clip trees and composites: `q45/king_blue.png`, `king_chr.png`, `turret_a.png`, `turret_b.png`, `king_comp.png`.

**Confidence.**

- Exports, labels, children and the activation length are DATA.
- The King at `king_dummy` with no offset is INFERRED. The name and the composite agree, but no table links the tower to `chr_king.sc`; the client probably hard-codes it.
- The turret angle mapping is INFERRED from viewing the frames.

### 5.3 Destroyed towers

**Rule.** When a tower dies:

1. Play its `DeathEffect`.
2. Stop drawing the tower and its character.
3. Draw the ruin at the tower point:
   - **Princess:** `TowerExplode` → emitter `Tower_destroyed_ground` → `building_tower.sc` `Tower_destroyed_ground1`, Ground layer.
   - **King:** `King_player_die` (bottom) or `King_enemy_die` (top) → emitter `KingTower_destroyed_ground` → `Tower_destroyed_ground2`, Base layer.
   - The ruin's particle lives 800000 ms, so it is effectively permanent.

`princesstower_destroyed`, `kingtower_destroyed` and the `_water` variants exist, but the Default skin does not reference them (`tower_skin_sets.csv` Default: `KingDeathFx King_player_die`, `PrincessDeathFx TowerExplode`). `kingtower_destroyed` is the shark skin's ruin (emitter `shark_destroyed_ground`); the Default skin's own ruin emitters are `DefaultTowerKing_base` / `KingTower_destroyed_ground` → `Tower_destroyed_ground2` and `DefaultTowerPrincess_base` / `Tower_destroyed_ground` → `Tower_destroyed_ground1`. So the King's ruin is `Tower_destroyed_ground2` (91 × 88 du against the Princess's 81 × 73), drawn about 3 tiles wide on the King's 4-tile pad; the King tower's own clip is 120 du wide.

**Sounds.** `TowerExplode` plays `card_common_mortar_die` (sample `building_destroyed_05`). `King_player_die` / `King_enemy_die` play `card_tower_kingtower_player_die` / `_enemy_die`, whose FMOD events hold a multi instrument with an **empty** playlist: the King's fall is silent in the banks. The crown sound and the end horn come from the match end (§9).

**Health bars.** The tower rows carry their own: Princess `HealthBar Tower`, `HealthBarOffsetYBlue/Red` 62 / −12; King `Summoner`, 170 / −30.

**Confidence.** DATA.

---

## 6. Projectiles

**What to draw (DATA, `projectiles.json`).**

- Sprite: the row's `FileName` + `ExportName`. 283 of 312 rows have one, nearly all in `sc/effects.sc`.
  - For the red side use `RedExportName` when present (46 rows).
  - `Scale` is a percent (92 rows, 60–200; default 100).
  - Ignore the prestige columns (skins).
  - `PrefabAsset` (8 rows) is 3D. Six of those have an SC2 `ExportName` fallback.
- Shadow: `ShadowExportName` (229 rows, usually `projectile_arrow_shadow`), from the same file, drawn at the ground point. Red version: `RedShadowExportName` (7 rows).

**Where to draw.**

- The core flies a projectile at height `z` on a parabola (`core/.../projectile/ProjectileFlight.java` `arcHeight`): `z = sz + (aimZ − sz)·t/total − Gravity/2·(t − total)·t`. `ConstantHeight` (42 rows) flies level.
- Draw the shadow at (x, y) and the sprite at (x, y) lifted by `z · 0.01742` du (§3.4).
- `EntityView` has no z, start or progress today. `ProjectileEntity` has `getZ()`, `getStartX/Y/Z()` and the aim, so add `z`, `startX`, `startY` and `aimZ` to the view.

**Orientation.**

- Rotate the clip in 2D so that its authored axis follows the direction of travel on screen. Use the ground track start→aim, or the frame-to-frame screen motion including z.
  - Arrow and fireball are authored pointing +x (right).
  - `projectile_spear_360` is authored pointing +y (down).
- Rotate the shadow too, unless `ShadowDisableRotate` (6 rows).
- Frames do not encode yaw:
  - **15-frame arc clips** (`projectile_arrow_basic(_enemy)`, `fireball_projectile1`, `dragon_projectile`, 60 fps) are a pitch sweep over the arc. Use `frame = round(progress · 14)` with `progress = |pos − start| / |aim − start|`. Do not play them at their fps.
  - **`use360Frames = true`** (33 rows: `projectile_spear_360` 33 f, `projectile_rocket` 60 f, `projectile_firespirit` 24 f) is a periodic tumble. Use `frame = round(progress · (n − 1))`.
  - **1-frame clips** (`projectile_cannonball_small`, used by Musketeer and King tower) need no frame choice.
  - **Long clips** (`log_roll_projectile`, 408 f) are real loops: play them at fps.
- `MinimumLengthForVisualAngleCalculation` (3 rows) hints that the client computes a "visual angle" from motion.

**Effects (§7).**

- `TrailEffect` (209 rows): a looping effect attached to the projectile.
- `HitEffect` (286): a one-shot at the impact point.
- `DeathEffect` (26): when removed without a hit.
- The shooter's `ProjectileEffect`: at launch (muzzle).

| Row | Sprite | Shadow | Gravity | Trail | Hit |
|---|---|---|---|---|---|
| MusketeerProjectile | `projectile_cannonball_small` (1 f) | arrow shadow | 0 (straight) | – | `cannon_hit` (particles) |
| ArcherArrow, TowerPrincessProjectile | `projectile_arrow_basic_enemy` (15 f), both sides | arrow shadow | 60 | – | `ArrowHit` (sound only) |
| ArrowsSpell (spell Arrows: 10 per wave × 3 waves, 200 ms apart; the core spawns them) | `projectile_arrow_basic_enemy` | arrow shadow | 50 | `arrows_trail` (no art) | `ArrowHitGround` → emitter `Arrows_Hit_Ground`: one of `Arrow_enemy_ground_anim_1..6` (arrows stuck in the ground), 1.5–1.9 s, 500 ms fade, Ground layer |
| FireballSpell | `fireball_projectile1` (15 f) | arrow shadow | 50 | `vfx_fireball_trail` (4 emitters, loop) | `Fireball_explosion` (10 emitters over Ground/Base/Shadow/Object/Above, Scale 85, ShakeScreen) |
| RocketSpell | `projectile_rocket` (360 frames-style, 60 f) | `projectile_shadow_rocket` | 50 | `Rocket_Emitter` | `Rocket_explosion` |

**Confidence.** The columns, exports, chains and height formula are DATA. The frames being pitch or tumble rather than yaw, and so needing a 2D rotation, is INFERRED from rendered sheets: the arrow goes end-on at frame 12 and reverses at 13–14, and the fireball peaks at frames 7–8. The progress→frame mapping is a GUESS; confirm it against a capture of an arrow.

---

## 7. Effects, area effects, spells

**The effects table** is `assets/legacy_vfx/effects.json`. It holds 2443 effects, keyed by exactly the names the rows use (`knight_hit`, `Fireball_explosion`, …).

Effect keys:

| Key | Meaning |
|---|---|
| `RenderableComponents[]` | the parts to play |
| `Scale` | % |
| `Loop` | 491 effects |
| `EnemyVersion` | 326 effects. Use that effect instead when the owner is drawn red |
| `FollowParent` | 301 effects. Attach to the entity |
| `ShakeScreen` | 292 effects |
| `ForcedLength` | |
| `PivotClip` | |
| `PrestigeVersion*` | skins: ignore |

Component `Type` values:

| Type | Count | Notes |
|---|---|---|
| ParticleEmitter | 5262 | |
| SWF | 1718 | `FileName` + `ExportName` |
| Effect | 564 | nested effect name: recurse |
| Filter | 123 | applied to the unit's sprite, §8 |
| Prefab | 9 | 3D: skip |

Each component also carries:

| Key | Meaning |
|---|---|
| `Layer` | draw layer |
| `Time` | start delay in ms |
| `RenderableScale` | % |
| `Sound` | sound to play |

**SWF components without `ExportName`** (1211 of 1718) have nothing to draw: 1080 carry only a `Sound`, and 131 carry nothing. So most character hit and attack-start effects draw nothing (`knight_hit`, `knight_attack_start`, `hog_rider_hit`). **INFERRED.**

**Playing an effect.**

- **SWF:** play the export from frame 0 at its clip fps.
  - Position: the effect position, or the entity's when `FollowParent`.
  - Scale: `Scale/100 · RenderableScale/100`.
  - After `Time` ms of delay, play once and drop it after the last frame. With `Loop`, loop it while the owner lives.
- **Filter:** see §8.
- **ParticleEmitter:** defined in `legacy_vfx/emitters.json` (2465 emitters).
  - Schema:
    - `ParticleExportName[]` / `ParticleResource[]`: a random export per particle, mostly from `sc/effects.sc`.
    - `ParticleCount`.
    - Emitter life `MinLife..MaxLife`; spawn interval `ParticleMinInterval..MaxInterval`; particle life `ParticleMinLife..MaxLife`.
    - Angle `ParticleMinAngle..MaxAngle`, speed `ParticleMinSpeed..MaxSpeed`.
    - Z: `ParticleMinVelocityZ..MaxVelocityZ`, `ParticleGravity`, `ParticleStartZ`.
    - Spawn ring `ParticleMinRadius..MaxRadius`.
    - Scale `StartScale → EndScale`; fades `FadeInDuration` / `FadeOutDuration`.
    - `RotateToDirection`, `LoopParticleClip`, `Inertia`, `TrailSWF`.
  - A plain 2.5D emitter (≈150 lines) on the same sprite renderer can play these.
  - Minimal fallback: skip emitters and play the SWF components and the area art only. Most hit and death puffs will then be missing; they are about 70% of all components.

**Layers.**

| Layer | Components |
|---|---|
| Object | 3202 |
| Above | 1760 |
| Base | 1651 |
| Ground | 765 |
| GroundAbility | 179 |
| Shadow | 80 |
| HealthBars | 1 |

Order, bottom to top:

1. arena
2. **Base**: radius rings, decals
3. **Ground**: dust, stuck arrows
4. **GroundAbility**
5. **Shadow**
6. **Object**: y-sorted together with the units
7. **Above**: glows, sparks, bolts
8. **HealthBars**

Ordering is INFERRED from what sits on each layer. Base versus Ground is a GUESS.

The renderer draws the Object layer of effects on the ground (not lifted) in the same y-sort as the ground units, buildings and towers: an Object part is drawn just before the first unit nearer the viewer than the effect's anchor, so a death puff behind a tower stays behind it. Object parts of lifted effects (a flyer's death) draw with the Above layer, over the units.

**Death puff size (GUESS).** At face value `Death_player`'s purple puff (`Death_enemy_puff`: 12 additive `explosion_purple_glow1` glows at 23–30 %, a clip that peaks 243 × 182 du at frame 12) stacks into a cloud about 1.5 tiles wide, larger than the game's. A troop's or building's `DeathEffect` is drawn at 0.7 of its scale (`EffectLayer.DEATH_SCALE`).

**Reflected attacks (Electro Giant).** The core reports each reflect (`WorldObserver.reflected`; one that struck back carries the buff it gave). Each plays, from the reflecting unit's row:

- `ReflectedAttackTargetedEffect` (`electro_giant_beam`: SWF `electro_wiz_beam`, `FlashWidth 80`, `PivotClip attack_pivot`, Above, sound `electro_wizard_beam`) as `ReflectedAttackTargetedEffectSources` (2) beams. Each runs from the `attack_pivot_<n>` child of the unit's idle clip at its facing (the coils on its back) to the unit struck. The clip is authored along +x from 1 to 82 du, so it is rotated to the target and stretched along x by distance / `FlashWidth`, its thickness as authored (INFERRED). Both ends follow their units while the clip plays (23 frames @30).
- `ReflectedAttackEffect` (`electro_giant_beam_hit`: sparks, puff, `vmc_electrowizard_def_v_hit`, sound `electro_wizard_hit`) at the unit struck.
- The `ReflectedAttackBuff`'s `FilterFile`/`FilterExportName` on the unit struck (`ZapFreeze`: `filter_cold`, a blue tint 29 frames @60, about its 500 ms).

The struck point is lifted to a flyer's `FlyingHeight`, or a crown tower's `AttachedCharacterHeight` (its shooter, GUESS).

**Columns that reference effects.**

| Column | Table | When, where |
|---|---|---|
| `SpawnEffect` | characters | on deploy, at the unit (`knight_deploy` = sound + `Deploy_ground_fX_mediumSize` dust, delayed 300 ms) |
| `AttackStartEffect` | characters | when an attack clip starts, at the unit |
| `ProjectileEffect` | characters | at launch, at the shooter (`musketeer_attack`: muzzle fire and smoke, Above) |
| `DamageEffect` | characters | when this unit's hit lands, at the target |
| `DeathEffect` | characters | at the death point. `knight_die` → `Death_player` (elixir burst + Base splat) + sword particle. **No death clip: remove the sprite and play this** |
| `ReflectedAttackTargetedEffect`, `ReflectedAttackEffect` | characters | each reflect that strikes back: beams from the reflecting unit's pivots, a hit on the unit struck (see below) |
| `MoveEffect` / `LoopMoveEffect` | characters | footsteps, on the `run1` `action_frame1/2` labels (Ground) |
| `LandingEffect` | characters (55) | end of a jump or dash |
| `TrailEffect` / `HitEffect` / `DeathEffect` | projectiles | §6 |
| `OneShotEffect` | area_effect_objects (65) | once when created, at its centre |
| `LoopingEffect` | area_effect_objects (61) | loop for `lifetimeMs` |
| `ScaledEffect` | area_effect_objects (108) | ground art (Base), loops, **scaled to `Radius`** |
| `SpawnEffect`, `HitEffect` | area_effect_objects | when it spawns something / on each unit it hits |
| `FileName`/`ExportName` | area_effect_objects (3) | its own sprite (`evo_cannon_crosshair`) |
| `Effect` | spells_*.csv | at cast (`Cast_RainOfArrows`, `CharacterDeploy`: sound only) |

**Spell chains (DATA).**

- **Arrows:** spell → 30 × `ArrowsSpell` projectiles → `ArrowHitGround` (§6).
- **Fireball:** spell → `FireballSpell` → `Fireball_explosion`.
- **Zap:** AEO `Zap` (life 1 ms) → `OneShotEffect` `vfx_zap_polish` (red: `vfx_zap_polish_enemy`). That is the bolts `zap_bolt1/2` (Above), glow and smoke, plus Filter `filter_target` (white flash on hit units).
- **Poison:** AEO `Poison` (8000 ms, R 3500).
  - OneShot `Spell_poison_effect`.
  - Looping `Spell_poison_loop` (skull particles).
  - Scaled `Spell_poison_ground` = SWF `Poison_radius` (509 f @60, Base) + `spell_poison_radius_blue` (390 f @30, Base). Red: `Spell_poison_ground_red`.
- **Freeze:** AEO `Freeze` (3500 ms, R 3000).
  - Looping `Spell_freeze_loop`.
  - Scaled `Spell_freeze_ground` = `freeze_effect_ground` + `spell_freeze_radius_blue` (Base) + snow particles (Ground).

**ScaledEffect size.** Scale the art uniformly so that its ring's horizontal half-width (in du, from the first frame's bounds) equals `Radius/1000 · 29.75` du. Rows share one effect at different radii (`Spell_freeze_ground`: 3000 and 40000), so the scale must come from the radius. The rings are authored at about 30 du per tile (§3), so at the authored radius the scale is about 1. **INFERRED.**

**sc3d.** `assets/sc3d/characters/` holds 3D assets for about 50 units (knight, archer, giant, hogrider, musketeer, princess, pekka, …). Every character and projectile `FileName` still exists as SC2, so SC2 art exists for all of them. Only 9 Prefab effect components and 8 projectile `PrefabAsset`s are 3D-only. **DATA.**

---

## 8. Shadows, flying units, deploy and damage filters, team colours, health bars, draw order

### Shadows

- No body-shadow export exists for normal characters. The shadow exports in `chr_*.sc` are projectile shadows only.
- Draw the unit's current frame again as a dark silhouette: RGB → 0, alpha about 0.35–0.4, blurred if cheap (`client_globals` `SHADOW_BLUR_DISTANCE = 170`). Place it at the ground point, under the Object layer.
- Transform in du about the clip origin:
  1. `scale(ShadowScaleX/100, ShadowScaleY/100)`
  2. horizontal skew `x += ShadowSkew/100 · (−y)`
  3. translate `(ShadowX, ShadowY)`
- Typical values (ScaleX / ScaleY / Skew / Y):

  | Units | ScaleX | ScaleY | Skew | ShadowY |
  |---|---|---|---|---|
  | Knight, Hog Rider | 85 | 85 | 35 | −10 |
  | Musketeer | 80 | 80 | 35 | −10 |
  | Archer | 75 | 75 | 25 | −15 |
  | Giant | 85 | 80 | 25 | 0 |
  | Princess tower (ShadowX −5) | 102 | 70 | 13 | 5 |
  | Cannon | 90 | −90 | 10 | 10 (ShadowX −7) |

- Flyers use a negative `ShadowScaleY` (−60: Minion, Baby Dragon, Lava Hound; 40 rows). The silhouette is flipped vertically and drawn at the **ground** point, not under the lifted sprite.
- Dedicated shadow sprites:
  - `ShadowCustom` / `ShadowCustomLow` (7 rows: `bat_shadow`, `flying_machine_shadow`).
  - `BlueShadowExportName` / `RedShadowExportName` (Cannon EV1).
  - Projectiles: `ShadowExportName`.

**Confidence.** The columns are DATA. "A silhouette of the sprite" is INFERRED. The skew sign, alpha and blur are GUESSes; check one in-game screenshot.

### Flying units

`air` is `FlyingHeight > 0`. Draw the sprite at the ground point lifted `FlyingHeight · 0.01742` du (§3.4). Examples:

| Unit | FlyingHeight | Lift |
|---|---|---|
| Minion | 1500 | 26 du |
| Balloon | 3000 | 52 du |
| Baby Dragon | 3500 | 61 du |
| Lava Hound | 4000 | 70 du |

Use the same factor for projectile z. The values are DATA; the factor is INFERRED from one tower data point.

### Deploy filter

`DeployBaseAnimExportName` names a clip in `sc/effects.sc`:

| Export | Rows |
|---|---|
| `filter_deploy_unit_default` | 336 |
| `filter_deploy_unit_legendary` | 124 |
| `filter_deploy_unit_miner` | 10 |
| … | |

It is a **filter**: a clip with one placeholder child, shape 193 (a goblin). Its per-frame matrix and colour transform are applied to the real unit sprite, pre-multiplied in the unit's local space with the origin at the feet.

`filter_deploy_unit_default` has 35 frames at 60 fps (≈ 583 ms):

| Frames | Matrix | Colour |
|---|---|---|
| 0–14, drop-in | `[0.868, 0, 0, 1.319, 0, ty]`, ty −64.95 → 0 | alpha 102 → 255, additive (150,150,150) → (25,25,25) |
| 15–34, landing squash | scale (1.10, 1.11), (1.33, 0.91) … → (1, 1) | none |

Play it from the first `DEPLOYING` tick. Then show `idle1` until the unit leaves DEPLOYING. Draw nothing during `WAITING_TO_DEPLOY`. The 300 ms `Time` on the deploy dust lines up with the landing at frame ≈ 14.

**Confidence.** The transforms are DATA. The filter semantics are INFERRED. The start alignment against `DeployTime` is a GUESS.

### Damage flash

- `DamageExportName` is `filter_damage` (367 rows) or `filter_damage_wobble` (106). The real exports carry a direction suffix:
  - `filter_damage_{down,up,left,right}`: 25 f.
  - `filter_damage_wobble_*`: 50 f.
- Pick the suffix from the screen direction of the hit, from attacker to unit.
- `filter_damage_down` frames 0–4 go from scale (1.09, 0.912) with ty +4 back to identity. The colour add is (17,8,0) throughout.
- `filter_target` (white add 150 → 35 over 30 f) is Zap's flash.
- This needs a per-unit "was hit this tick" event. `AreaHitLog` and the damage numbers may already give it.

**Confidence.** DATA for the transforms; INFERRED for the trigger.

### Team colours

The bottom side of the `ViewOrientation` is blue/"player". **DATA.**

- Characters: `BlueExportName` / `RedExportName`.
- Projectiles: `RedExportName` / `RedShadowExportName`, else the same art for both.
- Effects: `EnemyVersion`, and `_blue` / `_red` art pairs such as `spell_poison_radius_blue/red`.

### Health bars

- `csv_client/health_bars.csv` maps the row's `HealthBar` column (Medium 233 rows, Small 104, High 60, Tower, Shield, `*_EV1`, `High_2_Segments`, …) to exports in `sc/ui.sc`:
  - Units: `hp_player_small/medium/high` and `hp_enemy_*`.
  - Towers: `hp_player_tower_withNumber` and `hp_enemy_tower_withNumber`.
  - Segments: `SegmentsCount` with `hp_bar_divider_player/enemy`.
  - Visibility: `ShowEnemyAlways = true` for units. Own bars are hidden until damaged (`ShowOwnAlways` only for Tower and Summoner).
- Each export is a composite with named children:
  - `bar`: a 100-frame clip. **frame = round((1 − hp/max) · 99)**: 0 is full, 99 is empty. **DATA**, from the bounds.
  - `level`: a text field.
  - `downgrade` and `chef_upgrade`: hide them.
  - The tower bar adds `crown` and `hpNumber`.
- Sizes in du:

  | Bar | Size |
  |---|---|
  | small | ≈ 34×18 |
  | medium | ≈ 48×18 |
  | high | ≈ 65×18 |
  | tower | ≈ 93×23 |

- `healthbars.toml` (`Healthbar_Badge_*`, `Healthbar_Bar_*`) is a newer split version of the same art.
- **Placement (INFERRED):** `bar y = top of the unit's idle1 sprite bounds + HealthBarOffsetY` (du, +y down). 220 rows set it, mostly −10; Knight's +28 cancels its raised sword. `HealthBarOffsetYBlue/Red` override it per side. Flying units add their lift.

### Hand cards

An unaffordable card in the hand is turned to its luminance (grey), with no wash toward white. The client's ability button has `Grayscale = true` for its `not_enough_elixir` state (`csv_client/ability_button.toml`); the hand card (`spell_card_ingame` in `sc/ui.sc`) has no disabled frame or colour of its own, so it is assumed to use the same state. **INFERRED.**

### Shader FX

`ShaderFXBase/Top = Outline_Blue` appears on only 8 rows (an outline). Skip it.

### Draw order (INFERRED)

1. Arena.
2. Effect layers Base, then Ground, then GroundAbility.
3. Shadows: unit silhouettes, projectile shadows and Shadow-layer particles.
4. Ground units, buildings, towers and Object-layer effects, **sorted by screen y, far first**. A tower draws base, then attached character, then top (§5).
5. Air units and projectiles in flight, sorted by y.
6. Above-layer effects.
7. Health bars.

`VisualLayer = GROUND` exists on a single row (a decoration dummy that draws on the ground).

---

## 9. Crowns and the end of the match

**The core's end (DATA, `LadderMatch`).** `end()` runs once the match is decided (three crowns, or the time up with unequal crowns): `ended` is set, the timeline freezes, attack timers are held and hits refused (`BattleWorld.matchEnded`), and the winner is the side with more crowns. The battle keeps stepping for `endScreenDelayMs` (4 s in this data): the circle from a fallen king kills its side's units and buildings over the first half. Then the session is `over`. The frame's `timeMs` and the sides' elixir still run on after `end()`; the match clock the HUD shows does not.

**What the recorder shows (`MatchEnd`).**

| When | Shown | Heard | Label |
|---|---|---|---|
| A side's crowns go up | the tower's `DeathEffect` and ruin (§5.3) | `sound_get_star_player` when the bottom side takes it, `sound_get_star_enemy` (a crowd boo) when the top side does | INFERRED: crowns are "stars" in the client's names (`StarTower_*`), and the two rows sit with the battle sounds in `sounds.csv` |
| `ended` | the HUD's clock, elixir and its rate hold their values at the end; each unit stands `idle1` at its position and facing at the end; the losing side's units still die to the circle; destroyed towers have no bar | `Gameend Horn` (`music_jingle_general_gameend_horn`); the battle music stops (a `{"t", "end": true}` line in the sounds file); no countdown or music change after it | freeze: INFERRED from the core (timeline frozen, attacks held); units idle in place: GUESS; horn at any end: INFERRED from its name |
| `over` (4 s later) | `sc/ui_battle_end.sc` `replay_battle_end_new` over the arena, scaled so its `screen_area` (768 × 1024 du) fills the width: the 50% black backdrop (stretched to the phone's height), `player_01` (bottom side) and `player_02` panels sliding in (`appear_start..appear_end`), each won crown popping in (`crown_k`: `crown_appear_start..end`, 0.75 s + 0.4 s per crown), then `winner_start..winner_end` and `winner_txt` ("Winner!", text field 280/283) on the winner's panel; `draw` on a draw | `Win` / `Lose` / `Draw` (`music.csv`, from the bottom side's view) at its start; `sound_crown_appear_1..3` (pitched +0/+2/+4 semitones in the bank) as crowns 1..3 pop | the clip and labels are DATA; their timing, hiding the buttons, taunts, names, trophies and league, and the jingle's moment are GUESSes |
| `over` + 4.5 s | the recording stops (`OUTRO_TICKS = 90`) | | ours |

Not used: `sound_get_crown_1..3` (the same sample pitched like `crown_appear`), `First_Crown` / `Second_Crown` / `Third_Crown` (listed beside the achievement and chest sounds, likely menu sounds), `Pre_result` / `Post_result_loop` music, and the `win_battle_confetti_blue/red(_top)` effects (70 confetti particles at 100–200 speed, up to 800 z speed under -150 gravity, 10–15 s): where and when the client starts them is not in the data, and burst over the winner's king they hang over it as a cloud.

**The start jingle (INFERRED).** `MUSIC_START_TIMES` starts `BattleMusic` at 180 s left, when the clock starts; the location's `Sound` jingle belongs to the intro banners before it. A recording starts at the clock, so the mixer starts the music when the jingle's sound ends: its length up to its last audible point (above -40 dBFS), so its silent tail leaves no gap (`audio.battle_schedule`).

**Thinning the mix (INFERRED, ours; `audio.py`).** The banks' own per-event limits come first (`max_instances`, stealing, `cooldown`: Skeleton steps 4 instances and 0.15 s; Archer arrow hits 2 and 0.1 s). On top of them:

- footsteps and wing flaps (`*_steps`, `*_step`, `*_wing`) start at most every 0.3 s per event and play 4 dB quieter;
- voice lines (`*_vo`, e.g. the Miner's grunt) at most every 2 s per event;
- each still-playing instance of an event lowers a new one by 2 dB (at most 6 dB).

The game's 3D attenuation, buses' snapshots and ducking are not in the parsed data; these rules stand in for them.

## 10. 3D assets (sc3d)

The rows that the game draws only in 3D are listed in §1.1 (hero forms, `Ronin`, `MinionGiant`, evolutions with a `PrefabAsset`). `crforge-art sc3d` (see `tools/game-art/README.md`) extracts them as standard glTF 2.0 into `<cache>/<sha>/sc3d/`. Extraction is done. Placing the models in the battle is not done yet.

**Where the data is (DATA).**

- `assets/assets.scdb` is a SQLite database with tables `meta(path, assetType, assetFilename, uuid, …)` and `asset(path, data, …)`.
- Prefabs (`ScrollRuntimeScene`), animation state machines (`AssetASMData`) and timelines are JSON in `asset.data`. References are written `"path | uuid"`.
- `PrefabAsset = "sc3d/.../hero_prefab_x_battle"` names a prefab. Its node tree holds the following components (`objectType`):
  - 5001 Model: `.glb`, plus `Geometries[].MaterialOverride` (`.rmat`);
  - 5003 `AsmAsset`;
  - 5007 light;
  - 5019 `PivotPoints` (`hudoffset`);
  - 5006 nested `Prefab`;
  - 5018 `MaterialAsset`;
  - 5004/5008 particles and movie clips;
  - 5014/5016 timelines.
- The battle prefabs name the `lod1` models. `lod0` appears only in the `_collection` (menu) prefabs.

**File formats.**

| File | Format | Label |
|---|---|---|
| `.glb` | glTF binary container; the JSON chunk is replaced by a `FLA2` chunk: the glTF 2.0 schema as a FlatBuffer (field order in `gltf_flat.fbs`). It decodes to sane JSON for all 1437 files, and every accessor fits its bufferView | DATA |
| `SC_odin_format` | node parents, interleaved vertex streams, materials and packed animation in a glTF extension (`odin.py` docstring) | DATA (decodes and validates for all files) |
| vertex formats 30/29/22/12/9/3/36 | float3 / float2 / unorm16×2 / snorm8×4 / unorm8×4 / u8×4 / packed weights (11+11+10 bits, /4095). Normals come out unit length, weights sum to 1 and joints stay below the joint count | INFERRED, checked by the validator on all 341 models |
| UV v | stored v-up: glTF v = 1 − v | INFERRED, visually (textures land on the right parts only when flipped) |
| packed clips | int16 stream per node: `n` keys, then `−r` = hold r frames. Rotation is xyzw·32767; translation and scale are base + value·multiplier. Every one of the 1088 clips consumes exactly its `dataSize` | stream layout DATA. "Hold" INFERRED: the step after a hold is as large as a normal frame step, so held frames are not interpolated gaps |
| `.ktx` | KTX2, ASTC 4×4 / 6×6 / 8×8 (vkFormat 157–172), no supercompression, `KTXorientation = rd` | DATA |
| `_n` | X in RGB, Y in alpha, Z rebuilt | INFERRED (R=G=B on every normal map). Whether +Y is "up" (OpenGL) or "down" is a GUESS: a lit render would settle it |
| `_mra` | R metallic, G roughness, B ambient occlusion | INFERRED (gold parts have high R) |
| `_spec` (`teamColorTex`) | R ≈ a specular/brightness mask, G/B small masks | GUESS. It is kept as a side PNG |
| `.rmat` | FlatBuffer `{version, base, params[{name, type, value}]}` with types 1 float, 2–4 float vectors, 6 texture path, 7 bool. All 1900 files decode | DATA (type 2 is INFERRED) |

**Space and units.**

- **Prefab space is Z-up (DATA).** Each model's top node `CHARACTER` rotates its Y-up mesh by +90° about X, and `hudoffset` pivots lie on +z (0.84–3.3). The `.glb` adds a `zup_to_yup` root for viewers. Drop that root to work in prefab space.
- **Facing (INFERRED).** In prefab space the characters face −Y: on every rigged character, the `L_*` bones sit at +x. In the glTF output they face +Z, glTF's front. Tombstone-like buildings are turned 180° about z by their prefab.
- **Prefab node transforms (DATA).** Each node has `pos`, `rot` in Euler degrees, and `scl`. The character models are scaled 0.67–1.0 and are turned only about z. The Euler order matters only for VFX nodes and is a GUESS (x, then y, then z): `index.json` `models[].transform` uses it.
- **1 unit ≈ 1 tile (INFERRED, weak).** Ronin's model, prefab-scaled, is 0.82 deep and 1.5 tall, against a `CollisionRadius` of 600 (1.2 tiles across). The Tombstone base is 1.3 × 1.7. Check it by drawing a model over its collision circle.

**Camera, lights and states: clues found (DATA, use unverified).**

- `client_globals.csv` `VANILLA_3D_ACTOR_ENV` names `sc3d/vanilla/maps/arn_default/prefab_arn_default`. It holds a `camera_root` rotated −36.8° about x, with the camera 20 units out on its z axis. The camera component is `IsOrtho: true`, `OrthoWidth 27.8`, `OrthoHeight 49.39` (9:16), `Near 0.1`, `Far 1000`, `Fov 45`, `BloomThreshold 1.13`.
  - A 36.8° tilt from vertical squashes the ground's length by cos 36.8° = 0.801. §3.1 measured 0.811 (φ ≈ 35.8°) on the 2D arena art, so the 3D actors are probably drawn with an orthographic camera matching the 2D view.
  - The camera sits on the +y side, so a unit's default −Y facing points up the screen, matching "dir 1 at the bottom" (§2).
  - How 27.8 × 49.39 units map onto the 18 × 32 tile arena is open. If 1 unit = 1 tile, the arena would fill only 65% of the width.
- `arn_default` light: rot (−60, −180, −41), intensity 2, white, ambient (132, 115, 106), `AmbientSpecIntensity 2.45`.
  - Each battle prefab also carries its own `sun`: rot (−80.6, 0, −32.6), intensity 2–3, colour 128 grey, ambient around (111–136, 138–169, 114–139).
  - Which light wins, and how the `uber_pbr` shader combines them with `ambientIntensity`, `metalness` and the rim-light params, is open. The shaders are in `assets/sc3d/shaders/*.shader` (not decoded).
- `sc3d/vanilla/animation_states_remapping` maps the client's numeric anim states to ASM state names:
  - 0 Walk, 1 Idle, 2 Attack, 3 Charge, 4 Dash, 5 Deploy, 6–8 Attack2–4, 12/17 Skill, 16 SkillLoop, 24/26/28 Custom1–3, 27 Warp, 29 Walk2.
  - Each ASM (`sc3d/asm/*.json`) maps those names to clip, frame range, `BaseSpeed` and sound events, with `Random` states for idle breaks. This is the 3D counterpart of §1.3.
- `hudoffset` (prefab z) is the health-bar height. `ClippingPlaneEnabled` (5017) is probably the ground clip for deploy-from-below (GUESS).

**What placing them needs.**

- Project prefab space with the ortho camera above: tilt 36.8°, and a scale in pixels per unit that still has to be fitted against the 2D arena's 29.75 du per tile.
- Turn the model about z by the unit's heading. The default facing is −Y.
- Play the ASM state that the logic state maps to through the remapping table.
- Light it with the prefab's `sun` and ambient.
- Check the unit size against the 2D sprites (§3.2) and the collision radius.

---

## What `EntityView` needs in addition

| Field | Source in core | For |
|---|---|---|
| `attackTimerMs`, effective `hitSpeedMs`, `attackSequenceIndex` | `TargetingState.getAttackTimerMs()`, `TargetingConfig.hitSpeed()`, `getAttackSequenceIndex()` | lining attack clips up with hits (§1.4), `attack2`/`attack3` |
| height, dash time, charge progress, dash wind-up, landing hold (done: `BattleAdapter.unitMotion`, read by the renderer's observer) | `GridEntity.getZTotal()`, `MovementState`, `TargetingState.getDashWindupMs()`, `getBlockCountdownMs()` | lift, `jump1`/`charge1` timing (§1.6) |
| projectile `z`, `startX/Y`, `aimZ` | `ProjectileEntity.getZ()`, `getStartX/Y/Z()` | arc height, frame choice, shadow (§6) |
| king activation phase + elapsed ms | `ActivationEvent` in `TowerEntity` | the King tower clip (§5.2) |
| per-tick events: hit landed (attacker → target), death, projectile launch and impact, spawn | battle events, or the renderer diffs frames | effects (§7), damage filter (§8) |

## Open questions and guesses

1. **What attack timing does when time is short.** Frame matching itself is INFERRED. What happens when the wind-up has less time than `action_frame` (the first hit, or `af > HitSpeed`) is a GUESS: skip versus speed up. Frame-step a 30 fps capture of Knight, Giant and Magic Archer hitting a tower to settle it.
2. **The height factor 0.01742 du per game unit.** It is a camera model checked against one composite (the Princess on her tower). Check it against a flying unit and its shadow in a screenshot.
3. **Ground angle versus screen angle** for the direction index (§2). The two differ only near the bucket edges.
4. **Projectile frame mapping** (pitch sweep versus progress) and the rotation source (ground track versus screen motion including z).
5. **Shadow look:** the skew sign, alpha and blur, plus the per-arena `ShadowR/G/B/A` and `ShadowOffsetX/Y` from the location row.
6. **Base versus Ground layer order**, and how the deploy filter's start lines up with `DeployTime`.
7. **Run playback rate under slows and rage.** Fixed fps is the safe default.
8. **18 and 5 direction units.** The 18-direction step of 180/17 matched the Cannon Cart visually. The 5-direction Mortar clips are almost identical, so 45° is unverified. Sparky's 18 frames read ambiguously.
9. **`RotateAngleSpeed` and `TurretMovement` units** are not established: deg/s versus a turret step.
10. **King ↔ `chr_king.sc` link.** No table names it; it is assumed hard-coded in the client.
11. **Particle emitters** are about 70% of effect components. Without an emitter, most hit, death and trail visuals are missing. The SWF-only parts (stuck arrows, spell rings, zap bolts) are the cheap wins.
12. **Not done:** the Training Camp decoration pass was not composited; only its ground pads were measured. The ruin exports were not rendered. Hero, evo and `sc3d`-only art is extracted (§10) but not yet drawn.
13. **Lava Pups' glow.** Each `chr_lava_pups.sc` clip draws over the body a glow (shape 1, a 24 du soft disc scaled about 2.3×, covering the whole pup) with blend 8 (add) and the colour transform `mul (18, 18, 18)`, `alpha 187`, `add (255, 255, 151)`: a pale-yellow additive wash at 0.73 that turns the orange-brown body yellow-orange. The transform's byte order is DATA (identity is `255,255,255,a,0,0,0` 343 times in `effects.sc`, and `filter_cold` is a blue add), and the drawer applies it as Flash does (`rgb·mul + add·a`, then added). So the bright pups are drawn as the data says; whether the game's shader treats an additive child's added colour differently is unverified.
14. **Dashes and jumps (§1.6).** Check against a capture: whether the Mega Knight is translucent in the air (its `DashFilter`'s alpha) or the filter belongs to the wind-up; where its crouch falls; the river jump's height (4000 game units lift a hog 70 du); and the dash clips' turn order on the Golden Knight.
