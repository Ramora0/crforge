/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.render.art;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.PolygonSpriteBatch;
import com.badlogic.gdx.math.Affine2;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.Disposable;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.desktop.battle.BattleFrame;
import org.crforge.desktop.battle.EntityView;
import org.crforge.desktop.battle.UnitStatus;
import org.crforge.desktop.render.ViewOrientation;

/**
 * Draws a battle frame with the game's own art from the {@link ArtCache}, following
 * docs/realistic-rendering.md: each troop, building, tower and projectile as the movie clip its
 * table row names ({@code FileName} with {@code BlueExportName} / {@code RedExportName}), the
 * animation from the unit's state (§1.3), attack clips lined up with the hit (§1.4), the facing
 * export from the logic heading (§2), towers as base, rider and top (§5), projectiles by their
 * flight (§6), silhouette shadows and the deploy filter (§8). What the cache lacks is skipped.
 */
public final class RealisticRenderer implements Disposable {

  private static final float TICK_SECONDS = 0.05f;
  private static final float SHADOW_ALPHA = 0.38f;
  private static final int LAYER_GROUND_SHADOW = -1;
  private static final int LAYER_GROUND = 0;
  private static final int LAYER_AIR = 1;

  /** The king tower's clip: frame 0 idle, 1..96 waking (3.2 s at 30 fps), 97 active. */
  private static final int KING_ACTIVE_FRAME = 97;

  private final ArtCache cache;
  private final PolygonSpriteBatch batch;
  private final ScDrawer drawer;
  private final VisualLookup lookup;
  private final Map<Long, AnimState> anims = new HashMap<>();
  private final Affine2 transform = new Affine2();
  private final Affine2 local = new Affine2();
  private final Color tint = new Color();
  private ArtProjection proj;
  private Map<Integer, int[]> attacks = Map.of();
  private Map<Integer, int[]> flights = Map.of();

  /** The tick each king tower's activating run started, by entity id. */
  private final Map<Integer, Integer> kingWoke = new HashMap<>();

  /**
   * Each character's motion ({@code BattleAdapter.unitMotion}: height, dash time, charge, dash
   * wind-up, landing hold) after the last tick and the tick before it, by entity id.
   */
  private Map<Integer, int[]> motions = Map.of();

  private Map<Integer, int[]> motionsBefore = Map.of();

  /** The dashes a chained dash (the Golden Knight's) has made, by entity id. */
  private final Map<Integer, Integer> dashLinks = new HashMap<>();

  /** Tells the renderer when each king tower starts waking (its clip's activation frames). */
  public org.crforge.core.battle.unit.WorldObserver observer() {
    return new org.crforge.core.battle.unit.WorldObserver() {
      @Override
      public void activation(
          int tick,
          org.crforge.core.battle.unit.TowerEntity king,
          org.crforge.core.battle.unit.ActivationEvent event) {
        if (event.kind() == org.crforge.core.battle.unit.ActivationEvent.Kind.ACTIVATING_STARTED) {
          kingWoke.putIfAbsent(king.getId(), tick);
        }
      }

      @Override
      public void afterPostHooks(
          int tick,
          List<org.crforge.core.battle.unit.WorldEntity> present,
          List<org.crforge.core.battle.projectile.ProjectileEntity> projectiles) {
        Map<Integer, int[]> m = new HashMap<>();
        for (var w : present) {
          if (w instanceof org.crforge.core.battle.unit.CharacterEntity c) {
            int[] v = org.crforge.desktop.battle.BattleAdapter.unitMotion(c);
            if (v != null) m.put(c.getId(), v);
          }
        }
        motionsBefore = motions;
        motions = m;
      }

      @Override
      public void chainDashed(
          int tick,
          org.crforge.core.battle.unit.CharacterEntity unit,
          org.crforge.core.battle.unit.WorldEntity next,
          int count,
          int x,
          int y) {
        if (unit != null) dashLinks.put(unit.getId(), count);
      }

      @Override
      public void chainDashEnded(
          int tick,
          org.crforge.core.battle.unit.CharacterEntity unit,
          int count,
          org.crforge.core.pathfinding.target.TargetView reference) {
        if (unit != null) dashLinks.remove(unit.getId());
      }
    };
  }

