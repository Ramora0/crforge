/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.render.art;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.Affine2;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.crforge.core.battle.BattleEntity;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.projectile.ProjectileEntity;
import org.crforge.core.battle.unit.AreaEffectEntity;
import org.crforge.core.battle.unit.WorldEntity;
import org.crforge.core.battle.unit.WorldObserver;
import org.crforge.core.pathfinding.combat.AreaDamage;
import org.crforge.core.pathfinding.combat.DamageResult;
import org.crforge.desktop.battle.BattleFrame;
import org.crforge.desktop.battle.EntityView;
import org.crforge.desktop.render.ViewOrientation;

/**
 * The battle's one-shot and looping effects drawn with the game's own effect art: deploys, deaths,
 * hits and spells, read from the effect columns of the units' rows and the client's effect
 * definitions ({@link EffectDefs}).
 *
 * <p>Events come from diffing each tick's frame against the one before, by entity id:
 *
 * <ul>
 *   <li>a new troop or building plays its row's {@code SpawnEffect};
 *   <li>a character that vanishes plays its {@code DeathEffect}; a crown tower also leaves its ruin
 *       ({@code Tower_destroyed_ground1/2}) for the rest of the battle;
 *   <li>a new projectile plays its {@code TrailEffect} following it; one that vanishes plays its
 *       {@code HitEffect} where it landed;
 *   <li>a new area effect plays its {@code OneShotEffect}, the {@code Effect} of the spell of the
 *       same name, its {@code LoopingEffect} and its {@code ScaledEffect} (scaled to its radius)
 *       for as long as it lives;
 *   <li>a new projectile's shooter (the closest character of its side holding a target) plays its
 *       {@code ProjectileEffect} at the launch;
 *   <li>a character whose hit points drop next to an enemy attacking it plays that attacker's
 *       {@code DamageEffect}, or, with {@link #observer()} added to the battle's world, every hit
 *       plays its attacker's, and area effects too short-lived for any frame (Zap) still play;
 *   <li>with the observer, a reflecting unit striking back (the Electro Giant) plays its row's
 *       reflect beams and hit, and its buff's filter on the unit struck.
 * </ul>
 *
 * <p>Effects are drawn through {@link EffectDrawer}, which applies the colour transforms' added
 * colour that much effect art relies on. Environment hooks for testing: {@code CRFORGE_FXDEBUG}
 * logs every effect played; {@code CRFORGE_FXTEST=name@tick@x@y[@side];...} plays effects (or
 * {@code aeo:Row} area effects) at fixed ticks and places.
 *
 * <p>Each effect part is an SC2 export played once at its clip rate (or looped while its owner
 * lives), or a particle emitter simulated in display units: positions on the ground plane in du
 * (the arena's length squashed by {@link #GROUND_SQUASH}), heights in du straight up the screen,
 * times in ms, as {@code emitters.json} gives them. The emitter semantics are inferred (see
 * docs/realistic-rendering.md §7): {@code ParticleCount} is the most particles alive at once,
 * {@code Inertia} a drag of Inertia/100 * {@link #INERTIA_SCALE} per second on every axis, negative
 * {@code ParticleGravity} pulls down.
 */
public final class EffectLayer {

  private static final boolean DEBUG = System.getenv("CRFORGE_FXDEBUG") != null;

  /** Test hook: {@code effect@tick@x@y[@side];...} plays effects at fixed places and ticks. */
  private static final String TEST = System.getenv("CRFORGE_FXTEST");

  /** Seconds per battle tick. */
  private static final double TICK = 0.05;

  /** The arena's length against its width in the art (29.7 vs 24.12 du per tile). */
  private static final float GROUND_SQUASH =
      ArtProjection.DU_PER_TILE_Y / ArtProjection.DU_PER_TILE_X;

  /** Display units per unit of an emitter's spawn radius (GUESS, tuned by eye). */
  private static final float RADIUS_DU = 4f;

  /** Alpha of a Shadow-layer particle, drawn as a black silhouette. */
  private static final float SHADOW_ALPHA = 0.3f;

  /**
   * Vertical velocities and gravity in du (GUESS): at face value a death's elixir drops (vz 600,
   * gravity -1700) peaked over two tiles up, the game's at about half a troop's height.
   */
  private static final float Z_SCALE = 0.5f;

  /**
   * A troop's or building's {@code DeathEffect} drawn at this scale (GUESS): at face value its
   * purple puff ({@code Death_enemy_puff}: 12 additive glows at 23-30% of a clip that peaks 243 du
   * wide) stacks into a bright cloud about 1.5 tiles wide, the game's is smaller.
   */
  private static final float DEATH_SCALE = 0.7f;

  /** Additive particles of one emitter drawn at full strength before they share it. */
  private static final int GLOW_CROWD = 2;

  /** Drag per second per 100 {@code Inertia} (GUESS, tuned against real-game clips). */
  private static final float INERTIA_SCALE = 6f;

  /** Longest simulation step, seconds. */
  private static final double MAX_STEP = 1 / 120.0;

  /** Cap on live effects, oldest one-shots dropped beyond it. */
  private static final int MAX_INSTANCES = 400;

  /** Cap on footstep effects playing at once (a Skeleton Army runs 15 pairs of feet). */
  private static final int MAX_FOOTSTEPS = 24;

  /** Shortest gap between two plays of the same footstep sound, seconds. */
  private static final double STEP_SOUND_GAP = 0.12;

  /** Screen shake: frequency (Hz); amplitude (du) and length (s) per ShakeScreen (GUESS). */
  private static final double SHAKE_HZ = 16;

  private final ArtCache cache;
  private final EffectDefs defs;
  private final VisualLookup lookup;
  private final List<Instance> instances = new ArrayList<>();
  private final List<Ruin> ruins = new ArrayList<>();
  private final Random rng = new Random(0x5EED);
  private final Affine2 transform = new Affine2();
  private final Color tint = new Color();
  private EffectDrawer art;

  private GameTables tables;
  private final Map<String, GameRow> rowCache = new HashMap<>();

  /** Battle clock of the tick before and of the tick (seconds), and their frame times (ms). */
  private double prevClock;

  private double curClock;
  private int prevTimeMs;
  private int curTimeMs;

  /** How far the particles have been simulated. */
  private double simClock = -1;

  public EffectLayer(ArtCache cache) {
    this.cache = cache;
    this.defs = new EffectDefs(cache);
    this.lookup = new VisualLookup(cache);
  }

  // ---------------------------------------------------------------------------------------------
  // runtime state

  /** A tower's ruin, drawn under everything for the rest of the battle. */
  private record Ruin(EffectDefs.Sprite sprite, int gx, int gy) {}

  /** One playing effect. */
  private final class Instance {
    final EffectDefs.Effect fx;
    final double start;
    final int follow;
    final float scale;

    /** A footstep, counted against {@link #MAX_FOOTSTEPS}. */
    boolean footstep;

    /** Lift of the effect's origin above the ground, world pixels before ppdu (du). */
    final float liftGame;

    final boolean red;
    float ax, ay, pax, pay;

    /** The parent's facing on screen, degrees clockwise from +x, for parent-relative emitters. */
    float angle;

    boolean ownerGone;
    double ownerGoneAt = Double.MAX_VALUE;
    final Run[] runs;

    Instance(
        EffectDefs.Effect fx,
        double start,
        int gx,
        int gy,
        int follow,
        float scale,
        float liftGame,
        boolean red) {
      this.fx = fx;
      this.start = start;
      this.follow = follow;
      this.scale = scale;
      this.liftGame = liftGame;
      this.red = red;
      this.ax = this.pax = gx;
      this.ay = this.pay = gy;
      this.runs = new Run[fx.parts().size()];
      for (int i = 0; i < runs.length; i++) runs[i] = new Run(fx.parts().get(i));
    }

    float anchorX(float alpha) {
      return pax + (ax - pax) * alpha;
    }

    float anchorY(float alpha) {
      return pay + (ay - pay) * alpha;
    }

    boolean done() {
      for (Run r : runs) if (!r.done) return false;
      return true;
    }
  }

  /** One part of a playing effect. */
  private static final class Run {
    final EffectDefs.Part part;
    boolean started;
    boolean done;
    double emitterEnd;
    double nextEmit;
    final List<Particle> particles;

    Run(EffectDefs.Part part) {
      this.part = part;
      this.particles = part.emitter() != null ? new ArrayList<>() : null;
    }
  }

  private static final class Particle {
    EffectDefs.Sprite sprite;
    float ax, ay; // game units: the effect's anchor when emitted
    float x, y, z; // du offsets: ground plane (y down the screen) and height
    float vx, vy, vz;
    float age, life; // seconds
    float s0, s1; // scale at birth and death, effect scale included
    float rot, rotSpeed; // degrees, clockwise on screen
    float heading; // last direction of travel on screen, degrees, for RotateToDirection
    float floor; // the ground's height relative to the effect's origin, du (<= 0 when lifted)
    boolean collide;
    boolean resting;
  }

  // ---------------------------------------------------------------------------------------------
  // events

