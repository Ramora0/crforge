/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.render.art;

/**
 * The facing rule the game's art follows (docs/realistic-rendering.md §2): an action's {@code n}
 * facing exports cover the right half of the circle, {@code _1} facing up the screen (away from the
 * viewer) to {@code _n} facing down it, and the left half reuses them mirrored.
 */
public final class ArtRules {

  private ArtRules() {}

  /**
   * The facing angle of a vector on screen (x right, y up): 0 up, +90 right, ±180 down.
   *
   * @param fx the facing's x
   * @param fy the facing's y, up the screen
   */
  public static float facingDegrees(float fx, float fy) {
    return (float) Math.toDegrees(Math.atan2(fx, fy));
  }

  /**
   * The facing export ({@code 1..n}) and whether to mirror it for a facing angle, for an action
   * with {@code n} facing exports.
   *
   * @return {@code {export, 1 when mirrored else 0}}
   */
  public static int[] facingExport(float degrees, int n) {
    if (n <= 1) return new int[] {1, 0};
    float step = 180f / (n - 1);
    int dir = Math.round(Math.abs(degrees) / step) + 1;
    dir = Math.max(1, Math.min(n, dir));
    boolean flip = degrees < 0 && dir != 1 && dir != n;
    return new int[] {dir, flip ? 1 : 0};
  }
}