  /**
   * A ground character's height above the ground this frame, game units: the core's live height (a
   * dash's height profile, a river jump's arc) interpolated between the last two ticks.
   */
  public float groundHeight(int id, float tickAlpha) {
    int[] now = motions.get(id);
    if (now == null) return 0f;
    int[] before = motionsBefore.get(id);
    float z0 = before == null ? now[0] : before[0];
    return Math.max(0f, z0 + (now[0] - z0) * tickAlpha);
  }

  private final Color shadowColor = new Color(0f, 0f, 100 / 255f, 0.5f);
  private EffectLayer effects;

  /**
   * The effect layer whose damage flashes ({@code filter_damage}) the units show, and whose Object
   * layer on the ground the units sort among.
   */
  public void setEffects(EffectLayer effects) {
    this.effects = effects;
    if (effects != null) effects.sortObjectsWithUnits();
  }

  private float effectClock;

  /** The effect layer's clock this frame (seconds), for the damage flashes. */
  public void setEffectClock(float seconds) {
    this.effectClock = seconds;
  }

  /**
   * The arena's shadow colour: the location row's {@code ShadowR/G/B} (0..255, absent = 0) and
   * {@code ShadowA} (percent).
   */
  public void setShadowColor(int r, int g, int b, int alphaPercent) {
    shadowColor.set(r / 255f, g / 255f, b / 255f, Math.max(0, Math.min(100, alphaPercent)) / 100f);
  }

  public RealisticRenderer(ArtCache cache) {
    this.cache = cache;
    this.batch = new PolygonSpriteBatch(8000);
    this.drawer = new ScDrawer(batch, cache);
    this.lookup = new VisualLookup(cache);
  }

  public ArtCache cache() {
    return cache;
  }

  public ScDrawer drawer() {
    return drawer;
  }

  /**
   * The per-tick state the view does not carry: attack timers ({@code {attackTimerMs,
   * attackSequenceIndex, loadTimerMs}}) and projectile flights ({@code {z, startX, startY}}), by
   * entity id (see {@code BattleAdapter.attackTimers} / {@code projectileFlights}).
   */
  public void setTickData(Map<Integer, int[]> attacks, Map<Integer, int[]> flights) {
    this.attacks = attacks == null ? Map.of() : attacks;
    this.flights = flights == null ? Map.of() : flights;
  }

  private static final class AnimState {
    String action = "";
    float actionStart;
    float facingDegrees;
    float deployStart = -1;
    float activatedAt = -1;
    int heldFrame;
    int state = -1;
    float stateStart;
    float jumpEnd = -1;
  }

  /** One clip to draw this frame. */
  private record Pose(
      ScScene scene,
      int id,
      int frame,
      float x,
      float groundY,
      float lift,
      float scale,
      boolean flip,
      float rotation,
      float[] filter,
      float alpha,
      VisualLookup.Unit shadowOf,
      List<ScDrawer.Override> overrides,
      float sortY,
      int layer) {}

