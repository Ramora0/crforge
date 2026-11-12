/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.render.art;

import java.util.function.Predicate;
import org.crforge.core.pathfinding.move.MovementState;

/**
 * How a dash, a leap, a jump over the river and a charge are timed on the unit's clips
 * (docs/realistic-rendering.md §1.6). A {@code jump1} clip (Mega Knight, its evolution) carries the
 * labels {@code charge_start/end} (the crouch), {@code jump_start/end} (the flight) and {@code
 * recover_start/end} (the landing): the crouch plays at its own rate at the end of the dash's
 * wind-up, the flight is stretched over the dash's constant time ({@code DashConstantTime}), the
 * recover over the landing hold ({@code DashLandingTime}).
 */
final class DashArt {

  /** The charge progress at which a charge is complete and the charge clip plays. */
  static final int CHARGE_COMPLETE = MovementState.CHARGE_COMPLETE;

  /**
   * The landing hold's reading on the tick the dash lands: the core starts it at 50 ms and the
   * state visit of the same tick adds 50 (CharacterEntity's {@code LANDING_HOLD_START_MS}).
   */
  static final float HOLD_AT_LANDING_MS = 100f;

  private static final float TICK_MS = 50f;

  private DashArt() {}

  /**
   * Seconds into a {@code jump1} clip during the dash's wind-up: its crouch ({@code
   * charge_start..charge_end}) played at the clip's rate so it ends as the wind-up runs out, or -1
   * before it (the unit stands idle) and for a clip without the labels.
   *
   * @param windupLeft seconds of the wind-up left
   */
  static float windupTime(ScScene.Clip clip, float windupLeft) {
    if (clip == null || clip.fps() <= 0) return -1f;
    int cs = clip.labelFrame("charge_start");
    int ce = clip.labelFrame("charge_end");
    if (cs < 0 || ce < cs) return -1f;
    float length = (ce - cs + 1) / (float) clip.fps();
    if (windupLeft > length) return -1f;
    int frame = cs + (int) Math.floor(Math.max(0f, length - windupLeft) * clip.fps());
    return at(clip, Math.min(ce, frame));
  }

  /**
   * Seconds into a {@code jump1} clip during a leap: its flight ({@code jump_start..jump_end})
   * stretched over the airborne time, then, once the landing hold runs, its recover ({@code
   * recover_start..recover_end}) stretched over the hold.
   *
   * @param sinceStart seconds since the dash started
   * @param airSeconds the dash's constant time ({@code DashConstantTime}); 0 to play the flight at
   *     the clip's rate
   * @param holdMs the landing hold this frame (0 before the landing)
   * @param landingTimeMs the row's {@code DashLandingTime}
   */
  static float leapTime(
      ScScene.Clip clip, float sinceStart, float airSeconds, float holdMs, int landingTimeMs) {
    if (clip == null || clip.fps() <= 0) return 0f;
    int last = clip.frameCount() - 1;
    int js = labelOr(clip, "jump_start", 0);
    int je = labelOr(clip, "jump_end", last);
    int rs = clip.labelFrame("recover_start");
    int re = labelOr(clip, "recover_end", last);
    boolean landed = holdMs > 0 && (airSeconds <= 0 || sinceStart >= airSeconds);
    float frame;
    if (landed && rs >= 0) {
      // the hold's readings run from the landing (HOLD_AT_LANDING_MS - one tick, this frame
      // interpolated) up to the last one before the unit walks: DashLandingTime - one tick
      float span = Math.max(TICK_MS, landingTimeMs - 2 * TICK_MS);
      float p = clamp((holdMs - (HOLD_AT_LANDING_MS - TICK_MS)) / span);
      frame = rs + p * (re - rs);
    } else if (airSeconds > 0) {
      frame = js + clamp(sinceStart / airSeconds) * (je - js);
    } else {
      frame = Math.min(je, js + sinceStart * clip.fps());
    }
    return at(clip, (int) Math.floor(frame));
  }

  /**
   * Seconds into a river jump's own clip (Royal Hogs' {@code jump1}): played so its landing frame
   * ({@code action_frame1}, else its last) comes as the jump lands, the jump lasting its distance
   * over the jump speed (both game units, the speed per tick).
   */
  static float riverJumpTime(ScScene.Clip clip, float sinceStart, int distance, int jumpSpeed) {
    if (clip == null || clip.fps() <= 0) return sinceStart;
    int land = labelOr(clip, "action_frame1", clip.frameCount() - 1);
    if (distance <= 0 || jumpSpeed <= 0 || land <= 0) return sinceStart;
    float duration = distance / (float) jumpSpeed * TICK_MS / 1000f;
    return sinceStart * (land / (float) clip.fps()) / duration;
  }

  /**
   * Seconds into the Fisherman's {@code loading1} clip while his hook pulls: its pull ({@code
   * pull_start..pull_end}) at the clip's rate from the pull's start, held on its last frame; -1 for
   * a clip without the labels.
   */
  static float pullTime(ScScene.Clip clip, float sinceStart) {
    if (clip == null || clip.fps() <= 0) return -1f;
    int ps = clip.labelFrame("pull_start");
    int pe = labelOr(clip, "pull_end", clip.frameCount() - 1);
    if (ps < 0 || pe < ps) return -1f;
    return at(clip, Math.min(pe, ps + (int) Math.floor(Math.max(0f, sinceStart) * clip.fps())));
  }

  /**
   * The dash clip of a chained dash: {@code dash1}, {@code dash2}, … in turn by the dashes the
   * chain has made, over the variants the unit has (Golden Knight: 4).
   */
  static String dashVariant(Predicate<String> has, int links) {
    int n = 1;
    while (has.test("dash" + (n + 1))) n++;
    int k = Math.floorMod(links, n);
    return k == 0 ? "dash" : "dash" + (k + 1);
  }

  private static int labelOr(ScScene.Clip clip, String label, int fallback) {
    int f = clip.labelFrame(label);
    return f >= 0 ? f : fallback;
  }

  /** A time inside the frame, so the drawer's floor lands on it. */
  private static float at(ScScene.Clip clip, int frame) {
    int f = Math.max(0, Math.min(clip.frameCount() - 1, frame));
    return (f + 0.5f) / clip.fps();
  }

  private static float clamp(float v) {
    return Math.max(0f, Math.min(1f, v));
  }
}
