/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.render.art;

import static org.crforge.desktop.render.RenderConstants.BOTTOM_UI_HEIGHT;
import static org.crforge.desktop.render.RenderConstants.TILE_PIXELS;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.utils.Disposable;
import java.util.Optional;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.pathfinding.grid.TileMap;
import org.crforge.desktop.battle.BattleAdapter;
import org.crforge.desktop.battle.BattleFrame;
import org.crforge.desktop.battle.BattleSession;
import org.crforge.desktop.render.ViewOrientation;

/**
 * The debug screens' realistic view (key {@code X}, or {@code --realistic}): the arena and every
 * entity drawn with the game's own art in place of the debug shapes, fitted into the arena's
 * place on the screen. Available only when an art cache exists (see {@code tools/game-art}).
 */
public final class RealisticView implements Disposable {

  private final ArtCache cache;
  private final RealisticRenderer renderer;
  private final EffectLayer effects;
  private ArenaScene arena;
  private GameTables arenaTables;
  private BattleFrame previous;
  private BattleFrame current;
  private int lastTick = -1;

  private RealisticView(ArtCache cache) {
    this.cache = cache;
    this.renderer = new RealisticRenderer(cache);
    this.effects = new EffectLayer(cache);
  }

  /** The realistic view, when this machine has an art cache. */
  public static Optional<RealisticView> create() {
    return ArtCache.locate().map(RealisticView::new);
  }

  /** The per-tick state the frame does not carry (attack timers, projectile flights). */
  public void feed(BattleSession session) {
    if (session == null) return;
    renderer.setTickData(
        BattleAdapter.attackTimers(session), BattleAdapter.projectileFlights(session));
  }

  /** Draws the arena and its entities; the debug arena's tile scale and place are kept. */
  public void draw(
      BattleFrame frame,
      GameTables tables,
      TileMap tileMap,
      ViewOrientation view,
      OrthographicCamera camera) {
    if (arena == null || arenaTables != tables) {
      arena = new ArenaScene(cache, tables, System.getProperty("crforge.art.location"));
      int[] sh = arena.shadow();
      renderer.setShadowColor(sh[0], sh[1], sh[2], sh[3]);
      arenaTables = tables;
    }
    if (frame.tick() != lastTick) {
      previous = current == null || frame.tick() < lastTick ? frame : current;
      current = frame;
      effects.update(current, previous == frame ? null : previous, tables, view);
      lastTick = frame.tick();
    }
    float pixelsPerTile = TILE_PIXELS;
    float squareHeight = tileMap.heightUnits() / 1000f * pixelsPerTile;
    float arenaHeight = squareHeight * ArtProjection.DU_PER_TILE_Y / ArtProjection.DU_PER_TILE_X;
    ArtProjection proj =
        new ArtProjection(
            pixelsPerTile,
            0,
            BOTTOM_UI_HEIGHT + (squareHeight - arenaHeight) / 2f,
            tileMap.widthUnits(),
            tileMap.heightUnits());
    Gdx.gl.glEnable(GL20.GL_BLEND);
    ScDrawer drawer = renderer.drawer();
    drawer.batch().setProjectionMatrix(camera.combined);
    arena.drawGround(drawer, proj, view, current, previous, 1f);
    float seconds = current.tick() * 0.05f;
    drawer.begin();
    effects.drawBelow(drawer, proj, view, seconds);
    drawer.end();
    renderer.render(current, previous, 1f, tables, view, proj, camera.combined);
    drawer.begin();
    effects.drawAbove(drawer, proj, view, seconds);
    drawer.end();
  }

  @Override
  public void dispose() {
    renderer.dispose();
    cache.dispose();
  }
}