  /**
   * Draws the entities of {@code frame}, positions interpolated from {@code previous} (may be null)
   * by {@code alpha} in [0, 1].
   */
  public void render(
      BattleFrame frame,
      BattleFrame previous,
      float alpha,
      GameTables tables,
      ViewOrientation view,
      ArtProjection proj,
      Matrix4 projection) {
    this.proj = proj;
    lookup.setTables(tables);
    float seconds =
        (previous == null
                ? frame.tick()
                : previous.tick() + (frame.tick() - previous.tick()) * alpha)
            * TICK_SECONDS;
    Map<Integer, EntityView> before = new HashMap<>();
    if (previous != null) for (EntityView e : previous.entities()) before.put(e.id(), e);

    List<Pose> poses = new ArrayList<>();
    for (EntityView e : frame.entities()) {
      EntityView p = before.get(e.id());
      float gx = p == null ? e.x() : p.x() + (e.x() - p.x()) * alpha;
      float gy = p == null ? e.y() : p.y() + (e.y() - p.y()) * alpha;
      float sx = proj.x(view, gx);
      float sy = proj.y(view, gy);
      switch (e.kind()) {
        case PROJECTILE -> poseProjectile(poses, e, p, alpha, sx, sy, view);
        case AREA_EFFECT -> {} // drawn by the EffectLayer
        default -> poseCharacter(poses, e, sx, sy, seconds, alpha, view);
      }
    }
    poses.sort(
        Comparator.comparingInt(Pose::layer)
            .thenComparing(Comparator.comparingDouble((Pose q) -> -q.sortY())));

    batch.setProjectionMatrix(projection);
    drawer.begin();
    drawer.setSilhouette(true);
    for (Pose q : poses) if (q.shadowOf() != null) drawShadow(q);
    drawer.setSilhouette(false);
    // the effects' Object layer on the ground goes y-sorted among the ground poses (§7)
    float band = effects == null ? Float.NEGATIVE_INFINITY : Float.POSITIVE_INFINITY;
    for (Pose q : poses) {
      if (band != Float.NEGATIVE_INFINITY && q.layer() >= LAYER_GROUND) {
        float to = q.layer() == LAYER_GROUND ? q.sortY() : Float.NEGATIVE_INFINITY;
        objectEffects(to, band, view);
        band = to;
      }
      drawPose(q);
    }
    if (band != Float.NEGATIVE_INFINITY) objectEffects(Float.NEGATIVE_INFINITY, band, view);
    drawer.end();
    if (anims.size() > 4 * frame.entities().size() + 64) {
      Set<Long> live = new HashSet<>();
      for (EntityView e : frame.entities()) {
        for (long k = 0; k < 4; k++) live.add(e.id() * 4L + k);
      }
      anims.keySet().retainAll(live);
    }
  }

  /** The effects' ground Object-layer parts whose screen y lies in (lo, hi]. */
  private void objectEffects(float lo, float hi, ViewOrientation view) {
    if (effects.drawObjects(drawer, proj, view, effectClock, lo, hi)) drawer.setSilhouette(false);
  }

  // ---------------------------------------------------------------------------------------- units