  /** Called once per battle tick with the new frame and the one before it (null at the start). */
  public void update(
      BattleFrame frame, BattleFrame previous, GameTables tables, ViewOrientation view) {
    if (this.tables != tables) {
      this.tables = tables;
      rowCache.clear();
      cycles.clear();
      windups.clear();
      lookup.setTables(tables);
    }
    curClock = frame.tick() * TICK;
    curTimeMs = frame.timeMs();
    prevTimers = curTimers;
    curTimers = timers;
    if (previous == null) {
      prevClock = curClock;
      prevTimeMs = curTimeMs;
      if (simClock < 0) simClock = curClock;
      if (frame.tick() > 0 && ruins.isEmpty()) ruinsOfMissingTowers(frame, view);
      // what the observer heard before the first frame (a fast-forward) is not replayed
      events.clear();
      motions.clear();
      struck.clear();
      targetFilters.clear();
      activations.clear();
      reflects.clear();
      for (EntityView e : frame.entities()) {
        if (e.kind() == EntityView.Kind.TOWER
            && e.maxHitPoints() > 0
            && e.hitPoints() <= 0
            && destroyedTowers.add(e.id())) {
          EffectDefs.Sprite ruin =
              defs.sprite(
                  "sc/building_tower.sc",
                  e.king() ? "Tower_destroyed_ground2" : "Tower_destroyed_ground1");
          if (ruin != null) ruins.add(new Ruin(ruin, e.x(), e.y()));
        }
      }
      return;
    }
    prevClock = previous.tick() * TICK;
    prevTimeMs = previous.timeMs();
    if (simClock < 0) simClock = prevClock;
    double now = prevClock;

    Map<Integer, EntityView> before = new HashMap<>();
    for (EntityView e : previous.entities()) before.put(e.id(), e);
    Map<Integer, EntityView> after = new HashMap<>();
    for (EntityView e : frame.entities()) after.put(e.id(), e);

    // followed anchors move with their entities
    for (Instance in : instances) {
      if (in.follow < 0) continue;
      EntityView e = after.get(in.follow);
      in.pax = in.ax;
      in.pay = in.ay;
      if (e != null) {
        in.ax = e.x();
        in.ay = e.y();
      } else if (!in.ownerGone) {
        in.ownerGone = true;
        in.ownerGoneAt = now;
      }
    }

    for (EntityView e : frame.entities()) {
      EntityView p = before.get(e.id());
      boolean red = !view.blue(e.side());
      if (p == null) {
        appeared(e, frame, red, now, view);
      } else if (!observed && e.isCharacter() && e.hitPoints() < p.hitPoints()) {
        damaged(e, frame, red, now, view);
      }
    }
    for (EntityView p : previous.entities()) {
      if (!after.containsKey(p.id())) vanished(p, !view.blue(p.side()), now, view);
    }
    for (EntityView e : frame.entities()) {
      if (e.kind() == EntityView.Kind.AREA_EFFECT) seenAreas.add(e.id());
      // a crown tower at 0 hit points is destroyed even while the frame still lists it (the
      // battle's last frame after a three-crown win)
      if (e.kind() == EntityView.Kind.TOWER
          && e.maxHitPoints() > 0
          && e.hitPoints() <= 0
          && destroyedTowers.add(e.id())) {
        towerDestroyed(e, !view.blue(e.side()), now);
      }
    }
    for (Event ev : events) heard(ev, after, view, now);
    reflections(after, view, now);
    attackStarts(frame, before, view, now);
    footsteps(frame, before, view, now);
    flashes(after, view, now);
    kingActivations(after, view, now);
    dashes(after, view, now);
    if (TEST != null) testEffects(frame.tick(), view, now);
    events.clear();
    spawnedBy.keySet().retainAll(after.keySet());
    if (seenAreas.size() > 4096) seenAreas.clear();
    while (instances.size() > MAX_INSTANCES) instances.remove(0);
    shakes.removeIf(s -> now - s[0] > shakeLength(s[1]) + 0.1);
    damageFlash.values().removeIf(f -> now - f.start() > 3);
    targetFlash.values().removeIf(f -> now - f.start() > 3);
  }

  // ---------------------------------------------------------------------------------------------
  // dashes, jumps and charges (DashStartEffect, DashEffect, LandingEffect, DashFilter,
  // ChargeEffect)

  /** A dash, jump or charge moment the battle reported, with where the unit stood. */
  private record Motion(Kind kind, int id, String name, int side, int x, int y) {
    enum Kind {
      DASH_START,
      DASH_LANDED,
      JUMP_LANDED,
      CHARGED
    }
  }

  private final List<Motion> motions = new ArrayList<>();

  /** Each dashing unit's {@code DashEffect}, which follows it until it lands. */
  private final Map<Integer, Instance> dashEffects = new HashMap<>();

  /** Each dashing unit's {@code DashFilter}, played on it from the dash's start while it dashes. */
  private final Map<Integer, Flash> dashFilters = new HashMap<>();

  private void motion(
      Motion.Kind kind, org.crforge.core.battle.unit.CharacterEntity unit, int x, int y) {
    if (unit == null || unit.getData() == null || motions.size() > 2000) return;
    motions.add(new Motion(kind, unit.getId(), unit.getData().name(), unit.side(), x, y));
  }

  /**
   * Plays what a dash, a river jump and a charge show (§1.6): at a dash's start (the wind-up run
   * out, the unit leaving the ground) its row's {@code DashStartEffect} where it stood, its {@code
   * DashEffect} following it until it lands and its {@code DashFilter} on its sprite while it is
   * dashing; at the landing, of a dash or of a jump over the river, its {@code LandingEffect}; when
   * a charge completes, its {@code ChargeEffect} (dust, sound, the {@code effect_charge} badge)
   * with the effect's filter on the unit.
   */
  private void dashes(Map<Integer, EntityView> after, ViewOrientation view, double now) {
    for (Motion m : motions) {
      GameRow row = character(m.name());
      if (row == null) continue;
      boolean red = !view.blue(m.side());
      switch (m.kind()) {
        case DASH_START -> {
          play(text(row, "DashStartEffect"), red, now, m.x(), m.y(), -1, 1f, 0);
          endDashEffect(m.id(), now);
          Instance in = playFollowing(text(row, "DashEffect"), red, now, m.x(), m.y(), m.id());
          if (in != null) dashEffects.put(m.id(), in);
          String filter = text(row, "DashFilter");
          if (filter != null) dashFilters.put(m.id(), new Flash("sc/effects.sc", filter, now));
        }
        case DASH_LANDED, JUMP_LANDED -> {
          endDashEffect(m.id(), now);
          play(text(row, "LandingEffect"), red, now, m.x(), m.y(), -1, 1f, 0);
        }
        case CHARGED -> {
          EntityView e = after.get(m.id());
          int x = e == null ? m.x() : e.x(), y = e == null ? m.y() : e.y();
          EffectDefs.Effect fx = defs.effect(text(row, "ChargeEffect"), red);
          if (fx == null) continue;
          playFollowing(text(row, "ChargeEffect"), red, now, x, y, m.id());
          for (EffectDefs.Filter f : fx.filters()) {
            targetFlash.put(m.id(), new Flash(f.file(), f.export(), now + f.delay()));
          }
        }
      }
    }
    motions.clear();
    // the dash filter lasts while the unit dashes
    dashFilters
        .keySet()
        .removeIf(
            id -> {
              EntityView e = after.get(id);
              return e == null || e.state() != org.crforge.core.pathfinding.GridEntityState.DASHING;
            });
    dashEffects.keySet().removeIf(id -> !after.containsKey(id));
  }

  private void endDashEffect(int id, double now) {
    Instance in = dashEffects.remove(id);
    if (in != null && !in.ownerGone) {
      in.ownerGone = true;
      in.ownerGoneAt = now;
    }
  }

  /**
   * Plays an effect whose anchor follows its unit ({@code FollowParent}) until the unit is gone.
   */
  private Instance playFollowing(String name, boolean red, double now, int gx, int gy, int id) {
    EffectDefs.Effect fx = defs.effect(name, red);
    if (fx == null) return null;
    queueSounds(fx, now);
    if (!fx.drawable()) return null;
    Instance in = new Instance(fx, now, gx, gy, id, 1f, 0, red);
    add(in);
    return in;
  }

  // ---------------------------------------------------------------------------------------------
  // attack starts (AttackStartEffect)

  /** Attack timers by entity id as the observer last read them: {timerMs, sequenceIndex}. */
  private Map<Integer, int[]> timers = Map.of();

  private Map<Integer, int[]> curTimers = Map.of();
  private Map<Integer, int[]> prevTimers = Map.of();

  /** Per character name and attack sequence index: {hitSpeedMs, windUpStartMs}. */
  private final Map<String, int[]> windups = new HashMap<>();

  /**
   * Plays each attacking character's {@code AttackStartEffect} when its attack clip starts: the
   * renderer lines the clip's {@code action_frame} up with the hit (§1.4), so the clip starts
   * {@code action_frame / fps} before each multiple of the hit speed on the attack timer, i.e. when
   * the timer crosses {@code HitSpeed - af} (mod HitSpeed). A unit that locks on already inside the
   * wind-up starts it at once. Without the observer's timers, it plays as the unit starts
   * attacking. Mostly a sound (406 of 418 rows); a beam (Tesla) only plays its sound here.
   */
  private void attackStarts(
      BattleFrame frame, Map<Integer, EntityView> before, ViewOrientation view, double now) {
    for (EntityView e : frame.entities()) {
      if (e.state() != org.crforge.core.pathfinding.GridEntityState.ATTACKING) continue;
      if (e.kind() != EntityView.Kind.TROOP && e.kind() != EntityView.Kind.BUILDING) continue;
      GameRow row = character(e.name());
      String name = text(row, "AttackStartEffect");
      if (name == null) continue;
      EntityView p = before.get(e.id());
      boolean wasAttacking =
          p != null && p.state() == org.crforge.core.pathfinding.GridEntityState.ATTACKING;
      int[] t1 = curTimers.get(e.id());
      double at = Double.NaN;
      if (t1 == null) {
        if (!wasAttacking) at = now;
      } else {
        boolean blue = view.blue(e.side());
        int[] w = windup(e.name(), blue, t1[1]);
        int hs = w[0], start = w[1];
        int[] t0 = wasAttacking ? prevTimers.get(e.id()) : null;
        if (t0 == null || t1[0] < t0[0]) {
          if (Math.floorMod(t1[0], hs) >= start) at = now;
        } else if (t1[0] > t0[0]) {
          long n0 = Math.floorDiv((long) t0[0] - start, hs);
          long n1 = Math.floorDiv((long) t1[0] - start, hs);
          if (n1 > n0) {
            long cross = (n0 + 1) * hs + start;
            at = now + TICK * (cross - t0[0]) / (double) (t1[0] - t0[0]);
          }
        }
      }
      if (Double.isNaN(at)) continue;
      boolean red = !view.blue(e.side());
      if (name.equals(text(row, "TargetedHitEffect"))) {
        // a beam drawn between the unit and its target: only its sound here
        EffectDefs.Effect fx = defs.effect(name, red);
        if (fx != null) queueSounds(fx, at);
        continue;
      }
      int tx = e.hasTarget() ? e.targetX() : e.x() + e.headingX();
      int ty = e.hasTarget() ? e.targetY() : e.y() + e.headingY();
      playFacing(name, red, at, e.x(), e.y(), lift(row, e), view, e.x(), e.y(), tx, ty);
    }
  }

