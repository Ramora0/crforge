/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.render.art;

import org.crforge.desktop.render.ViewOrientation;

/**
 * Where the game's art puts a point of the arena. The arena art bakes a camera tilt into its 2D
 * drawing: a tile spans {@link #DU_PER_TILE_X} display units across and {@link #DU_PER_TILE_Y}
 * along the arena's length, so the realistic view draws tiles shorter than wide while every sprite
 * keeps a uniform scale. Positions are game units after the view's mirroring, the bottom player's
 * baseline at 0; world pixels have y up, the arena's bottom-left corner at ({@code originX}, {@code
 * originY}).
 */
public final class ArtProjection {

  /** Arena display units per tile across the arena (x). */
  public static final float DU_PER_TILE_X = 29.75f;

  /** Arena display units per tile along the arena (y). */
  public static final float DU_PER_TILE_Y = 24.12f;

  private final float pixelsPerTile;
  private final float originX;
  private final float originY;
  private final int widthUnits;
  private final int heightUnits;

  /**
   * @param pixelsPerTile world pixels per tile across the arena
   * @param originX world x of the arena's left edge
   * @param originY world y of the bottom player's baseline
   */
  public ArtProjection(
      float pixelsPerTile, float originX, float originY, int widthUnits, int heightUnits) {
    this.pixelsPerTile = pixelsPerTile;
    this.originX = originX;
    this.originY = originY;
    this.widthUnits = widthUnits;
    this.heightUnits = heightUnits;
  }

  public float pixelsPerTile() {
    return pixelsPerTile;
  }

  /** World pixels per display unit of the art (uniform). */
  public float pixelsPerDisplayUnit() {
    return pixelsPerTile / DU_PER_TILE_X;
  }

  /** Vertical world pixels per tile. */
  public float pixelsPerTileY() {
    return pixelsPerTile * DU_PER_TILE_Y / DU_PER_TILE_X;
  }

  public float x(ViewOrientation view, float gameX) {
    return originX + view.x(gameX) * pixelsPerTile / 1000f;
  }

  public float y(ViewOrientation view, float gameY) {
    return originY + view.y(gameY) * pixelsPerTileY() / 1000f;
  }

  /**
   * Display units a height of one game unit lifts a sprite up the screen: the orthographic camera's
   * 0.02975 * sin(phi), cos(phi) = 0.811 (a Tower Princess at 2200 stands on the tower floor, 38 du
   * up).
   */
  public static final float DU_PER_HEIGHT_UNIT = 0.01742f;

  /**
   * The arena art sits this many display units below the units' frame (fit on the checkerboard).
   */
  public static final float ARENA_ART_DY = 3f;

  /** World pixels for a height above the ground in game units (drawn straight up the screen). */
  public float height(float gameUnits) {
    return gameUnits * DU_PER_HEIGHT_UNIT * pixelsPerDisplayUnit();
  }

  /** World x of the arena art's origin (its centre line). */
  public float arenaOriginX() {
    return originX + widthUnits / 2f * pixelsPerTile / 1000f;
  }

  /** World y of the arena art's origin (the far edge). */
  public float arenaOriginY() {
    return originY + heightUnits * pixelsPerTileY() / 1000f;
  }

  public float arenaWidth() {
    return widthUnits * pixelsPerTile / 1000f;
  }

  public float arenaHeight() {
    return heightUnits * pixelsPerTileY() / 1000f;
  }
}