  private void poseCharacter(
      List<Pose> out,
      EntityView e,
      float x,
      float y,
      float seconds,
      float tickAlpha,
      ViewOrientation view) {
    int state = e.state();
    // a troop the Fisherman's hook pulls (FOLLOWING_REMOVED) or a Fisherman it drags to a building
    // (FOLLOWING_REMOVED_BUILDING) is still on the arena, drawn where the hook has it
    if (state == GridEntityState.WAITING_TO_DEPLOY) return;
    boolean blue = view.blue(e.side());
    VisualLookup.Unit unit = lookup.unit(e.name(), blue);
    if (unit == null) return;
    // a crown tower at 0 hit points is already its ruin (the effect layer draws it)
    if (e.kind() == EntityView.Kind.TOWER && e.maxHitPoints() > 0 && e.hitPoints() <= 0) return;
    float alpha = e.hidden() ? 0.35f : 1f;
    float scale = proj.pixelsPerDisplayUnit() * unit.scale;
    // a ground unit off the ground (a dash's leap, a jump over the river) is lifted by the core's
    // height and drawn over the ground units it passes; its shadow stays on the ground
    float height = e.air() ? 0f : groundHeight(e.id(), tickAlpha);
    float lift = e.air() ? proj.height(unit.flyingHeight) : proj.height(height);
    int layer = e.air() || height > 0 ? LAYER_AIR : LAYER_GROUND;
    AnimState st =
        anims.computeIfAbsent(
            e.id() * 4L,
            k -> {
              AnimState s = new AnimState();
              s.facingDegrees = blue ? 0f : 180f; // towards the enemy until the logic turns it
              return s;
            });
    updateFacing(st, e, view);

    if (unit.isTowerLike()) {
      ScScene.Clip base = unit.scene.clip(unit.baseExport);
      List<ScDrawer.Override> overrides = null;
      int f = 0;
      boolean flip = false;
      int turret = base == null ? -1 : base.childNamed("turret");
      int kingDummy = base == null ? -1 : base.childNamed("king_dummy");
      if (turret >= 0 || kingDummy >= 0) {
        // the king tower: the clip's timeline is its waking (from the core's activating run),
        // the turret's its aim
        Integer woke = kingWoke.get(e.id());
        if (woke != null && st.activatedAt < 0) st.activatedAt = woke * TICK_SECONDS;
        if (st.activatedAt >= 0) {
          f = Math.min(KING_ACTIVE_FRAME, 1 + (int) ((seconds - st.activatedAt) * base.fps()));
        }
        overrides = new ArrayList<>();
        if (turret >= 0) {
          int n = unit.scene.frameCount(base.children()[turret]);
          int[] rf = halfCircleFrame(st.facingDegrees, n);
          overrides.add(new ScDrawer.Override("turret", null, -1, rf[0], rf[1] == 1));
        }
        if (kingDummy >= 0) {
          ScScene kings = cache.scene("sc/chr_king.sc");
          int king = kings == null ? -1 : kings.export(blue ? "King_blue" : "King_red");
          if (king >= 0) overrides.add(new ScDrawer.Override("king_dummy", kings, king, f, false));
        }
      } else if (unit.rotationOnTimeline && unit.rotationFrames > 1) {
        int[] rf = halfCircleFrame(st.facingDegrees, unit.rotationFrames);
        f = rf[0];
        flip = rf[1] == 1;
      } else if (base != null) {
        f = (int) Math.floor(seconds * base.fps());
      }
      out.add(
          new Pose(
              unit.scene,
              unit.baseExport,
              f,
              x,
              y,
              lift,
              scale,
              flip,
              0,
              null,
              alpha,
              unit,
              overrides,
              y,
              layer));
      if (unit.attached != null) {
        float riderLift = lift + proj.height(unit.attachedHeight);
        AnimState rs = anims.computeIfAbsent(e.id() * 4L + 1, k -> new AnimState());
        rs.facingDegrees = st.facingDegrees;
        poseAnimated(
            out,
            e,
            unit.attached,
            rs,
            x,
            y,
            riderLift,
            seconds,
            tickAlpha,
            alpha,
            null,
            y - 0.01f,
            layer);
      }
      if (unit.topExport >= 0) {
        out.add(
            new Pose(
                unit.scene,
                unit.topExport,
                0,
                x,
                y,
                lift,
                scale,
                false,
                0,
                null,
                alpha,
                null,
                null,
                y - 0.02f,
                layer));
      }
      return;
    }
    // a hidden unit (underground, invisible) casts no shadow
    poseAnimated(
        out,
        e,
        unit,
        st,
        x,
        y,
        lift,
        seconds,
        tickAlpha,
        alpha,
        e.hidden() ? null : unit,
        y,
        layer);
  }

  private static void updateFacing(AnimState st, EntityView e, ViewOrientation view) {
    float hx = e.headingX(), hy = e.headingY();
    if (e.kind() == EntityView.Kind.TOWER && e.hasTarget()) {
      // a crown tower is no character: the core never turns its facing (only the Royal Chef's
      // throw does), so its turret and rider aim at the reference it holds
      hx = e.targetX() - e.x();
      hy = e.targetY() - e.y();
    }
    float fx = view.x((float) (e.x() + hx)) - view.x((float) e.x());
    float fy = view.y((float) (e.y() + hy)) - view.y((float) e.y());
    if (fx * fx + fy * fy < 1e-4f) return;
    st.facingDegrees = ArtRules.facingDegrees(fx, fy);
  }

  /** A turret's timeline frame (0-based) for its facing over a mirrored half circle. */
  private static int[] halfCircleFrame(float theta, int n) {
    int[] f = ArtRules.facingExport(theta, n);
    return new int[] {f[0] - 1, f[1]};
  }

