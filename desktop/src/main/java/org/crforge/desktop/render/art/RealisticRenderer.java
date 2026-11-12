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
import java.util.List;
import java.util.Map;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.desktop.battle.BattleFrame;
import org.crforge.desktop.battle.EntityView;
import org.crforge.desktop.render.ViewOrientation;

/**
 * Draws a battle frame with the game's own art from the {@link ArtCache}: each troop, building,
 * tower and projectile as the movie clip its table row names ({@code FileName} with {@code
 * BlueExportName} / {@code RedExportName}), its animation picked from the unit's state and its
 * facing from its heading. Everything the art cache lacks is skipped, so the debug view's own
 * shapes can be drawn underneath as a fallback.
 */
public final class RealisticRenderer implements Disposable {

  private final ArtCache cache;
  private final PolygonSpriteBatch batch;
  private final ScDrawer drawer;
  private final VisualLookup lookup;
  private final Map<Integer, AnimState> anims = new HashMap<>();
  private final Affine2 transform = new Affine2();
  private final Color tint = new Color();

  private ArtProjection proj;

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

  /** World pixels per display unit of the art. */
  public float pixelsPerDisplayUnit() {
    return proj.pixelsPerDisplayUnit();
  }

  private static final class AnimState {
    String clip = "";
    float startSeconds;
    int lastDirection = ArtRules.DIRECTIONS / 2 + 1;
    boolean lastFlip;
  }

  /** One entity resolved to what is drawn for it this frame. */
  private record Draw(EntityView entity, float x, float y, float sortY) {}

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
        previous == null
            ? frame.timeMs() / 1000f
            : (previous.timeMs() + (frame.timeMs() - previous.timeMs()) * alpha) / 1000f;
    Map<Integer, EntityView> before = new HashMap<>();
    if (previous != null) for (EntityView e : previous.entities()) before.put(e.id(), e);

    List<Draw> ground = new ArrayList<>();
    List<Draw> air = new ArrayList<>();
    List<Draw> effects = new ArrayList<>();
    for (EntityView e : frame.entities()) {
      EntityView p = before.get(e.id());
      float gx = p == null ? e.x() : p.x() + (e.x() - p.x()) * alpha;
      float gy = p == null ? e.y() : p.y() + (e.y() - p.y()) * alpha;
      float sx = proj.x(view, gx);
      float sy = proj.y(view, gy);
      Draw d = new Draw(e, sx, sy, sy);
      switch (e.kind()) {
        case AREA_EFFECT -> effects.add(d);
        case PROJECTILE -> air.add(d);
        default -> (e.air() ? air : ground).add(d);
      }
    }
    // back (top of the screen) to front
    Comparator<Draw> backToFront = Comparator.comparingDouble((Draw d) -> -d.sortY());
    ground.sort(backToFront);
    air.sort(backToFront);

