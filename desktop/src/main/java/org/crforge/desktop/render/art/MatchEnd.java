/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.render.art;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.Affine2;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Disposable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.desktop.battle.BattleFrame;
import org.crforge.desktop.battle.EntityView;
import org.crforge.desktop.render.ViewOrientation;

/**
 * The crowns and the end of a match, as the recorder shows them (docs/realistic-rendering.md §9).
 *
 * <ul>
 *   <li>A side taking a crown plays {@code sound_get_star_player} (the bottom side, the "player")
 *       or {@code sound_get_star_enemy} ({@code csv_client/sounds.csv}).
 *   <li>Once the core ends the match ({@code LadderMatch.end}: three crowns, or the time up), the
 *       clock and the elixir hold their values, every unit stands idle where it stood, the game-end
 *       horn sounds and the battle music stops.
 *   <li>When the battle stops stepping (the core's end-screen delay later), the replay end screen
 *       ({@code sc/ui_battle_end.sc} {@code replay_battle_end_new}) plays over the arena: both
 *       players' panels, their crowns popping in one by one ({@code sound_crown_appear_1..3}), then
 *       "Winner!" over the winner's panel with the {@code Win} / {@code Lose} / {@code Draw} jingle
 *       ({@code csv_client/music.csv}, for the bottom side).
 * </ul>
 */
public final class MatchEnd implements Disposable {

  private static final String GET_STAR_PLAYER =
      "soundbanks/music_jingle_general/music_jingle_general_get_star_player";
  private static final String GET_STAR_ENEMY =
      "soundbanks/ui_battle_general/ui_battle_general_get_star_enemy";
  private static final String HORN =
      "soundbanks/music_jingle_general/music_jingle_general_gameend_horn";
  private static final String CROWN_APPEAR =
      "soundbanks/ui_battle_general/ui_battle_general_crown_appear_";
  private static final String WIN = "soundbanks/music_jingle_general/music_jingle_general_win";
  private static final String LOSE = "soundbanks/music_jingle_general/music_jingle_general_lose";
  private static final String DRAW = "soundbanks/music_jingle_general/music_jingle_general_draw";

  /** The end screen's layout area ({@code screen_area}: 768 x 1024 du about its centre). */
  private static final float SCREEN_DU = 768f;

  /** End screen timing (seconds from its start; GUESS, the client drives it from code). */
  private static final float PANEL_FRAMES = 40f;

  private static final float CROWN_START = 0.75f;
  private static final float CROWN_GAP = 0.4f;
  private static final float FPS = 60f;

  /** The winner panel's "Winner!" text field (player_01's, player_02's). */
  private static final int WINNER_BLUE = 280;

  private static final int WINNER_RED = 283;

  private final ArtCache cache;
  private final HudArt art;
  private final HudFonts fonts = new HudFonts();
  private final List<String[]> sounds = new ArrayList<>();
  private final Map<Integer, Integer> crowns = new HashMap<>();
  private BattleFrame endFrame;
  private Map<Integer, EntityView> frozen = Map.of();
  private int endTick = -1;
  private int screenTick = -1;
  private int lastCrownSound;
  private boolean jingle;
  private int blueCrowns;
  private int redCrowns;
  private int winner = -1;
  private boolean blueWon;
  private boolean stopped;

  public MatchEnd(ArtCache cache) {
    this.cache = cache;
    this.art = new HudArt(cache);
  }

  /** Tells it the battle has stopped stepping (the end screen follows an ended match). */
  public void stopped() {
    stopped = true;
  }

  /** Whether the match has ended. */
  public boolean ended() {
    return endFrame != null;
  }

  /** The battle time (seconds, tick * 0.05) the match ended at, or -1. */
  public float endSeconds() {
    return endTick < 0 ? -1f : endTick * 0.05f;
  }

  /**
   * Reads the battle's frame of one tick (in order); returns the frame the arena is drawn from:
   * after the end, every character idle at its place and facing at the end (one that appeared later
   * idle where it is).
   */
  public BattleFrame update(BattleFrame f, ViewOrientation view) {
    double t = f.tick() * 0.05;
    for (BattleFrame.SideView s : f.sides()) {
      Integer before = crowns.put(s.side(), s.crowns());
      if (before != null && s.crowns() > before) {
        add(t, view.blue(s.side()) ? GET_STAR_PLAYER : GET_STAR_ENEMY);
      }
      if (view.blue(s.side())) blueCrowns = s.crowns();
      else redCrowns = s.crowns();
    }
    if (endFrame == null && f.ended()) {
      endFrame = f;
      endTick = f.tick();
      winner = f.winner();
      blueWon = winner >= 0 && view.blue(winner);
      Map<Integer, EntityView> m = new HashMap<>();
      for (EntityView e : f.entities()) {
        if (e.isCharacter()) m.put(e.id(), e);
      }
      frozen = m;
      add(t, HORN);
    }
    if (endFrame != null && screenTick < 0 && (f.over() || stopped)) screenTick = f.tick();
    if (screenTick >= 0) screenSounds(f.tick());
    return endFrame == null ? f : world(f);
  }