  private void poseAnimated(
      List<Pose> out,
      EntityView e,
      VisualLookup.Unit unit,
      AnimState st,
      float x,
      float y,
      float lift,
      float seconds,
      float tickAlpha,
      float alpha,
      VisualLookup.Unit shadowOf,
      float sortY,
      int layer) {
    float scale = proj.pixelsPerDisplayUnit() * unit.scale;
    String action;
    float clipTime = -1; // seconds into the clip when timed to the logic, else looped from start
    float[] filter = null;
    int state = e.state();
    if (state != st.state) {
      if (st.state == GridEntityState.JUMPING) st.jumpEnd = seconds;
      st.state = state;
      st.stateStart = seconds;
    }
    int[] motion = motions.get(e.id());
    switch (state) {
      case GridEntityState.MOVING,
          GridEntityState.SPAWN_PATHFIND,
          GridEntityState.INGAME_PATHFIND,
          GridEntityState.ROUTE_FOLLOWING_ALTERNATE,
          GridEntityState.STANDING -> {
        float windup = motion == null ? 0 : (motion[3] + 50f * (1f - tickAlpha)) / 1000f;
        float landing = st.jumpEnd < 0 ? -1 : seconds - st.jumpEnd;
        if (motion != null && motion[3] > 0) {
          // a dash's wind-up: the unit stands, crouching into a leap at its end (§1.6)
          float t = unit.has("jump") ? DashArt.windupTime(clipOf(unit, "jump"), windup) : -1;
          action = t >= 0 ? "jump" : "idle";
          clipTime = t;
        } else if (landing >= 0 && unit.has("landing") && landing < clipLength(unit, "landing")) {
          action = "landing"; // the evolved Royal Hogs' landing after a jump over the river
          clipTime = landing;
        } else if (state == GridEntityState.STANDING) {
          action = "idle";
        } else {
          boolean charged = motion != null && motion[2] >= DashArt.CHARGE_COMPLETE;
          action = charged && unit.has("charge") ? "charge" : "run";
        }
      }
      case GridEntityState.DASHING -> {
        if (unit.has("jump") && unit.integer("JumpHeight", 0) > 0) {
          // a leap (Mega Knight): the clip's jump part over the dash's constant time, its recover
          // part over the landing hold
          action = "jump";
          clipTime =
              DashArt.leapTime(
                  clipOf(unit, "jump"),
                  seconds - st.stateStart,
                  unit.integer("DashConstantTime", 0) / 1000f,
                  motion == null ? 0 : motion[4] - 50f * (1f - tickAlpha),
                  unit.integer("DashLandingTime", 0));
        } else if (unit.has("dash")) {
          action = DashArt.dashVariant(unit::has, dashLinks.getOrDefault(e.id(), 0));
        } else {
          action = unit.has("charge") ? "charge" : "run";
        }
      }
      case GridEntityState.JUMPING -> {
        if (unit.has("jump")) {
          // a jump over the river with a clip of its own (Royal Hogs): its landing frame at the
          // landing, the jump lasting the distance over the jump speed
          action = "jump";
          clipTime =
              DashArt.riverJumpTime(
                  clipOf(unit, "jump"),
                  seconds - st.stateStart,
                  motion == null ? 0 : motion[5],
                  unit.integer("JumpSpeed", 0));
        } else {
          action = "run";
        }
      }
      case GridEntityState.COMPONENTS_DISABLED, GridEntityState.FOLLOWING_REMOVED_BUILDING -> {
        // the Fisherman held while his hook pulls (or drags him): his loading clip's pull
        float t = DashArt.pullTime(clipOf(unit, "loading"), seconds - st.stateStart);
        action = t >= 0 ? "loading" : "idle";
        clipTime = t;
      }
      case GridEntityState.CASTING, GridEntityState.ABILITY_FOLLOW_UP ->
          action = unit.has("ability") ? "ability" : "idle";
      case GridEntityState.DEPLOYING, GridEntityState.CLONE_SETUP -> {
        if (st.deployStart < 0) st.deployStart = seconds;
        action = unit.has("deploy") ? "deploy" : "idle";
        filter = deployFilter(unit, seconds - st.deployStart);
      }
      case GridEntityState.ATTACKING -> {
        int[] atk = attacks.get(e.id());
        String clipName = "attack";
        if (atk != null && atk[1] > 0 && unit.has("attack" + (atk[1] + 1))) {
          clipName = "attack" + (atk[1] + 1);
        }
        float t = atk == null ? -1f : attackClipTime(unit, clipName, atk, tickAlpha);
        if (atk == null) {
          action = clipName; // no timer to line up with: loop the clip
        } else if (t >= 0) {
          action = clipName;
          clipTime = t;
        } else {
          action = "idle";
        }
      }
      default -> action = "idle";
    }
    if (state != GridEntityState.DEPLOYING && state != GridEntityState.CLONE_SETUP) {
      st.deployStart = -1;
    }
    filter = withDamageFlash(filter, e.id(), effectClock);
    if (!unit.has(action)) action = unit.has("idle") ? "idle" : action;
    if (e.shield() > 0 && unit.has("shield:" + action)) action = "shield:" + action;
    int n = unit.directions(action);
    int[] df = ArtRules.facingExport(st.facingDegrees, Math.max(1, n));
    int id = unit.animation(action, df[0]);
    if (id < 0) id = unit.anyExport;
    if (id < 0) return;
    if (!action.equals(st.action)) {
      st.action = action;
      st.actionStart = seconds;
    }
    ScScene.Clip clip = unit.scene.clip(id);
    int frame = 0;
    if (clip != null && clip.frameCount() > 1) {
      float t = clipTime >= 0 ? clipTime : seconds - st.actionStart;
      frame = (int) Math.floor(Math.max(0, t) * clip.fps());
      if (clipTime >= 0) frame = Math.min(frame, clip.frameCount() - 1);
    }
    if (e.hasStatus(UnitStatus.Kind.STUNNED) || e.hasStatus(UnitStatus.Kind.FROZEN)) {
      frame = st.heldFrame;
    } else {
      st.heldFrame = frame;
    }
    out.add(
        new Pose(
            unit.scene,
            id,
            frame,
            x,
            y,
            lift,
            scale,
            df[1] == 1,
            0,
            filter,
            alpha,
            shadowOf,
            null,
            sortY,
            layer));
  }

