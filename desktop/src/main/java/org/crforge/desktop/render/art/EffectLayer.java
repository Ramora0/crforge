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
 *       plays its attacker's, and area effects too short-lived for any frame (Zap) still play.
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
 * {@code Inertia} a drag of Inertia/100 per second, negative {@code ParticleGravity} pulls down.
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

  /** Longest simulation step, seconds. */
  private static final double MAX_STEP = 1 / 120.0;

  /** Cap on live effects, oldest one-shots dropped beyond it. */
  private static final int MAX_INSTANCES = 400;

  private final ArtCache cache;
  private final EffectDefs defs;
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
    }
    curClock = frame.tick() * TICK;
    curTimeMs = frame.timeMs();
    if (previous == null) {
      prevClock = curClock;
      prevTimeMs = curTimeMs;
      if (simClock < 0) simClock = curClock;
      if (frame.tick() > 0 && ruins.isEmpty()) ruinsOfMissingTowers(frame, view);
      // what the observer heard before the first frame (a fast-forward) is not replayed
      events.clear();
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
    }
    for (Event ev : events) heard(ev, after, view, now);
    if (TEST != null) testEffects(frame.tick(), view, now);
    events.clear();
    if (seenAreas.size() > 4096) seenAreas.clear();
    while (instances.size() > MAX_INSTANCES) instances.remove(0);
  }

  // ---------------------------------------------------------------------------------------------
  // what the battle tells (optional)

  /** A hit (attacker's row, victim's id and position) or an area effect's hit (its row). */
  private record Event(boolean area, String name, int id, int side, int x, int y, int victim) {}

  private final List<Event> events = new ArrayList<>();
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
      play(spellEffect(ev.name()), red, now, ev.x(), ev.y(), -1, 1f, 0);
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
        play(text(row, "SpawnEffect"), red, now, e.x(), e.y(), -1, 1f, lift(row, e));
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
        play(spellEffect(e.name()), red, now, e.x(), e.y(), -1, 1f, 0);
      }
      default -> {}
    }
  }

  private void vanished(EntityView p, boolean red, double now, ViewOrientation view) {
    switch (p.kind()) {
      case TROOP, BUILDING -> {
        GameRow row = character(p.name());
        play(text(row, "DeathEffect"), red, now, p.x(), p.y(), -1, 1f, lift(row, p));
      }
      case TOWER -> {
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
    queueSounds(fx, now);
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
    p.vz = rand(em.minVz, em.maxVz) * s;
    p.life = Math.max(0.016f, rand(em.pMinLife, em.pMaxLife) / 1000f);
    float var = em.randomScale > 0 ? 1f + (rng.nextFloat() * 2f - 1f) * em.randomScale / 100f : 1f;
    var = Math.max(0.1f, var);
    p.s0 = em.startScale / 100f * s * var;
    p.s1 = em.endScale / 100f * s * var;
    p.rotSpeed = rand(em.rotMinSpeed, em.rotMaxSpeed);
    // particles born on or above the ground land on it; shadow-layer ones mirror below it
    p.collide = r.part.layer() != EffectDefs.Layer.SHADOW && p.z >= 0 && em.gravity < 0;
    r.particles.add(p);
    if (preAge > 0) stepParticle(p, em, Math.min(preAge, (float) MAX_STEP));
  }

  private static void stepParticle(Particle p, EffectDefs.Emitter em, float dt) {
    p.age += dt;
    if (p.resting) return;
    if (em.inertia > 0) {
      float k = (float) Math.exp(-em.inertia / 100f * dt);
      p.vx *= k;
      p.vy *= k;
    }
    p.vz += em.gravity * dt;
    p.x += p.vx * dt;
    p.y += p.vy * dt;
    p.z += p.vz * dt;
    p.rot += p.rotSpeed * dt;
    if (p.collide && p.z < 0) {
      p.z = 0;
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
    if (!anyIn(false)) return;
    beginArt(drawer);
    for (EffectDefs.Layer layer : EffectDefs.Layer.values()) {
      if (!layer.below()) drawLayer(drawer, proj, view, layer, alpha);
    }
    art.end();
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
      float lift = proj.height(in.liftGame);
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
        for (Particle p : r.particles) drawParticle(drawer, proj, view, em, p, ppdu, lift, shadow);
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
      boolean shadow) {
    float a = 1f;
    if (em.fadeIn > 0) a = Math.min(a, p.age * 1000f / em.fadeIn);
    if (em.fadeOut > 0) a = Math.min(a, (p.life - p.age) * 1000f / em.fadeOut);
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
      float dx = p.vx, dy = p.vy - p.vz;
      if (dx * dx + dy * dy > 1f) rot += (float) Math.toDegrees(Math.atan2(dy, dx));
    }
    transform.setToTranslation(wx, wy);
    if (rot != 0) transform.rotate(-rot);
    transform.scale(s, -s);
    if (shadow) tint.set(0, 0, 0, SHADOW_ALPHA * a);
    else tint.set(1, 1, 1, a);
    art.draw(sp.scene(), sp.id(), frame, transform, tint);
  }
}
