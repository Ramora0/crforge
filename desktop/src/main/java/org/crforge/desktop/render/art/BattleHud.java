/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.render.art;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.Disposable;
import org.crforge.desktop.battle.BattleFrame;
import org.crforge.desktop.render.ViewOrientation;

/** The battle's own HUD (health bars, timer, elixir, hand) drawn with the game's UI art. */
public final class BattleHud implements Disposable {

  private final ArtCache cache;
  private final ScDrawer drawer;

  public BattleHud(ArtCache cache, ScDrawer drawer) {
    this.cache = cache;
    this.drawer = drawer;
  }

  public void draw(
      BattleFrame frame,
      ViewOrientation view,
      ArtProjection proj,
      int width,
      int height,
      Matrix4 projection) {}

  @Override
  public void dispose() {}
}