  private static ScScene.Clip clipOf(VisualLookup.Unit unit, String action) {
    int id = unit.animation(action, 5);
    return id < 0 ? null : unit.scene.clip(id);
  }

  private static float clipLength(VisualLookup.Unit unit, String action) {
    ScScene.Clip c = clipOf(unit, action);
    return c == null || c.fps() <= 0 ? 0f : c.frameCount() / (float) c.fps();
  }

  /**
   * Seconds into the attack clip so its {@code action_frame} meets the logic hit, or -1 for the
   * ready (idle) pose between hits (§1.4).
   */
  private static float attackClipTime(
      VisualLookup.Unit unit, String action, int[] atk, float tickAlpha) {
    int id = unit.animation(action, 5);
    ScScene.Clip clip = id < 0 ? null : unit.scene.clip(id);
    if (clip == null || atk == null) return 0f;
    int hs = unit.integer("VisualHitSpeed", 0);
    if (hs <= 0) hs = unit.integer("HitSpeed", 1000);
    if (hs <= 0) return 0f;
    float fps = clip.fps();
    int afFrame = clip.labelFrame("action_frame");
    float length = clip.frameCount() / fps;
    float af = afFrame >= 0 ? afFrame / fps : length * 0.6f;
    float hsS = hs / 1000f;
    float rate = af > hsS ? af / hsS : 1f;
    float t = Math.max(0, atk[0] - 50f * (1f - tickAlpha)) / 1000f; // the timer is the newer tick's
    float p = t % hsS;
    float toHit = hsS - p;
    if (toHit * rate <= af) return af - toHit * rate;
    if (p * rate < length - af) return af + p * rate;
    return -1f;
  }

