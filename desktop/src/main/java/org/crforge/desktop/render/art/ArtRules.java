/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.render.art;

import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.desktop.battle.EntityView;

/**
 * The few rules the game's art does not state in its tables: the art's scale against the tile grid,
 * which animation a state plays and which of the facing exports a heading picks. See
 * docs/realistic-rendering.md for the evidence behind each.
 */
public final class ArtRules {

  private ArtRules() {}

  /** Display units of the art per arena tile. */
  public static final float DISPLAY_UNITS_PER_TILE = 30f;

  /**
   * Facing exports per action, {@code _1} (facing away, up the screen) to {@code _9} (facing the
   * viewer).
   */
  public static final int DIRECTIONS = 9;

  /** Whether the facing exports between up and down face right (else left) before mirroring. */
  public static final boolean ART_FACES_RIGHT = true;

  /** The animation a unit plays in its state: {@code run}, {@code attack} or {@code idle}. */
  public static String action(EntityView e) {
    return switch (e.state()) {
      case GridEntityState.MOVING,
          GridEntityState.DASHING,
          GridEntityState.JUMPING,
          GridEntityState.SPAWN_PATHFIND,
          GridEntityState.INGAME_PATHFIND,
          GridEntityState.ROUTE_FOLLOWING_ALTERNATE ->
          "run";
      case GridEntityState.ATTACKING -> "attack";
      default -> "idle";
    };
  }

  /**
   * The facing export ({@code 1..DIRECTIONS}) and whether to mirror it, for a facing vector in
   * world pixels (x right, y up).
   */
  public static int[] direction(float fx, float fy) {
    double theta = Math.atan2(Math.abs(fx), fy); // 0 = up the screen, PI = down
    int dir = 1 + (int) Math.round(theta / Math.PI * (DIRECTIONS - 1));
    boolean flip = ART_FACES_RIGHT ? fx < 0 : fx > 0;
    if (dir == 1 || dir == DIRECTIONS) flip = false;
    return new int[] {Math.max(1, Math.min(DIRECTIONS, dir)), flip ? 1 : 0};
  }
}