  /** {hit speed, timer phase (ms) at which the attack clip starts} of a character's attack. */
  private int[] windup(String name, boolean blue, int sequence) {
    return windups.computeIfAbsent(
        name + (blue ? "|b|" : "|r|") + sequence,
        k -> {
          VisualLookup.Unit unit = lookup.unit(name, blue);
          GameRow row = character(name);
          int hs = RealisticRenderer.integer(row, "VisualHitSpeed", 0);
          if (hs <= 0) hs = RealisticRenderer.integer(row, "HitSpeed", 1000);
          hs = Math.max(50, hs);
          if (unit == null) return new int[] {hs, 0};
          String action = "attack";
          if (sequence > 0 && unit.has("attack" + (sequence + 1))) {
            action = "attack" + (sequence + 1);
          }
          int id = unit.animation(action, 5);
          ScScene.Clip clip = id < 0 ? null : unit.scene.clip(id);
          // no attack clip (most buildings): the hit itself
          if (clip == null || clip.frameCount() <= 1) return new int[] {hs, 0};
          int af = clip.labelFrame("action_frame");
          float afMs = (af >= 0 ? af : clip.frameCount() * 0.6f) * 1000f / Math.max(1, clip.fps());
          return new int[] {hs, Math.max(0, hs - Math.round(afMs))};
        });
  }

  // ---------------------------------------------------------------------------------------------
  // footsteps (MoveEffect)

  /** When each running unit's run clip started (battle seconds). */
  private final Map<Integer, Double> runStart = new HashMap<>();

  /** Per character name and colour: {cycle, footfall 1, footfall 2} in seconds, or empty. */
  private final Map<String, double[]> cycles = new HashMap<>();

  private final Map<String, Double> lastStepSound = new HashMap<>();

  private static boolean running(int state) {
    return state == org.crforge.core.pathfinding.GridEntityState.MOVING
        || state == org.crforge.core.pathfinding.GridEntityState.SPAWN_PATHFIND
        || state == org.crforge.core.pathfinding.GridEntityState.INGAME_PATHFIND
        || state == org.crforge.core.pathfinding.GridEntityState.ROUTE_FOLLOWING_ALTERNATE;
  }

  /**
   * Plays each running ground troop's {@code MoveEffect} on its run clip's footfalls: the {@code
   * action_frame1} and {@code action_frame2} labels of {@code run1} (§1.5), the clip looped at its
   * fps from the tick the unit started running, as the renderer plays it. Flyers and units that did
   * not move this tick make none; at most {@link #MAX_FOOTSTEPS} play at once and each step sound
   * plays at most every {@link #STEP_SOUND_GAP} seconds.
   */
  private void footsteps(
      BattleFrame frame, Map<Integer, EntityView> before, ViewOrientation view, double now) {
    int live = 0;
    for (Instance in : instances) if (in.footstep && !in.done()) live++;
    java.util.Set<Integer> moving = new java.util.HashSet<>();
    for (EntityView e : frame.entities()) {
      if (e.kind() != EntityView.Kind.TROOP || e.air() || !running(e.state())) continue;
      moving.add(e.id());
      EntityView p = before.get(e.id());
      Double rs = runStart.get(e.id());
      if (rs == null || p == null || !running(p.state())) {
        rs = now;
        runStart.put(e.id(), rs);
      }
      if (p == null || (p.x() == e.x() && p.y() == e.y()) || e.hidden()) continue;
      GameRow row = character(e.name());
      String name = text(row, "MoveEffect");
      if (name == null) continue;
      boolean red = !view.blue(e.side());
      double[] c = cycle(e.name(), !red);
      if (c.length == 0) continue;
      for (int k = 1; k < 3; k++) {
        // footfalls rs + c[k] + n * cycle in [now, now + TICK)
        double first = rs + c[k] + Math.ceil((now - rs - c[k]) / c[0] - 1e-9) * c[0];
        for (double t = first; t < now + TICK - 1e-9; t += c[0]) {
          float a = (float) ((t - now) / TICK);
          int x = Math.round(p.x() + (e.x() - p.x()) * a);
          int y = Math.round(p.y() + (e.y() - p.y()) * a);
          EffectDefs.Effect fx = defs.effect(name, red);
          if (fx == null) break;
          for (EffectDefs.Sound s : fx.sounds()) {
            Double last = lastStepSound.get(s.ref());
            if (last != null && Math.abs(t - last) < STEP_SOUND_GAP) continue;
            lastStepSound.put(s.ref(), t);
            sounds.add(new PendingSound(t + s.delay(), s.ref()));
          }
          if (!fx.drawable() || live >= MAX_FOOTSTEPS) continue;
          Instance in = new Instance(fx, t, x, y, -1, 1f, 0, red);
          in.footstep = true;
          in.angle = screenAngle(view, p.x(), p.y(), e.x(), e.y());
          add(in);
          live++;
        }
      }
    }
    runStart.keySet().retainAll(moving);
  }

  /** {cycle, footfall 1, footfall 2} seconds of a character's run clip; empty without one. */
  private double[] cycle(String name, boolean blue) {
    return cycles.computeIfAbsent(
        name + (blue ? "|b" : "|r"),
        k -> {
          VisualLookup.Unit unit = lookup.unit(name, blue);
          if (unit == null) return new double[0];
          int id = unit.animation("run", 5);
          ScScene.Clip clip = id < 0 ? null : unit.scene.clip(id);
          if (clip == null || clip.frameCount() <= 1 || clip.fps() <= 0) return new double[0];
          double fps = clip.fps();
          int n = clip.frameCount();
          int f1 = clip.labelFrame("action_frame1");
          int f2 = clip.labelFrame("action_frame2");
          if (f1 < 0) f1 = 0;
          if (f2 < 0) f2 = (f1 + n / 2) % n;
          return new double[] {n / fps, f1 / fps, f2 / fps};
        });
  }

  // ---------------------------------------------------------------------------------------------
  // damage flash and unit filters (§8)

  /** A filter clip playing on a unit since {@code start} (battle seconds). */
  private record Flash(String file, String export, double start) {}

  private final Map<Integer, Flash> damageFlash = new HashMap<>();
  private final Map<Integer, Flash> targetFlash = new HashMap<>();

  /** Hits heard: {victim id, attacker x, attacker y, 1 if the attacker is known}. */
  private final List<int[]> struck = new ArrayList<>();

  /** Area effects' victims: victim id and the area's row name. */
  private record AreaVictim(int victim, String area, int side) {}

  private final List<AreaVictim> targetFilters = new ArrayList<>();

  private void flashes(Map<Integer, EntityView> after, ViewOrientation view, double now) {
    for (int[] s : struck) {
      EntityView v = after.get(s[0]);
      if (v == null) continue;
      Flash old = damageFlash.get(s[0]);
      // a hit with no known attacker does not override a directed one this tick
      if (s[3] == 0 && old != null && old.start() == now) continue;
      String base = text(character(v.name()), "DamageExportName");
      if (base == null) continue;
      String dir = "down";
      if (s[3] != 0) {
        float dx = view.x((float) v.x()) - view.x((float) s[1]);
        float dy = -(view.y((float) v.y()) - view.y((float) s[2])) * GROUND_SQUASH;
        if (dx * dx + dy * dy > 1e-3f) {
          if (Math.abs(dx) > Math.abs(dy)) dir = dx > 0 ? "right" : "left";
          else dir = dy > 0 ? "down" : "up";
        }
      }
      damageFlash.put(s[0], new Flash("sc/effects.sc", base + "_" + dir, now));
      if (DEBUG) System.err.printf("flash %.2f %s %d %s_%s%n", now, v.name(), s[0], base, dir);
    }
    struck.clear();
    for (AreaVictim av : targetFilters) {
      for (EffectDefs.Filter f : areaFilters(av.area(), !view.blue(av.side()))) {
        targetFlash.put(av.victim(), new Flash(f.file(), f.export(), now + f.delay()));
        if (DEBUG) System.err.printf("flash %.2f %d %s%n", now, av.victim(), f.export());
      }
    }
    targetFilters.clear();
  }

  /** The unit filters of an area effect's effects (Zap's {@code filter_target}). */
  private List<EffectDefs.Filter> areaFilters(String area, boolean red) {
    GameRow row = row("area_effect_objects", area);
    List<EffectDefs.Filter> out = new ArrayList<>();
    for (String name :
        new String[] {
          text(row, "OneShotEffect"), text(row, "HitEffect"), spellEffect(area),
        }) {
      EffectDefs.Effect fx = defs.effect(name, red);
      if (fx != null) out.addAll(fx.filters());
    }
    return out;
  }

  /**
   * The filter to apply to unit {@code entityId}'s sprite at frame time {@code seconds} (the same
   * seconds {@link #drawBelow} takes), or null for none: its row's {@code DamageExportName} ({@code
   * filter_damage[_wobble]}) with the suffix of the last hit's direction on screen, from attacker
   * to unit, and an area's {@code Filter} component on its victims (Zap's {@code filter_target}
   * white flash) on top: {@code {a, b, c, d, tx, ty, alpha, addR, addG, addB, mulR, mulG, mulB}}.
   * The matrix maps the unit's own display units (origin at its feet, y down, before mirroring), as
   * the deploy filter's does; alpha and the multipliers multiply the sprite's colour, the added
   * colour (0..1, straight) is added to it (ScDrawer's {@code u_add}).
   */
  public float[] damageFilter(int entityId, float seconds) {
    if (damageFlash.isEmpty() && targetFlash.isEmpty() && dashFilters.isEmpty()) return null;
    double t = clockAt(seconds);
    float[] d = sample(damageFlash.get(entityId), t);
    float[] w = sample(targetFlash.get(entityId), t);
    if (d == null) d = w;
    else if (w != null) for (int i = 7; i < 10; i++) d[i] = Math.min(1f, d[i] + w[i]);
    float[] dash = sample(dashFilters.get(entityId), t);
    if (dash == null) return d;
    return d == null ? dash : compose(dash, d);
  }