  /**
   * The unit's filter with its damage flash (the effect layer's {@code filter_damage} frame:
   * matrix, alpha, additive colour, multiplier) composed on top, or the filter alone.
   */
  private float[] withDamageFlash(float[] filter, int id, float seconds) {
    float[] d = effects == null ? null : effects.damageFilter(id, seconds);
    if (d == null) return filter;
    float[] base = filter == null ? new float[] {1, 0, 0, 1, 0, 0, 1} : filter;
    // (base o flash): the flash acts in the unit's local space after the deploy filter
    float a = base[0] * d[0] + base[2] * d[1];
    float b = base[1] * d[0] + base[3] * d[1];
    float c = base[0] * d[2] + base[2] * d[3];
    float dd = base[1] * d[2] + base[3] * d[3];
    float tx = base[0] * d[4] + base[2] * d[5] + base[4];
    float ty = base[1] * d[4] + base[3] * d[5] + base[5];
    float[] out = {a, b, c, dd, tx, ty, base[6] * d[6], 0, 0, 0, 1, 1, 1};
    if (d.length >= 13) System.arraycopy(d, 7, out, 7, 6);
    return out;
  }

  /** The deploy filter's matrix and alpha at that time, or null once it has played (§8). */
  private float[] deployFilter(VisualLookup.Unit unit, float elapsed) {
    ScScene fx = lookup.effectsScene();
    if (fx == null || unit.deployFilter < 0) return null;
    ScScene.Clip c = fx.clip(unit.deployFilter);
    if (c == null || c.frameCount() == 0) return null;
    int f = (int) Math.floor(elapsed * c.fps());
    if (f >= c.frameCount()) return null;
    int[] el = c.frames()[Math.max(0, f)];
    if (el.length < 3) return null;
    ScScene.Bank bank = fx.bank(c.bank());
    float[] m = {1, 0, 0, 1, 0, 0, 1};
    if (bank != null && el[1] != ScScene.IDENTITY && el[1] * 6 + 5 < bank.matrices().length) {
      System.arraycopy(bank.matrices(), el[1] * 6, m, 0, 6);
    }
    if (bank != null && el[2] != ScScene.IDENTITY && el[2] * 7 + 6 < bank.colors().length) {
      m[6] = bank.colors()[el[2] * 7 + 3] / 255f;
    }
    return m;
  }

  // ---------------------------------------------------------------------------------- projectiles

  private void poseProjectile(
      List<Pose> out,
      EntityView e,
      EntityView before,
      float tickAlpha,
      float x,
      float y,
      ViewOrientation view) {
    VisualLookup.Projectile p = lookup.projectile(e.name(), view.blue(e.side()));
    if (p == null) return;
    int[] flight = flights.get(e.id());
    float z = flight == null ? 0 : flight[0];
    float progress = 0.5f;
    float sx = x, sy = y;
    if (flight != null) {
      float dx = e.aimX() - flight[1], dy = e.aimY() - flight[2];
      float total = (float) Math.sqrt(dx * dx + dy * dy);
      float ddx = e.x() - flight[1], ddy = e.y() - flight[2];
      progress = total > 1 ? Math.min(1f, (float) Math.sqrt(ddx * ddx + ddy * ddy) / total) : 1f;
      sx = proj.x(view, flight[1]);
      sy = proj.y(view, flight[2]);
    }
    float ax = proj.x(view, e.aimX()), ay = proj.y(view, e.aimY());
    float angle;
    if ((ax - sx) * (ax - sx) + (ay - sy) * (ay - sy) >= 1f) {
      angle = (float) Math.toDegrees(Math.atan2(ay - sy, ax - sx));
    } else {
      float hx = view.x((float) (e.x() + e.headingX())) - view.x((float) e.x());
      float hy = view.y((float) (e.y() + e.headingY())) - view.y((float) e.y());
      angle = (float) Math.toDegrees(Math.atan2(hy * 0.811f, hx));
    }
    ScScene.Clip clip = p.scene().clip(p.export());
    int n = clip == null ? 1 : clip.frameCount();
    int frame =
        n > 100
            ? (int) (e.lifeMs() / 1000f * (clip == null ? 30 : clip.fps()))
            : Math.round(progress * (n - 1));
    float scale = proj.pixelsPerDisplayUnit() * p.scale();
    out.add(
        new Pose(
            p.scene(),
            p.export(),
            frame,
            x,
            y,
            proj.height(z),
            scale,
            false,
            angle,
            null,
            1f,
            null,
            null,
            y,
            LAYER_AIR));
    if (p.shadow() >= 0) {
      JsonNode noRotate = p.row() == null ? null : p.row().columns().get("ShadowDisableRotate");
      float shadowAngle = noRotate != null && noRotate.asBoolean(false) ? 0 : angle;
      out.add(
          new Pose(
              p.scene(),
              p.shadow(),
              frame,
              x,
              y,
              0,
              scale,
              false,
              shadowAngle,
              null,
              0.7f,
              null,
              null,
              y,
              LAYER_GROUND_SHADOW));
    }
  }

