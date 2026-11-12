/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.render.art;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DashArtTest {

  /** A clip of {@code frames} frames at {@code fps} with those labels and nothing to draw. */
  private static ScScene.Clip clip(int frames, int fps, Map<Integer, String> labels) {
    return new ScScene.Clip(
        1, fps, 0, new int[0], new String[0], new int[0], new int[frames][], labels);
  }

  /** The Mega Knight's {@code mega_knight_jump1_N}: 85 frames at 60 fps (sc/chr_mega_knight). */
  private static final ScScene.Clip MEGA_KNIGHT_JUMP =
      clip(
          85,
          60,
          Map.of(
              0, "charge_start",
              13, "charge_end",
              14, "jump_start",
              53, "jump_end",
              54, "recover_start",
              84, "recover_end"));

  private static int frame(ScScene.Clip clip, float seconds) {
    return (int) Math.floor(seconds * clip.fps());
  }

  @Test
  void theCrouchEndsAsTheWindupRunsOut() {
    // 14 frames at 60 fps: the crouch takes the wind-up's last 233 ms
    assertThat(DashArt.windupTime(MEGA_KNIGHT_JUMP, 0.5f)).isNegative();
    assertThat(frame(MEGA_KNIGHT_JUMP, DashArt.windupTime(MEGA_KNIGHT_JUMP, 14 / 60f))).isZero();
    assertThat(frame(MEGA_KNIGHT_JUMP, DashArt.windupTime(MEGA_KNIGHT_JUMP, 0f))).isEqualTo(13);
    // a clip without the labels has no crouch
    assertThat(DashArt.windupTime(clip(30, 30, Map.of()), 0f)).isNegative();
  }

  @Test
  void theFlightSpansTheConstantTimeAndTheRecoverTheLandingHold() {
    float air = 0.8f; // DashConstantTime 800
    int landing = 300; // DashLandingTime 300
    assertThat(frame(MEGA_KNIGHT_JUMP, DashArt.leapTime(MEGA_KNIGHT_JUMP, 0f, air, 0, landing)))
        .isEqualTo(14);
    assertThat(frame(MEGA_KNIGHT_JUMP, DashArt.leapTime(MEGA_KNIGHT_JUMP, 0.4f, air, 0, landing)))
        .isEqualTo(33);
    // still in the air at its constant time's end, and no landing hold yet: the last flight frame
    assertThat(frame(MEGA_KNIGHT_JUMP, DashArt.leapTime(MEGA_KNIGHT_JUMP, 0.9f, air, 0, landing)))
        .isEqualTo(53);
    // the hold reads 50 as the landing starts (interpolated) and 250 on its last tick
    assertThat(frame(MEGA_KNIGHT_JUMP, DashArt.leapTime(MEGA_KNIGHT_JUMP, 0.85f, air, 50, landing)))
        .isEqualTo(54);
    assertThat(frame(MEGA_KNIGHT_JUMP, DashArt.leapTime(MEGA_KNIGHT_JUMP, 1.0f, air, 250, landing)))
        .isEqualTo(84);
  }

  @Test
  void aRiverJumpClipLandsOnItsLandingFrame() {
    // Royal Hogs' jump1: 35 frames at 60 fps, action_frame1 (the landing) at 29
    ScScene.Clip hog = clip(35, 60, Map.of(29, "action_frame1"));
    // a jump of 3200 at 160 a tick lasts 20 ticks, one second
    assertThat(DashArt.riverJumpTime(hog, 1f, 3200, 160)).isCloseTo(29 / 60f, within(1e-4f));
    assertThat(frame(hog, DashArt.riverJumpTime(hog, 0.5f, 3200, 160))).isEqualTo(14);
  }

  @Test
  void theFishermanPullsFromHisLoadingClipsPullLabel() {
    ScScene.Clip loading =
        clip(96, 60, Map.of(0, "loop_start", 74, "pull_start", 80, "pull_hold", 95, "pull_end"));
    assertThat(frame(loading, DashArt.pullTime(loading, 0f))).isEqualTo(74);
    assertThat(frame(loading, DashArt.pullTime(loading, 2f))).isEqualTo(95);
    assertThat(DashArt.pullTime(clip(30, 30, Map.of()), 0f)).isNegative();
  }

  @Test
  void chainedDashesTakeTheDashClipsInTurn() {
    Set<String> goldenKnight = Set.of("dash", "dash2", "dash3", "dash4");
    assertThat(DashArt.dashVariant(goldenKnight::contains, 0)).isEqualTo("dash");
    assertThat(DashArt.dashVariant(goldenKnight::contains, 1)).isEqualTo("dash2");
    assertThat(DashArt.dashVariant(goldenKnight::contains, 5)).isEqualTo("dash2");
    Set<String> bandit = Set.of("dash");
    assertThat(DashArt.dashVariant(bandit::contains, 3)).isEqualTo("dash");
  }

  @Test
  void aDashFilterComposesOverTheDamageFlash() {
    float[] jitter = {1, 0, 0, 1, 0.5f, -0.3f, 0.6f, 0, 0, 0, 1, 1, 1};
    float[] flash = {1, 0, 0, 1, 0, 0, 1, 0.4f, 0.4f, 0.4f, 1, 1, 1};
    float[] both = EffectLayer.compose(jitter, flash);
    assertThat(both[4]).isEqualTo(0.5f);
    assertThat(both[5]).isEqualTo(-0.3f);
    assertThat(both[6]).isEqualTo(0.6f);
    assertThat(both[7]).isEqualTo(0.4f);
  }
}