  /** Filter {@code outer} applied over {@code inner}: matrices composed, colours combined. */
  static float[] compose(float[] outer, float[] inner) {
    float[] o = new float[13];
    o[0] = outer[0] * inner[0] + outer[2] * inner[1];
    o[1] = outer[1] * inner[0] + outer[3] * inner[1];
    o[2] = outer[0] * inner[2] + outer[2] * inner[3];
    o[3] = outer[1] * inner[2] + outer[3] * inner[3];
    o[4] = outer[0] * inner[4] + outer[2] * inner[5] + outer[4];
    o[5] = outer[1] * inner[4] + outer[3] * inner[5] + outer[5];
    o[6] = outer[6] * inner[6];
    for (int i = 7; i < 10; i++) o[i] = Math.min(1f, outer[i] + inner[i]);
    for (int i = 10; i < 13; i++) o[i] = outer[i] * inner[i];
    return o;
  }

  private float[] sample(Flash f, double t) {
    if (f == null || t < f.start()) return null;
    return defs.filterAt(f.file(), f.export(), (float) (t - f.start()));
  }

  /** The battle clock (tick seconds) at a frame time in seconds. */
  private double clockAt(float seconds) {
    return prevClock + (curClock - prevClock) * alphaOf(seconds);
  }

  // ---------------------------------------------------------------------------------------------
  // screen shake

  /** Shakes started: {battle seconds, ShakeScreen strength}. */
  private final List<double[]> shakes = new ArrayList<>();

  /** GUESS: the shake's peak in display units, growing with the root of its strength. */
  private static double shakeAmplitude(double strength) {
    return 1.2 * Math.sqrt(strength / 100.0);
  }

  /** GUESS: how long a shake lasts, seconds (Fireball 800: 0.52 s, a king tower 2000: 1 s). */
  private static double shakeLength(double strength) {
    return 0.2 + strength / 2500.0;
  }

  /**
   * The camera offset of the screen shake at frame time {@code seconds} (as {@link #drawBelow}
   * takes it): {@code {dx, dy}} in display units (multiply by {@code
   * ArtProjection.pixelsPerDisplayUnit()}), x right and y up the screen; {0, 0} at rest. Effects
   * with {@code ShakeScreen} (Fireball 800, Rocket 1500, tower deaths 1000-2000, Zap 100) start a
   * decaying shake; the strongest one playing wins. The amplitude and length are GUESSes.
   */
  public float[] shake(float seconds) {
    if (shakes.isEmpty()) return new float[] {0, 0};
    double t = clockAt(seconds);
    double best = 0, bestAge = 0, bestSeed = 0;
    for (double[] s : shakes) {
      double age = t - s[0];
      double len = shakeLength(s[1]);
      if (age < 0 || age >= len) continue;
      double k = 1 - age / len;
      double amp = shakeAmplitude(s[1]) * k * k;
      if (amp > best) {
        best = amp;
        bestAge = age;
        bestSeed = s[0];
      }
    }
    if (best < 0.02) return new float[] {0, 0};
    double w = 2 * Math.PI * SHAKE_HZ * bestAge + bestSeed * 7.31;
    return new float[] {
      (float) (best * Math.sin(w)), (float) (best * 0.8 * Math.sin(w * 1.37 + 1.9))
    };
  }

  // ---------------------------------------------------------------------------------------------
  // reflected attacks (the Electro Giant's zap back)

  /** A reflect that struck back: the reflecting unit, the one struck, and the buff it gave. */
  private record Reflect(int from, int to, int side, int fx, int fy, int tx, int ty, String buff) {}

  private final List<Reflect> reflects = new ArrayList<>();

  /** A beam clip stretched from a unit's pivot to another unit while it plays. */
  private static final class Beam {
    EffectDefs.Sprite sprite;
    double start;
    float scale;
    float width;
    int from, to, pivot;
    String fromName;
    boolean blue;
    float lift;
    float fx, fy, pfx, pfy, tx, ty, ptx, pty;
    float heading;
  }

  private final List<Beam> beams = new ArrayList<>();

  /**
   * Each reflect that struck back plays the reflecting unit's row: {@code
   * ReflectedAttackTargetedEffect} as beams from its {@code ReflectedAttackTargetedEffectSources}
   * pivots ({@code attack_pivot_1..n} of its clip) to the unit struck, the effect's {@code
   * FlashWidth} stretched to the distance; {@code ReflectedAttackEffect} on the unit struck; and
   * the {@code ReflectedAttackBuff}'s {@code FilterFile}/{@code FilterExportName} on it (ZapFreeze:
   * {@code filter_cold}). Sounds come with the effects.
   */
  private void reflections(Map<Integer, EntityView> after, ViewOrientation view, double now) {
    Set<Long> seen = new java.util.HashSet<>();
    for (Reflect r : reflects) {
      if (!seen.add(((long) r.from() << 32) ^ r.to())) continue;
      EntityView g = after.get(r.from());
      EntityView v = after.get(r.to());
      String giant = g != null ? g.name() : null;
      if (giant == null) continue;
      GameRow row = character(giant);
      boolean red = !view.blue(r.side());
      int tx = v == null ? r.tx() : v.x(), ty = v == null ? r.ty() : v.y();
      float lift = v == null ? 0 : struckHeight(v);
      play(text(row, "ReflectedAttackEffect"), red, now, tx, ty, -1, 1f, lift);
      String beam = text(row, "ReflectedAttackTargetedEffect");
      EffectDefs.Effect fx = defs.effect(beam, red);
      if (fx != null) {
        queueSounds(fx, now);
        int sources =
            Math.max(1, RealisticRenderer.integer(row, "ReflectedAttackTargetedEffectSources", 1));
        float width = defs.flashWidth(beam);
        for (EffectDefs.Part part : fx.parts()) {
          if (part.swf() == null || width <= 0 || beams.size() > 64) continue;
          for (int k = 1; k <= sources; k++) {
            Beam b = new Beam();
            b.sprite = part.swf();
            b.start = now + part.delay();
            b.scale = part.scale();
            b.width = width;
            b.from = r.from();
            b.to = r.to();
            b.pivot = k;
            b.fromName = giant;
            b.blue = !red;
            b.lift = lift;
            b.fx = b.pfx = g.x();
            b.fy = b.pfy = g.y();
            b.tx = b.ptx = tx;
            b.ty = b.pty = ty;
            b.heading = headingDegrees(g, view, b.heading);
            beams.add(b);
          }
        }
      }
      GameRow buff = row("character_buffs", r.buff());
      String file = text(buff, "FilterFile"), export = text(buff, "FilterExportName");
      if (file != null && export != null) {
        targetFlash.put(r.to(), new Flash(file, export, now));
      }
    }
    reflects.clear();
    // the beams follow both ends
    for (Beam b : beams) {
      b.pfx = b.fx;
      b.pfy = b.fy;
      b.ptx = b.tx;
      b.pty = b.ty;
      EntityView g = after.get(b.from);
      if (g != null) {
        b.fx = g.x();
        b.fy = g.y();
        b.heading = headingDegrees(g, view, b.heading);
      }
      EntityView v = after.get(b.to);
      if (v != null) {
        b.tx = v.x();
        b.ty = v.y();
      }
    }
    beams.removeIf(b -> (simClockOr(now) - b.start) * b.sprite.fps() >= b.sprite.frames());
  }

  private double simClockOr(double now) {
    return Math.max(simClock, now);
  }

  /** Where a reflect strikes a unit: a flyer at its flying height, a tower at its shooter's. */
  private float struckHeight(EntityView v) {
    GameRow row = character(v.name());
    if (v.air()) return RealisticRenderer.integer(row, "FlyingHeight", 0);
    if (v.kind() == EntityView.Kind.TOWER) {
      return RealisticRenderer.integer(row, "AttachedCharacterHeight", 0);
    }
    return 0;
  }

  /** A unit's facing on screen as the renderer takes it (§2), or {@code last} without one. */
  private static float headingDegrees(EntityView e, ViewOrientation view, float last) {
    float fx = view.x((float) (e.x() + e.headingX())) - view.x((float) e.x());
    float fy = view.y((float) (e.y() + e.headingY())) - view.y((float) e.y());
    if (fx * fx + fy * fy < 1e-4f) return last;
    return ArtRules.facingDegrees(fx, fy);
  }

  /** The beams playing, drawn over the units. */
  private void drawBeams(ArtProjection proj, ViewOrientation view, float alpha) {
    float ppdu = proj.pixelsPerDisplayUnit();
    for (Beam b : beams) {
      double age = simClock - b.start;
      if (age < 0) continue;
      int frame = Math.min(b.sprite.frames() - 1, (int) Math.floor(age * b.sprite.fps()));
      float gx = b.pfx + (b.fx - b.pfx) * alpha, gy = b.pfy + (b.fy - b.pfy) * alpha;
      float[] pivot = pivot(b);
      float sx = proj.x(view, gx) + pivot[0] * ppdu;
      float sy = proj.y(view, gy) - pivot[1] * ppdu;
      float ex = proj.x(view, b.ptx + (b.tx - b.ptx) * alpha);
      float ey = proj.y(view, b.pty + (b.ty - b.pty) * alpha) + proj.height(b.lift);
      float dx = ex - sx, dy = ey - sy;
      float len = (float) Math.sqrt(dx * dx + dy * dy);
      if (len < 1f) continue;
      float s = ppdu * b.scale;
      transform.setToTranslation(sx, sy);
      transform.rotate((float) Math.toDegrees(Math.atan2(dy, dx)));
      transform.scale(len / b.width, -s);
      tint.set(1, 1, 1, 1);
      art.draw(b.sprite.scene(), b.sprite.id(), frame, transform, tint);
    }
  }