  /** The frame the HUD shows: after the end, the clock and the elixir as they were at the end. */
  public BattleFrame hud(BattleFrame f) {
    if (endFrame == null) return f;
    List<BattleFrame.SideView> sides = new ArrayList<>();
    for (BattleFrame.SideView s : f.sides()) {
      BattleFrame.SideView e = null;
      for (BattleFrame.SideView x : endFrame.sides()) if (x.side() == s.side()) e = x;
      sides.add(
          e == null
              ? s
              : new BattleFrame.SideView(
                  s.side(),
                  e.elixir(),
                  e.wholeElixir(),
                  s.crowns(),
                  s.hand(),
                  s.next(),
                  s.abilities()));
    }
    return new BattleFrame(
        f.tick(),
        endFrame.timeMs(),
        endFrame.overtime(),
        endFrame.elixirRate(),
        f.entities(),
        List.copyOf(sides),
        f.ended(),
        f.winner(),
        f.over(),
        f.halted(),
        f.messages());
  }

  private BattleFrame world(BattleFrame f) {
    List<EntityView> out = new ArrayList<>(f.entities().size());
    for (EntityView e : f.entities()) {
      if (!e.isCharacter() || e.deploying()) {
        out.add(e);
        continue;
      }
      EntityView at = frozen.getOrDefault(e.id(), e);
      out.add(idle(e, at));
    }
    return new BattleFrame(
        f.tick(),
        f.timeMs(),
        f.overtime(),
        f.elixirRate(),
        List.copyOf(out),
        f.sides(),
        f.ended(),
        f.winner(),
        f.over(),
        f.halted(),
        f.messages());
  }

  /** {@code e} standing idle at {@code at}'s place and facing, with no target. */
  private static EntityView idle(EntityView e, EntityView at) {
    return new EntityView(
        e.id(),
        e.kind(),
        e.side(),
        e.name(),
        e.level(),
        at.x(),
        at.y(),
        e.radius(),
        e.hitPoints(),
        e.maxHitPoints(),
        e.shield(),
        e.maxShield(),
        e.air(),
        e.king(),
        GridEntityState.STANDING,
        e.deploying(),
        e.hidden(),
        e.range(),
        e.minimumRange(),
        e.sightRange(),
        false,
        0,
        0,
        at.headingX(),
        at.headingY(),
        e.lifeMs(),
        e.lifetimeMs(),
        e.aimX(),
        e.aimY(),
        e.radiusY(),
        e.bodyRadius(),
        e.bodyRadiusY(),
        0,
        e.grid(),
        e.meter(),
        e.statuses(),
        e.role(),
        e.bar());
  }

  /** Returns and clears the sounds started so far, as {@code {seconds, soundRef}}. */
  public List<String[]> drainSounds() {
    List<String[]> out = new ArrayList<>(sounds);
    sounds.clear();
    return out;
  }

  private void add(double seconds, String ref) {
    sounds.add(new String[] {String.valueOf((float) seconds), ref});
  }

  // ---------------------------------------------------------------- end screen

  private int maxCrowns() {
    return Math.max(blueCrowns, redCrowns);
  }

  private float winnerAt() {
    return CROWN_START + maxCrowns() * CROWN_GAP + 0.2f;
  }

  private float crownAt(int k) {
    return CROWN_START + (k - 1) * CROWN_GAP;
  }

  private void screenSounds(int tick) {
    double start = screenTick * 0.05;
    double u = (tick - screenTick) * 0.05;
    if (!jingle) {
      jingle = true;
      add(start, winner < 0 ? DRAW : blueWon ? WIN : LOSE);
    }
    while (lastCrownSound < maxCrowns() && crownAt(lastCrownSound + 1) <= u + 1e-6) {
      lastCrownSound++;
      add(start + crownAt(lastCrownSound), CROWN_APPEAR + lastCrownSound);
    }
  }