    batch.setProjectionMatrix(projection);
    drawer.begin();
    for (Draw d : effects) drawEffect(d, seconds, view);
    for (Draw d : ground) drawCharacter(d, seconds, view, tables);
    for (Draw d : air) {
      if (d.entity().kind() == EntityView.Kind.PROJECTILE) drawProjectile(d, view);
      else drawCharacter(d, seconds, view, tables);
    }
    drawer.end();
    // forget entities that are gone
    if (anims.size() > 4 * frame.entities().size() + 64) {
      java.util.Set<Integer> live = new java.util.HashSet<>();
      for (EntityView e : frame.entities()) live.add(e.id());
      anims.keySet().retainAll(live);
    }
  }

  private void drawCharacter(Draw d, float seconds, ViewOrientation view, GameTables tables) {
    EntityView e = d.entity();
    boolean blue = view.blue(e.side());
    VisualLookup.Unit unit = lookup.unit(e.name(), blue);
    if (unit == null) return;
    float alpha = e.hidden() ? 0.35f : 1f;
    float scale = pixelsPerDisplayUnit() * unit.scale();
    float baseY = d.y() + (e.air() ? proj.height(unit.flyingHeight()) : 0);

    if (e.kind() == EntityView.Kind.TOWER || unit.isTowerLike()) {
      // the building itself, then its attached character standing on it, then the top
      if (unit.baseExport() >= 0) {
        place(d.x(), baseY, scale, false);
        tint.set(1, 1, 1, alpha);
        drawer.draw(
            unit.scene(),
            unit.baseExport(),
            clipFrame(unit.scene(), unit.baseExport(), seconds),
            transform,
            tint);
      }
      VisualLookup.Unit rider = unit.attached();
      if (rider != null) {
        float riderY = baseY + proj.height(unit.attachedHeight());
        drawAnimated(
            e, rider, d.x(), riderY, pixelsPerDisplayUnit() * rider.scale(), seconds, view, alpha);
      }
      if (unit.topExport() >= 0) {
        place(d.x(), baseY, scale, false);
        tint.set(1, 1, 1, alpha);
        drawer.draw(
            unit.scene(),
            unit.topExport(),
            clipFrame(unit.scene(), unit.topExport(), seconds),
            transform,
            tint);
      }
      return;
    }
    drawAnimated(e, unit, d.x(), baseY, scale, seconds, view, alpha);
  }

  private void drawAnimated(
      EntityView e,
      VisualLookup.Unit unit,
      float x,
      float y,
      float scale,
      float seconds,
      ViewOrientation view,
      float alpha) {
    String action = ArtRules.action(e);
    AnimState st =
        anims.computeIfAbsent(
            e.id() * 31 + (unit == null ? 0 : unit.hashCode() & 7), k -> new AnimState());
    // facing: towards the target while attacking, else along the heading
    float fx, fy;
    if (e.state() == GridEntityState.ATTACKING && e.hasTarget()) {
      fx = view.x((float) e.targetX()) - view.x((float) e.x());
      fy = view.y((float) e.targetY()) - view.y((float) e.y());
    } else {
      fx = view.x((float) (e.x() + e.headingX())) - view.x((float) e.x());
      fy = view.y((float) (e.y() + e.headingY())) - view.y((float) e.y());
    }
    if (fx * fx + fy * fy > 1e-4f) {
      int[] dirFlip = ArtRules.direction(fx, fy);
      st.lastDirection = dirFlip[0];
      st.lastFlip = dirFlip[1] == 1;
    }
    int id = unit.animation(action, st.lastDirection);
    if (id < 0) id = unit.animation("idle", st.lastDirection);
    if (id < 0) id = unit.anyExport();
    if (id < 0) return;
    String key = action;
    if (!key.equals(st.clip)) {
      st.clip = key;
      st.startSeconds = seconds;
    }
    ScScene.Clip clip = unit.scene().clip(id);
    int frame = 0;
    if (clip != null && clip.frameCount() > 1) {
      frame = (int) Math.floor(Math.max(0, seconds - st.startSeconds) * clip.fps());
    }
    place(x, y, scale, st.lastFlip);
    tint.set(1, 1, 1, alpha);
    drawer.draw(unit.scene(), id, frame, transform, tint);
  }

  private void drawProjectile(Draw d, ViewOrientation view) {
    EntityView e = d.entity();
    VisualLookup.Projectile p = lookup.projectile(e.name(), view.blue(e.side()));
    if (p == null) return;
    float hx = view.x((float) (e.x() + e.headingX())) - view.x((float) e.x());
    float hy =
        (view.y((float) (e.y() + e.headingY())) - view.y((float) e.y()))
            * ArtProjection.DU_PER_TILE_Y
            / ArtProjection.DU_PER_TILE_X;
    float angle = (hx * hx + hy * hy) > 1e-4f ? (float) Math.toDegrees(Math.atan2(hy, hx)) : 90f;
    float scale = pixelsPerDisplayUnit() * p.scale();
    transform.setToTranslation(d.x(), d.y());
    // the art points along +x in display space (y down): rotate to the heading in world space
    transform.rotate(angle);
    transform.scale(scale, -scale);
    tint.set(1, 1, 1, 1);
    drawer.draw(p.scene(), p.export(), 0, transform, tint);
  }

  private void drawEffect(Draw d, float seconds, ViewOrientation view) {
    EntityView e = d.entity();
    VisualLookup.Projectile p = lookup.areaEffect(e.name(), view.blue(e.side()));
    if (p == null) return;
    float scale = pixelsPerDisplayUnit() * p.scale();
    place(d.x(), d.y(), scale, false);
    tint.set(1, 1, 1, 1);
    int frame =
        e.lifetimeMs() > 0
            ? (int) ((e.lifetimeMs() - e.lifeMs()) / 1000f * 30)
            : (int) (seconds * 30);
    drawer.draw(p.scene(), p.export(), frame, transform, tint);
  }

  /** Display units (y down) at {@code scale} pixels each, origin at (x, y), mirrored if flip. */
  private void place(float x, float y, float scale, boolean flip) {
    transform.setToTranslation(x, y);
    transform.scale(flip ? -scale : scale, -scale);
  }

  private static int clipFrame(ScScene scene, int id, float seconds) {
    ScScene.Clip c = scene.clip(id);
    return c == null ? 0 : (int) Math.floor(seconds * c.fps());
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