  /**
   * The offset (du, y down, the unit's scale applied) of a beam's source: the {@code
   * attack_pivot_<n>} child of the unit's idle clip at its facing, mirrored with it; 0 without one.
   */
  private float[] pivot(Beam b) {
    VisualLookup.Unit unit = lookup.unit(b.fromName, b.blue);
    if (unit == null) return new float[] {0, 0};
    int[] df = ArtRules.facingExport(b.heading, Math.max(1, unit.directions("idle")));
    int id = unit.animation("idle", df[0]);
    ScScene.Clip clip = id < 0 ? null : unit.scene.clip(id);
    if (clip == null || clip.frameCount() == 0) return new float[] {0, 0};
    int child = clip.childNamed("attack_pivot_" + b.pivot);
    if (child < 0) child = clip.childNamed("attack_pivot");
    if (child < 0) return new float[] {0, 0};
    int[] el = clip.frames()[0];
    ScScene.Bank bank = unit.scene.bank(clip.bank());
    for (int k = 0; k + 2 < el.length; k += 3) {
      if (el[k] != child) continue;
      int mi = el[k + 1];
      if (mi == ScScene.IDENTITY || bank == null || mi * 6 + 5 >= bank.matrices().length) break;
      float x = bank.matrices()[mi * 6 + 4], y = bank.matrices()[mi * 6 + 5];
      return new float[] {(df[1] == 1 ? -x : x) * unit.scale, y * unit.scale};
    }
    return new float[] {0, 0};
  }

  // ---------------------------------------------------------------------------------------------
  // king tower activation

  /** Kings whose activation was heard: {id, x, y, side}. */
  private final List<int[]> activations = new ArrayList<>();

  private final Set<Integer> activatedKings = new java.util.HashSet<>();

  /**
   * A king tower waking plays {@code King_player_activate} (red: its {@code EnemyVersion} {@code
   * King_enemy_activate}): the effect the king tower's {@code KingTowerActivationEffect} action
   * plays ({@code csv_logic/characters/king_tower.toml}), sounds only.
   */
  private void kingActivations(Map<Integer, EntityView> after, ViewOrientation view, double now) {
    for (int[] k : activations) {
      if (!activatedKings.add(k[0])) continue;
      play("King_player_activate", !view.blue(k[3]), now, k[1], k[2], -1, 1f, 0);
    }
    activations.clear();
  }

  // ---------------------------------------------------------------------------------------------
  // what the battle tells (optional)

  /** A hit (attacker's row, victim's id and position) or an area effect's hit (its row). */
  private record Event(boolean area, String name, int id, int side, int x, int y, int victim) {}

  private final List<Event> events = new ArrayList<>();

  /** Units the observer heard spawned by another object, by id: the spawner's row name. */
  private final Map<Integer, String> spawnedBy = new HashMap<>();

  private final Set<Integer> seenAreas = new java.util.HashSet<>();
  private boolean observed;

  /**
   * An observer to add to the battle's world ({@code world.addObserver(effects.observer())}) so the
   * layer hears each hit with its attacker, which plays the attacker's {@code DamageEffect} on the
   * victim, and the area effects that live less than a tick, such as Zap's, which no frame shows.
   * Without it, hits are guessed from hit points dropping next to an attacking enemy.
   */
  public WorldObserver observer() {
    observed = true;
    return new WorldObserver() {
      @Override
      public void typedHitDealt(
          int tick,
          WorldEntity source,
          WorldEntity target,
          int amount,
          int damageId,
          DamageResult result) {
        hit(source, target);
      }

      @Override
      public void afterPostHooks(
          int tick, List<WorldEntity> present, List<ProjectileEntity> projectiles) {
        Map<Integer, int[]> t = new HashMap<>();
        for (WorldEntity w : present) {
          var targeting = w.getTargeting();
          if (targeting == null) continue;
          t.put(
              w.getId(),
              new int[] {targeting.getAttackTimerMs(), targeting.getAttackSequenceIndex()});
        }
        timers = t;
      }

      @Override
      public void characterSpawned(
          int tick,
          org.crforge.core.battle.spawn.SpawnHost source,
          org.crforge.core.battle.unit.CharacterEntity child,
          int createdX,
          int createdY) {
        if (child == null || spawnedBy.size() > 4096) return;
        spawnedBy.put(child.getId(), source == null ? "" : String.valueOf(source.name()));
      }

      @Override
      public void projectileImpacted(
          int tick,
          ProjectileEntity projectile,
          WorldEntity target,
          int damage,
          DamageResult result) {
        if (target == null || projectile == null || struck.size() > 2000) return;
        struck.add(new int[] {target.getId(), projectile.getStartX(), projectile.getStartY(), 1});
      }

      @Override
      public void activation(
          int tick,
          org.crforge.core.battle.unit.TowerEntity king,
          org.crforge.core.battle.unit.ActivationEvent event) {
        if (king == null || event == null) return;
        var kind = event.kind();
        if (kind == org.crforge.core.battle.unit.ActivationEvent.Kind.ACTIVATING_STARTED
            || kind == org.crforge.core.battle.unit.ActivationEvent.Kind.EFFECT) {
          activations.add(new int[] {king.getId(), king.x(), king.y(), king.side()});
        }
      }

      @Override
      public void reflected(int tick, org.crforge.core.battle.unit.Reflection r) {
        // a reflect that struck back (its buff applied); none when the hit was out of its reach
        if (r == null || r.buff() == null || r.target() == null || r.struck() == null) return;
        if (reflects.size() > 500) return;
        WorldEntity t = r.target(), v = r.struck();
        reflects.add(
            new Reflect(t.getId(), v.getId(), t.side(), t.x(), t.y(), v.x(), v.y(), r.buff()));
      }

      @Override
      public void dashStarted(
          int tick,
          org.crforge.core.battle.unit.CharacterEntity unit,
          org.crforge.core.pathfinding.target.TargetView reference,
          int fromX,
          int fromY,
          int aimX,
          int aimY) {
        motion(Motion.Kind.DASH_START, unit, fromX, fromY);
      }

      @Override
      public void dashLanded(
          int tick,
          org.crforge.core.battle.unit.CharacterEntity unit,
          WorldEntity hit,
          int damage,
          boolean area) {
        if (unit != null) motion(Motion.Kind.DASH_LANDED, unit, unit.x(), unit.y());
      }

      @Override
      public void movementStateRequested(
          int tick, org.crforge.core.battle.unit.CharacterEntity unit, int from, int to) {
        int jumping = org.crforge.core.pathfinding.GridEntityState.JUMPING;
        if (unit != null && from == jumping && to != jumping) {
          motion(Motion.Kind.JUMP_LANDED, unit, unit.x(), unit.y());
        }
      }

      @Override
      public void chargeCompleted(
          int tick, org.crforge.core.battle.unit.CharacterEntity unit, int progress) {
        if (unit != null) motion(Motion.Kind.CHARGED, unit, unit.x(), unit.y());
      }

      @Override
      public void areaHit(
          int tick,
          WorldEntity attacker,
          WorldEntity victim,
          int damage,
          int hitId,
          DamageResult result) {
        hit(attacker, victim);
      }

      @Override
      public void areaEffectDamaged(
          int tick, AreaEffectEntity areaEffect, AreaDamage.Area area, AreaDamage.Outcome outcome) {
        areaEffect(areaEffect);
        if (areaEffect == null || areaEffect.getData() == null || outcome == null) return;
        if (outcome.damaged() == null || struck.size() > 2000) return;
        String name = areaEffect.getData().name();
        for (var v : outcome.damaged()) {
          struck.add(new int[] {v.id(), areaEffect.getX(), areaEffect.getY(), 1});
          targetFilters.add(new AreaVictim(v.id(), name, areaEffect.side()));
        }
      }

      @Override
      public void areaEffectFilterHit(
          int tick,
          AreaEffectEntity areaEffect,
          int x,
          int y,
          int radius,
          int halfWidth,
          int halfHeight) {
        areaEffect(areaEffect);
      }
    };
  }

  private void hit(BattleEntity source, WorldEntity target) {
    if (target != null && struck.size() <= 2000) {
      if (source instanceof WorldEntity a) {
        struck.add(new int[] {target.getId(), a.x(), a.y(), 1});
      } else {
        struck.add(new int[] {target.getId(), 0, 0, 0});
      }
    }
    if (source == null || target == null || source instanceof ProjectileEntity) return;
    if (!(source instanceof WorldEntity attacker)) return;
    if (events.size() > 2000) return;
    events.add(
        new Event(
            false,
            attacker.name(),
            attacker.getId(),
            attacker.side(),
            target.x(),
            target.y(),
            target.getId()));
  }

  private void areaEffect(AreaEffectEntity a) {
    if (a == null || a.getData() == null || events.size() > 2000) return;
    events.add(new Event(true, a.getData().name(), a.getId(), a.side(), a.getX(), a.getY(), -1));
  }

  private void testEffects(int tick, ViewOrientation view, double now) {
    for (String spec : TEST.split(";")) {
      String[] f = spec.trim().split("@");
      if (f.length < 4 || Integer.parseInt(f[1]) != tick) continue;
      int side = f.length > 4 ? Integer.parseInt(f[4]) : view.bottomSide();
      int x = Integer.parseInt(f[2]), y = Integer.parseInt(f[3]);
      if (f[0].startsWith("aeo:")) {
        events.add(new Event(true, f[0].substring(4), -tick * 31 - x, side, x, y, -1));
        for (Event ev : events) heard(ev, Map.of(), view, now);
        events.clear();
      } else {
        play(f[0], !view.blue(side), now, x, y, -1, 1f, 0);
      }
    }
  }