  // ---------------------------------------------------------------------------------------- draw

  private void drawPose(Pose q) {
    transform.setToTranslation(q.x(), q.groundY() + q.lift());
    if (q.rotation() != 0) transform.rotate(q.rotation());
    transform.scale(q.flip() ? -q.scale() : q.scale(), -q.scale());
    float[] f = q.filter();
    if (f != null) {
      // the filter acts in the unit's own (unmirrored) space
      if (q.flip()) transform.scale(-1, 1);
      applyFilter(f);
      if (q.flip()) transform.scale(-1, 1);
    }
    float a = q.alpha() * (f != null ? f[6] : 1f);
    tint.set(1, 1, 1, a);
    if (f != null && f.length >= 13) {
      tint.set(f[10], f[11], f[12], a);
      drawer.setNextAdd(f[7], f[8], f[9]);
    }
    if (q.overrides() != null) {
      drawer.draw(q.scene(), q.id(), q.frame(), transform, tint, q.overrides());
    } else {
      drawer.draw(q.scene(), q.id(), q.frame(), transform, tint);
    }
  }

  private void applyFilter(float[] f) {
    local.m00 = f[0];
    local.m10 = f[1];
    local.m01 = f[2];
    local.m11 = f[3];
    local.m02 = f[4];
    local.m12 = f[5];
    transform.mul(local);
  }

  /** The unit's current frame again as a dark, flattened silhouette at its ground point (§8). */
  private void drawShadow(Pose q) {
    VisualLookup.Unit u = q.shadowOf();
    float sxs = u.integer("ShadowScaleX", 90) / 100f;
    float sys = u.integer("ShadowScaleY", 90) / 100f;
    float skew = u.integer("ShadowSkew", 25) / 100f;
    float ox = u.integer("ShadowX", 0);
    float oy = u.integer("ShadowY", 0);
    transform.setToTranslation(q.x(), q.groundY());
    transform.scale(q.scale(), -q.scale());
    transform.translate(ox, oy);
    // cast towards the viewer: the silhouette mirrored below the feet, its far end leaning left
    local.idt();
    local.m01 = -skew; // x -= skew * y, y down after the mirror
    transform.mul(local);
    transform.scale(sxs, -sys);
    if (q.flip()) transform.scale(-1, 1);
    // a unit still dropping in (the deploy filter's fade) casts a shadow as faint as itself
    float fade = q.filter() != null && q.filter().length >= 7 ? q.filter()[6] : 1f;
    tint.set(shadowColor.r, shadowColor.g, shadowColor.b, shadowColor.a * q.alpha() * fade * fade);
    if (q.overrides() != null) {
      drawer.draw(q.scene(), q.id(), q.frame(), transform, tint, q.overrides());
    } else {
      drawer.draw(q.scene(), q.id(), q.frame(), transform, tint);
    }
  }

  static String text(GameRow row, String column) {
    if (row == null) return null;
    JsonNode v = row.columns().get(column);
    return v == null || v.isNull() ? null : v.asText();
  }

  static int integer(GameRow row, String column, int fallback) {
    if (row == null) return fallback;
    JsonNode v = row.columns().get(column);
    return v == null || !v.canConvertToInt() ? fallback : v.asInt();
  }

  @Override
  public void dispose() {
    batch.dispose();
  }
}
