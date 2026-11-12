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
  private final Color shadowColor = new Color(0f, 0f, 100 / 255f, 0.5f);

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
    for (Pose q : poses) drawPose(q);
    drawer.end();
    if (anims.size() > 4 * frame.entities().size() + 64) {
      Set<Long> live = new HashSet<>();
      for (EntityView e : frame.entities()) {
        for (long k = 0; k < 4; k++) live.add(e.id() * 4L + k);
      }
      anims.keySet().retainAll(live);
    }
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
    if (state == GridEntityState.WAITING_TO_DEPLOY
        || state == GridEntityState.FOLLOWING_REMOVED
        || state == GridEntityState.FOLLOWING_REMOVED_BUILDING) {
      return;
    }
    boolean blue = view.blue(e.side());
    VisualLookup.Unit unit = lookup.unit(e.name(), blue);
    if (unit == null) return;
    float alpha = e.hidden() ? 0.35f : 1f;
    float scale = proj.pixelsPerDisplayUnit() * unit.scale;
    float lift = e.air() ? proj.height(unit.flyingHeight) : 0f;
    int layer = e.air() ? LAYER_AIR : LAYER_GROUND;
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
        // the king tower: the clip's timeline is its waking, the turret's its aim
        boolean active = e.hasTarget() || state == GridEntityState.ATTACKING;
        if (active && st.activatedAt < 0) st.activatedAt = seconds;
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
    poseAnimated(out, e, unit, st, x, y, lift, seconds, tickAlpha, alpha, unit, y, layer);
  }

  private static void updateFacing(AnimState st, EntityView e, ViewOrientation view) {
    float fx = view.x((float) (e.x() + e.headingX())) - view.x((float) e.x());
    float fy = view.y((float) (e.y() + e.headingY())) - view.y((float) e.y());
    if (fx * fx + fy * fy < 1e-4f) return;
    st.facingDegrees = (float) Math.toDegrees(Math.atan2(fx, fy)); // 0 up, +90 right, ±180 down
  }

  /** Facing export index (1..n) and mirroring for an action with n half-circle exports (§2). */
  private static int[] facingExport(float theta, int n) {
    if (n <= 1) return new int[] {1, 0};
    float step = 180f / (n - 1);
    int dir = Math.round(Math.abs(theta) / step) + 1;
    dir = Math.max(1, Math.min(n, dir));
    boolean flip = theta < 0 && dir != 1 && dir != n;
    return new int[] {dir, flip ? 1 : 0};
  }

  /** A turret's timeline frame (0-based) for its facing over a mirrored half circle. */
  private static int[] halfCircleFrame(float theta, int n) {
    int[] f = facingExport(theta, n);
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
    switch (state) {
      case GridEntityState.MOVING,
          GridEntityState.SPAWN_PATHFIND,
          GridEntityState.INGAME_PATHFIND,
          GridEntityState.ROUTE_FOLLOWING_ALTERNATE ->
          action = "run";
      case GridEntityState.DASHING ->
          action = unit.has("dash") ? "dash" : unit.has("charge") ? "charge" : "run";
      case GridEntityState.JUMPING -> action = unit.has("jump") ? "jump" : "run";
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
        float t = attackClipTime(unit, clipName, atk, tickAlpha);
        if (t >= 0) {
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
    if (!unit.has(action)) action = unit.has("idle") ? "idle" : action;
    if (e.shield() > 0 && unit.has("shield:" + action)) action = "shield:" + action;
    int n = unit.directions(action);
    int[] df = facingExport(st.facingDegrees, Math.max(1, n));
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
    local.idt();
    local.m01 = skew; // the top leans with the light: x += skew * y (y down)
    transform.mul(local);
    transform.scale(sxs, sys);
    if (q.flip()) transform.scale(-1, 1);
    tint.set(shadowColor.r, shadowColor.g, shadowColor.b, shadowColor.a * q.alpha());
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