  private void heard(Event ev, Map<Integer, EntityView> after, ViewOrientation view, double now) {
    boolean red = !view.blue(ev.side());
    if (ev.area()) {
      if (!seenAreas.add(ev.id())) return;
      GameRow row = row("area_effect_objects", ev.name());
      play(text(row, "OneShotEffect"), red, now, ev.x(), ev.y(), -1, 1f, 0);
      // its sounds play with the card (BattleSounds)
      play(spellEffect(ev.name()), red, now, ev.x(), ev.y(), -1, 1f, 0, false);
      return;
    }
    EntityView victim = after.get(ev.victim());
    float lift = victim == null ? 0 : lift(character(victim.name()), victim);
    EntityView attacker = after.get(ev.id());
    int fx = attacker == null ? ev.x() : attacker.x();
    int fy = attacker == null ? ev.y() : attacker.y();
    playFacing(
        text(character(ev.name()), "DamageEffect"),
        red,
        now,
        ev.x(),
        ev.y(),
        lift,
        view,
        fx,
        fy,
        ev.x(),
        ev.y());
  }

  private void appeared(
      EntityView e, BattleFrame frame, boolean red, double now, ViewOrientation view) {
    switch (e.kind()) {
      case TROOP, BUILDING -> {
        GameRow row = character(e.name());
        String source = spawnedBy.remove(e.id());
        if (source == null) {
          play(text(row, "SpawnEffect"), red, now, e.x(), e.y(), -1, 1f, lift(row, e));
        } else {
          // a unit spawned by another (a Tombstone's, a Witch's skeletons) plays its spawner's
          // SpawnCharacterEffect, not its own card deploy (SpawnEffect)
          GameRow from = character(source);
          String fx = text(from, "SpawnCharacterEffect");
          if (fx == null && from == null)
            fx = text(row("area_effect_objects", source), "SpawnEffect");
          play(fx, red, now, e.x(), e.y(), -1, 1f, lift(row, e));
        }
      }
      case PROJECTILE -> {
        GameRow row = row("projectiles", e.name());
        play(text(row, "TrailEffect"), red, now, e.x(), e.y(), e.id(), 1f, 0);
        launched(e, frame, red, now, view);
      }
      case AREA_EFFECT -> {
        GameRow row = row("area_effect_objects", e.name());
        play(text(row, "OneShotEffect"), red, now, e.x(), e.y(), -1, 1f, 0);
        play(text(row, "LoopingEffect"), red, now, e.x(), e.y(), e.id(), 1f, 0);
        EffectDefs.Effect scaled = defs.effect(text(row, "ScaledEffect"), red);
        if (scaled != null) queueSounds(scaled, now);
        if (scaled != null && scaled.drawable()) {
          float hw = defs.swfHalfWidth(scaled);
          float want = e.radius() / 1000f * ArtProjection.DU_PER_TILE_X;
          float s = hw > 1 && want > 0 ? want / hw : 1f;
          add(new Instance(scaled, now, e.x(), e.y(), e.id(), s, 0, red));
        }
        // its sounds play with the card (BattleSounds)
        play(spellEffect(e.name()), red, now, e.x(), e.y(), -1, 1f, 0, false);
      }
      default -> {}
    }
  }

  private void vanished(EntityView p, boolean red, double now, ViewOrientation view) {
    switch (p.kind()) {
      case TROOP, BUILDING -> {
        GameRow row = character(p.name());
        play(text(row, "DeathEffect"), red, now, p.x(), p.y(), -1, DEATH_SCALE, lift(row, p));
      }
      case TOWER -> {
        if (destroyedTowers.add(p.id())) towerDestroyed(p, red, now);
      }
      case PROJECTILE -> {
        GameRow row = row("projectiles", p.name());
        int x = p.x(), y = p.y();
        // a projectile is removed within a step of its aim: land the hit there
        if (p.aimX() != 0 || p.aimY() != 0) {
          long dx = p.aimX() - p.x(), dy = p.aimY() - p.y();
          if (dx * dx + dy * dy < 1500L * 1500L) {
            x = p.aimX();
            y = p.aimY();
          }
        }
        playFacing(text(row, "HitEffect"), red, now, x, y, 0, view, p.x(), p.y(), x, y);
      }
      default -> {}
    }
  }

  /** Towers already exploded (vanished, or listed at 0 hit points). */
  private final Set<Integer> destroyedTowers = new java.util.HashSet<>();

  /** A crown tower falls: its death effect, and its ruin for the rest of the battle. */
  private void towerDestroyed(EntityView p, boolean red, double now) {
    GameRow row = character(p.name());
    String death = text(row, "DeathEffect");
    if (death == null) death = p.king() ? "King_player_die" : "TowerExplode";
    play(death, red, now, p.x(), p.y(), -1, 1f, 0);
    EffectDefs.Sprite ruin =
        defs.sprite(
            "sc/building_tower.sc",
            p.king() ? "Tower_destroyed_ground2" : "Tower_destroyed_ground1");
    if (ruin != null) ruins.add(new Ruin(ruin, p.x(), p.y()));
  }

  /**
   * When the layer starts mid-battle, the princess towers already destroyed: the arena's princess
   * towers stand mirrored across both its axes, so every mirror image of a standing one that no
   * tower stands at is a ruin.
   */
  private void ruinsOfMissingTowers(BattleFrame frame, ViewOrientation view) {
    List<int[]> standing = new ArrayList<>();
    for (EntityView e : frame.entities()) {
      if (e.kind() == EntityView.Kind.TOWER && !e.king()) standing.add(new int[] {e.x(), e.y()});
    }
    if (standing.isEmpty()) return;
    int w = view.widthUnits(), h = view.heightUnits();
    List<int[]> expected = new ArrayList<>();
    for (int[] s : standing) {
      for (int[] c :
          new int[][] {{s[0], s[1]}, {w - s[0], s[1]}, {s[0], h - s[1]}, {w - s[0], h - s[1]}}) {
        boolean known = false;
        for (int[] x : expected)
          known |= Math.abs(x[0] - c[0]) < 300 && Math.abs(x[1] - c[1]) < 300;
        if (!known) expected.add(c);
      }
    }
    EffectDefs.Sprite ruin = defs.sprite("sc/building_tower.sc", "Tower_destroyed_ground1");
    if (ruin == null) return;
    for (int[] c : expected) {
      boolean there = false;
      for (int[] s : standing) there |= Math.abs(s[0] - c[0]) < 300 && Math.abs(s[1] - c[1]) < 300;
      if (!there) ruins.add(new Ruin(ruin, c[0], c[1]));
    }
  }

  /**
   * A projectile appeared: its shooter, the closest character of its side that holds a target
   * (towers included), plays its {@code ProjectileEffect} at the projectile's start, lifted to the
   * shooter's flying height or a tower's turret, facing the aim.
   */
  private void launched(
      EntityView p, BattleFrame frame, boolean red, double now, ViewOrientation view) {
    EntityView best = null;
    long bestD = 2500L * 2500L;
    for (EntityView a : frame.entities()) {
      if (!a.isCharacter() || a.side() != p.side() || !a.hasTarget()) continue;
      if (!shoots(character(a.name()), p.name())) continue;
      long dx = a.x() - p.x(), dy = a.y() - p.y();
      long d = dx * dx + dy * dy;
      if (d < bestD) {
        bestD = d;
        best = a;
      }
    }
    if (best == null) return;
    GameRow row = character(best.name());
    String effect = text(row, "ProjectileEffect");
    if (effect == null) return;
    float lift =
        best.air()
            ? RealisticRenderer.integer(row, "FlyingHeight", 0)
            : RealisticRenderer.integer(row, "AttachedCharacterHeight", 0);
    int ax = p.aimX() != 0 || p.aimY() != 0 ? p.aimX() : best.targetX();
    int ay = p.aimX() != 0 || p.aimY() != 0 ? p.aimY() : best.targetY();
    playFacing(effect, red, now, p.x(), p.y(), lift, view, p.x(), p.y(), ax, ay);
  }

  /** Whether a character row names that projectile in one of its projectile columns. */
  private static boolean shoots(GameRow row, String projectile) {
    if (row == null || projectile == null) return false;
    for (Map.Entry<String, JsonNode> c : row.columns().entrySet()) {
      if (c.getKey().contains("Projectile")
          && c.getValue() != null
          && projectile.equals(c.getValue().asText())) {
        return true;
      }
    }
    return false;
  }

  /** A hit landed on {@code e}: the closest enemy attacking it plays its DamageEffect on it. */
  private void damaged(
      EntityView e, BattleFrame frame, boolean targetRed, double now, ViewOrientation view) {
    struck.add(new int[] {e.id(), 0, 0, 0});
    EntityView best = null;
    long bestD = Long.MAX_VALUE;
    for (EntityView a : frame.entities()) {
      if (!a.isCharacter() || a.side() == e.side() || !a.hasTarget()) continue;
      if (a.state() != org.crforge.core.pathfinding.GridEntityState.ATTACKING) continue;
      long tx = a.targetX() - e.x(), ty = a.targetY() - e.y();
      if (tx * tx + ty * ty > 600L * 600L) continue;
      long dx = a.x() - e.x(), dy = a.y() - e.y();
      long d = dx * dx + dy * dy;
      // melee range: projectiles bring their own HitEffect
      if (d
          > (long) (a.range() + e.radius() + a.radius() + 600)
              * (a.range() + e.radius() + a.radius() + 600)) {
        continue;
      }
      if (d < bestD) {
        bestD = d;
        best = a;
      }
    }
    if (best == null) return;
    struck.add(new int[] {e.id(), best.x(), best.y(), 1});
    GameRow row = character(best.name());
    playFacing(
        text(row, "DamageEffect"),
        !targetRed,
        now,
        e.x(),
        e.y(),
        lift(character(e.name()), e),
        view,
        best.x(),
        best.y(),
        e.x(),
        e.y());
  }

  /** The cast effect of the spell row named like an area effect (Lightning, Zap, ...). */
  private String spellEffect(String name) {
    for (String t : new String[] {"spells_other", "spells_characters", "spells_buildings"}) {
      GameRow row = row(t, name);
      if (row != null) return text(row, "Effect");
    }
    return null;
  }