  /**
   * Draws the end screen once the battle has stopped, {@code seconds} the battle clock (tick *
   * 0.05) of the frame drawn, over the whole {@code width x height} frame.
   */
  public void draw(float seconds, int width, int height, ScDrawer drawer, Matrix4 projection) {
    if (screenTick < 0) return;
    float u = seconds - screenTick * 0.05f;
    if (u < 0) return;
    ScScene scene = cache.scene("sc/ui_battle_end.sc");
    int id = scene == null ? -1 : scene.export("replay_battle_end_new");
    if (id < 0) return;
    int root = Math.min(59, (int) (u * FPS));
    float s = width / SCREEN_DU;
    Affine2 t = new Affine2().setToTranslation(width / 2f, height / 2f);
    t.scale(s, -s);
    art.begin(drawer.batch(), projection);
    try {
      art.draw(scene, id, root, t, new Color(1, 1, 1, 1), new ScreenHook(u));
    } finally {
      art.end();
    }
  }

  /** Plays the end screen's parts at {@code u} seconds into it. */
  private final class ScreenHook implements HudArt.Hook {
    private final float u;

    /** 0 at the root, 1 inside player_01 (the bottom side), 2 inside player_02. */
    private int panel;

    ScreenHook(float u) {
      this.u = u;
    }

    private boolean winnerPanel() {
      return winner >= 0 && (panel == 1) == blueWon;
    }

    private int crownsOfPanel() {
      return panel == 1 ? blueCrowns : redCrowns;
    }

    @Override
    public int child(String name, int childId, Affine2 local, int frame) {
      switch (name) {
        case "ok_btn",
        "replay_btn",
        "taunt_enemy",
        "taunt_player",
        "screen_area",
        "player_name",
        "trophies",
        "trophies_crown_rush",
        "crowns_crown_rush",
        "league",
        "rating":
          return HudArt.SKIP;
        case "draw":
          return winner < 0 ? Math.min(19, (int) (u * FPS)) : HudArt.SKIP;
        case "player_01", "player_02":
          {
            panel = name.equals("player_01") ? 1 : 2;
            int f = 1 + Math.min((int) PANEL_FRAMES - 1, (int) (u * FPS));
            if (winnerPanel() && u >= winnerAt())
              f = 41 + Math.min(18, (int) ((u - winnerAt()) * FPS));
            return f;
          }
        case "crown_1", "crown_2", "crown_3":
          {
            int k = name.charAt(6) - '0';
            float at = crownAt(k);
            // a crown not (yet) won leaves its slot's cushion bare
            return k > crownsOfPanel() || u < at
                ? HudArt.SKIP
                : 1 + Math.min(48, (int) ((u - at) * FPS));
          }
        case "crown":
          return (int) (u * FPS);
        case "cushion_1", "cushion_2", "cushion_3":
          return 0;
        case "winner_txt":
          return winnerPanel() && u >= winnerAt()
              ? Math.min(14, (int) ((u - winnerAt()) * FPS))
              : HudArt.SKIP;
        case "":
          // the root's dimming backdrop: stretched to the phone's taller frame
          if (panel == 0) local.scale(1f, 1.6f);
          return frame;
        default:
          return frame;
      }
    }

    @Override
    public void text(String name, int textId, Affine2 world, float alpha) {
      if (textId == WINNER_BLUE || textId == WINNER_RED) {
        // text field: box (-76, -2)..(351, 38), Supercell-Magic 36, centred
        int color = textId == WINNER_BLUE ? 0xff66ffff : 0xffffccff;
        drawText("Winner!", world, -76, -2, 351, 38, 36, color, 0xff000000, alpha);
      }
    }
  }

  private void drawText(
      String value,
      Affine2 world,
      float left,
      float top,
      float right,
      float bottom,
      int size,
      int color,
      int outline,
      float alpha) {
    float x0 = world.m00 * left + world.m01 * top + world.m02;
    float y0 = world.m10 * left + world.m11 * top + world.m12;
    float x1 = world.m00 * right + world.m01 * bottom + world.m02;
    float y1 = world.m10 * right + world.m11 * bottom + world.m12;
    float sy = (float) Math.sqrt(world.m01 * world.m01 + world.m11 * world.m11);
    float px = size * sy;
    if (px < 4f || alpha <= 0.01f) return;
    art.straightAlpha();
    fonts.draw(
        art.batch(),
        value,
        Math.min(x0, x1),
        Math.min(y0, y1),
        Math.abs(x1 - x0),
        Math.abs(y1 - y0),
        px,
        color,
        outline,
        Align.center,
        alpha);
  }

  @Override
  public void dispose() {
    fonts.dispose();
    art.dispose();
  }
}