  private Instance play(
      String name,
      boolean red,
      double now,
      int gx,
      int gy,
      int follow,
      float scale,
      float liftGame) {
    return play(name, red, now, gx, gy, follow, scale, liftGame, true);
  }

  private Instance play(
      String name,
      boolean red,
      double now,
      int gx,
      int gy,
      int follow,
      float scale,
      float liftGame,
      boolean withSounds) {
    EffectDefs.Effect fx = defs.effect(name, red);
    if (DEBUG && name != null) {
      System.err.printf(
          "fx %.2f %s %s at %d,%d%n",
          now,
          name,
          fx == null || !fx.drawable() ? "(nothing to draw)" : fx.parts().size() + " parts",
          gx,
          gy);
    }
    if (fx == null) return null;
    if (withSounds) queueSounds(fx, now);
    if (fx.shake() > 0 && shakes.size() < 64) {
      shakes.add(new double[] {now, fx.shake()});
      if (DEBUG) System.err.printf("shake %.2f %s %d%n", now, name, fx.shake());
    }
    if (!fx.drawable()) return null;
    // a one-shot does not follow; a looping one follows its owner and ends with it
    int f = follow;
    if (!fx.loop() && follow >= 0) {
      // trails and looping area effects are the only followed ones; a one-shot plays in place
      f = -1;
    }
    Instance in = new Instance(fx, now, gx, gy, f, scale, liftGame, red);
    if (fx.loop() && f < 0) {
      // a looping effect with no owner to end it plays for a while
      in.ownerGone = true;
      in.ownerGoneAt = now + 2.0;
    }
    add(in);
    return in;
  }

  /** Plays an effect facing from (fx, fy) towards (tx, ty), game units. */
  private void playFacing(
      String name,
      boolean red,
      double now,
      int gx,
      int gy,
      float liftGame,
      ViewOrientation view,
      int fx,
      int fy,
      int tx,
      int ty) {
    Instance in = play(name, red, now, gx, gy, -1, 1f, liftGame);
    if (in != null) in.angle = screenAngle(view, fx, fy, tx, ty);
  }

  /** Direction from one game point to another on screen, degrees clockwise from +x (y down). */
  private static float screenAngle(ViewOrientation view, int fx, int fy, int tx, int ty) {
    float dx = view.x((float) tx) - view.x((float) fx);
    float dy = -(view.y((float) ty) - view.y((float) fy)) * GROUND_SQUASH;
    if (dx * dx + dy * dy < 1e-3f) return 0;
    return (float) Math.toDegrees(Math.atan2(dy, dx));
  }

  /** A sound due at a battle time. */
  private record PendingSound(double at, String ref) {}

  private final List<PendingSound> sounds = new ArrayList<>();

  private void queueSounds(EffectDefs.Effect fx, double now) {
    if (sounds.size() > 4000) return;
    for (EffectDefs.Sound s : fx.sounds()) {
      sounds.add(new PendingSound(now + s.delay(), s.ref()));
      if (DEBUG) System.err.printf("sound %.2f %s%n", now + s.delay(), s.ref());
    }
  }

  /**
   * The sounds of the effects (and effect parts) that started since the last call, oldest first,
   * each as {battle seconds it starts at (tick * 0.05, as a string), sound reference such as {@code
   * soundbanks/card_common_knight/card_common_knight_hit}}. A part delayed by its {@code Time} is
   * returned once the battle clock (as far as updated or drawn) reaches it, with its own start
   * time. The same sound starting at the same moment is returned once.
   */
  public List<String[]> drainSounds() {
    double clock = Math.max(simClock, prevClock);
    List<String[]> out = new ArrayList<>();
    sounds.sort(java.util.Comparator.comparingDouble(PendingSound::at));
    for (Iterator<PendingSound> it = sounds.iterator(); it.hasNext(); ) {
      PendingSound s = it.next();
      if (s.at() > clock + 1e-6) break;
      it.remove();
      // many projectiles of one spell landing together play their sound once
      boolean dup = false;
      for (String[] o : out) dup |= o[1].equals(s.ref()) && String.valueOf(s.at()).equals(o[0]);
      if (!dup) out.add(new String[] {String.valueOf(s.at()), s.ref()});
    }
    return out;
  }

  private void add(Instance in) {
    instances.add(in);
  }

  private float lift(GameRow row, EntityView e) {
    if (!e.air() || row == null) return 0;
    return RealisticRenderer.integer(row, "FlyingHeight", 0);
  }

  private GameRow character(String name) {
    GameRow r = row("characters", name);
    return r != null ? r : row("buildings", name);
  }

  private GameRow row(String table, String name) {
    if (tables == null || name == null) return null;
    String key = table + "|" + name;
    if (rowCache.containsKey(key)) return rowCache.get(key);
    GameRow r = null;
    try {
      var t = tables.table(table);
      if (t != null && t.has(name)) r = t.row(name);
    } catch (RuntimeException ignored) {
      // a table this data version lacks
    }
    rowCache.put(key, r);
    return r;
  }

  private static String text(GameRow row, String column) {
    if (row == null) return null;
    JsonNode v = row.columns().get(column);
    if (v == null || v.isNull()) return null;
    String s = v.asText();
    return s.isBlank() ? null : s;
  }

  // ---------------------------------------------------------------------------------------------
  // simulation

  private float alphaOf(float seconds) {
    if (curTimeMs == prevTimeMs) return 1f;
    float a = (seconds * 1000f - prevTimeMs) / (curTimeMs - prevTimeMs);
    return Math.max(0f, Math.min(1f, a));
  }

  private void advance(double now) {
    if (simClock < 0) simClock = now;
    if (now <= simClock) return;
    double t = simClock;
    while (t < now - 1e-9) {
      double step = Math.min(MAX_STEP, now - t);
      t += step;
      for (Instance in : instances) stepInstance(in, t, (float) step);
    }
    simClock = now;
    instances.removeIf(Instance::done);
  }

  private void stepInstance(Instance in, double t, float dt) {
    for (Run r : in.runs) {
      if (r.done) continue;
      EffectDefs.Part part = r.part;
      double begin = in.start + part.delay();
      if (t < begin) continue;
      if (part.loop() && in.ownerGone && !r.started && t >= in.ownerGoneAt) {
        r.done = true;
        continue;
      }
      if (part.swf() != null) {
        r.started = true;
        double age = t - begin;
        if (part.loop()) {
          if (in.ownerGone && t >= in.ownerGoneAt) r.done = true;
        } else if (age * part.swf().fps() >= part.swf().frames()) {
          r.done = true;
        }
        continue;
      }
      EffectDefs.Emitter em = part.emitter();
      if (!r.started) {
        r.started = true;
        float life = rand(em.minLife, em.maxLife) / 1000f;
        r.emitterEnd = part.loop() ? Double.MAX_VALUE : begin + life;
        r.nextEmit = begin;
        // the opening burst
        int burst = em.maxInterval <= 0 ? em.count : 1;
        for (int i = 0; i < burst; i++) emit(in, r, em, i, (float) (t - begin));
        r.nextEmit = begin + Math.max(1e-3, rand(em.minInterval, em.maxInterval) / 1000f);
      } else {
        double end = Math.min(r.emitterEnd, in.ownerGone ? in.ownerGoneAt : Double.MAX_VALUE);
        if (em.maxInterval > 0) {
          while (r.nextEmit <= t && r.nextEmit < end) {
            if (r.particles.size() < em.count) {
              emit(in, r, em, r.particles.size(), (float) (t - r.nextEmit));
            }
            r.nextEmit += Math.max(1e-3, rand(em.minInterval, em.maxInterval) / 1000f);
          }
        } else if (t < end) {
          for (int i = r.particles.size(); i < em.count; i++) emit(in, r, em, i, 0f);
        }
      }
      for (Iterator<Particle> it = r.particles.iterator(); it.hasNext(); ) {
        Particle p = it.next();
        stepParticle(p, em, dt);
        if (p.age >= p.life) it.remove();
      }
      double end = Math.min(r.emitterEnd, in.ownerGone ? in.ownerGoneAt : Double.MAX_VALUE);
      if (r.particles.isEmpty() && t >= end) r.done = true;
    }
  }

  private void emit(Instance in, Run r, EffectDefs.Emitter em, int index, float preAge) {
    float s = in.scale * r.part.scale();
    Particle p = new Particle();
    p.sprite = em.sprites[rng.nextInt(em.sprites.length)];
    p.ax = in.ax;
    p.ay = in.ay;
    float angle;
    if (em.randomAngle || em.count <= 1) {
      angle = rand(em.minAngle, em.maxAngle);
    } else {
      angle =
          em.minAngle
              + (em.maxAngle - em.minAngle)
                  * ((index % em.count) + rng.nextFloat() * 0.3f)
                  / em.count;
    }
    if (em.relativeAngle) angle += in.angle;
    double rad = Math.toRadians(angle);
    float cx = (float) Math.cos(rad), cy = (float) Math.sin(rad) * GROUND_SQUASH;
    float radius = rand(em.minRadius, em.maxRadius) * RADIUS_DU;
    p.x = (cx * radius + em.startX) * s;
    p.y = cy * radius * s;
    if (em.areaRadius > 0) {
      double a = rng.nextDouble() * Math.PI * 2;
      float d = (float) Math.sqrt(rng.nextFloat()) * em.areaRadius * s;
      p.x += (float) Math.cos(a) * d;
      p.y += (float) Math.sin(a) * d * GROUND_SQUASH;
    }
    p.z = em.startZ * s;
    float speed = rand(em.minSpeed, em.maxSpeed) * s;
    p.vx = cx * speed;
    p.vy = cy * speed;
    p.vz = rand(em.minVz, em.maxVz) * s * Z_SCALE;
    p.life = Math.max(0.016f, rand(em.pMinLife, em.pMaxLife) / 1000f);
    float var = em.randomScale > 0 ? 1f + (rng.nextFloat() * 2f - 1f) * em.randomScale / 100f : 1f;
    var = Math.max(0.1f, var);
    p.s0 = em.startScale / 100f * s * var;
    p.s1 = em.endScale / 100f * s * var;
    p.rotSpeed = rand(em.rotMinSpeed, em.rotMaxSpeed);
    // particles born on or above the ground land on it; shadow-layer ones mirror below it
    // (a flyer's death plays at its height: its drops fall to the ground, not to that height)
    p.floor = -in.liftGame * ArtProjection.DU_PER_HEIGHT_UNIT;
    p.collide = r.part.layer() != EffectDefs.Layer.SHADOW && p.z >= 0 && em.gravity < 0;
    r.particles.add(p);
    if (preAge > 0) stepParticle(p, em, Math.min(preAge, (float) MAX_STEP));
  }

  private static void stepParticle(Particle p, EffectDefs.Emitter em, float dt) {
    p.age += dt;
    if (p.resting) return;
    if (em.inertia > 0) {
      // a drag on every axis: undamped, a death's elixir drops (vz 600) rose 4 tiles and its
      // puff (gravity +35) kept climbing for its whole 2.8 s life
      float k = (float) Math.exp(-em.inertia / 100f * INERTIA_SCALE * dt);
      p.vx *= k;
      p.vy *= k;
      p.vz *= k;
    }
    p.vz += em.gravity * Z_SCALE * dt;
    p.x += p.vx * dt;
    p.y += p.vy * dt;
    p.z += p.vz * dt;
    p.rot += p.rotSpeed * dt;
    if (p.collide && p.z < p.floor) {
      p.z = p.floor;
      if (em.stopOnBounce || em.noBounce || Math.abs(p.vz) < 40) {
        p.vz = 0;
        if (em.stopOnBounce || Math.abs(p.vx) + Math.abs(p.vy) < 2) p.resting = true;
        p.vx *= 0.5f;
        p.vy *= 0.5f;
      } else {
        p.vz = -p.vz * 0.3f;
        p.vx *= 0.6f;
        p.vy *= 0.6f;
      }
    }
  }

  private float rand(float a, float b) {
    return a == b ? a : a + rng.nextFloat() * (b - a);
  }

  // ---------------------------------------------------------------------------------------------
  // drawing

  /** Effects drawn under the units (ground decals, spell areas). */
  public void drawBelow(ScDrawer drawer, ArtProjection proj, ViewOrientation view, float seconds) {
    float alpha = alphaOf(seconds);
    advance(prevClock + (curClock - prevClock) * alpha);
    if (ruins.isEmpty() && !anyIn(true)) return;
    beginArt(drawer);
    float ppdu = proj.pixelsPerDisplayUnit();
    for (Ruin r : ruins) {
      transform.setToTranslation(proj.x(view, r.gx()), proj.y(view, r.gy()));
      transform.scale(ppdu, -ppdu);
      tint.set(1, 1, 1, 1);
      art.draw(r.sprite().scene(), r.sprite().id(), 0, transform, tint);
    }
    for (EffectDefs.Layer layer : EffectDefs.Layer.values()) {
      if (layer.below()) drawLayer(drawer, proj, view, layer, alpha);
    }
    art.end();
  }

  private void beginArt(ScDrawer drawer) {
    if (art == null) art = new EffectDrawer(cache);
    art.begin(drawer.batch());
  }

  /** Whether any started part draws below (or above) the units. */
  private boolean anyIn(boolean below) {
    for (Instance in : instances) {
      for (Run r : in.runs) {
        if (r.started && !r.done && r.part.layer().below() == below) return true;
      }
    }
    return false;
  }

  /** Effects drawn over the units (hits, explosions, deaths). */
  public void drawAbove(ScDrawer drawer, ArtProjection proj, ViewOrientation view, float seconds) {
    float alpha = alphaOf(seconds);
    advance(prevClock + (curClock - prevClock) * alpha);
    if (!anyIn(false) && beams.isEmpty()) return;
    beginArt(drawer);
    for (EffectDefs.Layer layer : EffectDefs.Layer.values()) {
      if (!layer.below()) drawLayer(drawer, proj, view, layer, alpha);
    }
    drawBeams(proj, view, alpha);
    art.end();
  }

  /**
   * Whether the renderer draws the Object layer of effects on the ground y-sorted with the units
   * ({@link #drawObjects}); {@link #drawAbove} then leaves them out.
   */
  private boolean objectsSorted;

  /** The screen y band {@link #drawLayer} keeps ground-level Object parts to: (lo, hi]. */
  private float sortLo = Float.NaN, sortHi;

  void sortObjectsWithUnits() {
    objectsSorted = true;
  }

  /**
   * Draws the Object-layer parts of the effects standing on the ground whose anchor's screen y lies
   * in {@code (lo, hi]} (§7: the Object layer is y-sorted together with the units), called by the
   * renderer between its ground poses, far first. False when there was nothing to draw (the batch
   * is then untouched).
   */
  boolean drawObjects(
      ScDrawer drawer,
      ArtProjection proj,
      ViewOrientation view,
      float seconds,
      float lo,
      float hi) {
    float alpha = alphaOf(seconds);
    boolean any = false;
    for (Instance in : instances) {
      if (in.liftGame != 0) continue;
      float y = proj.y(view, in.anchorY(alpha));
      if (!(y > lo && y <= hi)) continue;
      for (Run r : in.runs) {
        any |= r.started && !r.done && r.part.layer() == EffectDefs.Layer.OBJECT;
      }
    }
    if (!any) return false;
    beginArt(drawer);
    sortLo = lo;
    sortHi = hi;
    drawLayer(drawer, proj, view, EffectDefs.Layer.OBJECT, alpha);
    sortLo = Float.NaN;
    art.end();
    return true;
  }

  /** Frees the effect shader. */
  public void dispose() {
    if (art != null) art.dispose();
    art = null;
  }

  private void drawLayer(
      ScDrawer drawer,
      ArtProjection proj,
      ViewOrientation view,
      EffectDefs.Layer layer,
      float alpha) {
    float ppdu = proj.pixelsPerDisplayUnit();
    boolean shadow = layer == EffectDefs.Layer.SHADOW;
    for (Instance in : instances) {
      if (layer == EffectDefs.Layer.OBJECT && objectsSorted && in.liftGame == 0) {
        // the renderer draws these among the units, each in its band of screen y
        if (Float.isNaN(sortLo)) continue;
        float y = proj.y(view, in.anchorY(alpha));
        if (!(y > sortLo && y <= sortHi)) continue;
      } else if (!Float.isNaN(sortLo)) {
        continue;
      }
      // a shadow lies on the ground under a lifted effect
      float lift = shadow ? 0f : proj.height(in.liftGame);
      for (Run r : in.runs) {
        if (r.done || !r.started || r.part.layer() != layer) continue;
        if (r.part.swf() != null) {
          EffectDefs.Sprite sp = r.part.swf();
          double age = simClock - (in.start + r.part.delay());
          int frame = (int) Math.floor(Math.max(0, age) * sp.fps());
          if (!r.part.loop()) frame = Math.min(frame, sp.frames() - 1);
          float s = ppdu * in.scale * r.part.scale();
          transform.setToTranslation(
              proj.x(view, in.anchorX(alpha)), proj.y(view, in.anchorY(alpha)) + lift);
          transform.scale(s, -s);
          if (shadow) tint.set(0, 0, 0, SHADOW_ALPHA);
          else tint.set(1, 1, 1, 1);
          art.draw(sp.scene(), sp.id(), frame, transform, tint);
          continue;
        }
        EffectDefs.Emitter em = r.part.emitter();
        // GUESS: additive glows of one emitter overlapping do not add up to a blinding blob in the
        // game (a death's 12 purple glows); their alpha shares sqrt(GLOW_CROWD / alive)
        int n = r.particles.size();
        float crowd = n > GLOW_CROWD ? (float) Math.sqrt(GLOW_CROWD / (double) n) : 1f;
        for (Particle p : r.particles) {
          drawParticle(drawer, proj, view, em, p, ppdu, lift, shadow, crowd);
        }
      }
    }
  }

  private void drawParticle(
      ScDrawer drawer,
      ArtProjection proj,
      ViewOrientation view,
      EffectDefs.Emitter em,
      Particle p,
      float ppdu,
      float lift,
      boolean shadow,
      float crowd) {
    float a = 1f;
    if (em.fadeIn > 0) a = Math.min(a, p.age * 1000f / em.fadeIn);
    if (em.fadeOut > 0) a = Math.min(a, (p.life - p.age) * 1000f / em.fadeOut);
    if (p.sprite.additive()) a *= crowd;
    if (a <= 0.01f) return;
    float k = Math.min(1f, p.age / p.life);
    float s = (p.s0 + (p.s1 - p.s0) * k) * ppdu;
    if (Math.abs(s) < 1e-3f) return;
    EffectDefs.Sprite sp = p.sprite;
    int frame;
    if (sp.frames() <= 1) frame = 0;
    else if (em.loopClip) frame = (int) (p.age * sp.fps());
    else frame = Math.min(sp.frames() - 1, (int) (p.age * sp.fps()));
    float wx = proj.x(view, p.ax) + p.x * ppdu;
    float wy = proj.y(view, p.ay) - p.y * ppdu + p.z * ppdu + lift;
    float rot = p.rot;
    if (em.rotateToDirection) {
      // a falling (ballistic) particle points along its arc on screen; a floating one (a death's
      // puff, gravity >= 0) along the ground, so its glows lie flat rather than stand up
      float dx = p.vx, dy = em.gravity < 0 ? p.vy - p.vz : p.vy;
      if (dx * dx + dy * dy > 1f) p.heading = (float) Math.toDegrees(Math.atan2(dy, dx));
      rot += p.heading;
    }
    transform.setToTranslation(wx, wy);
    if (rot != 0) transform.rotate(-rot);
    transform.scale(s, -s);
    if (shadow) tint.set(0, 0, 0, SHADOW_ALPHA * a);
    else tint.set(1, 1, 1, a);
    art.draw(sp.scene(), sp.id(), frame, transform, tint);
  }
}
