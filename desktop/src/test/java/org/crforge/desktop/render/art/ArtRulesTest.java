/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.render.art;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.crforge.desktop.render.ViewOrientation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtRulesTest {

  @Test
  void facingExportsCoverTheRightHalfAndMirrorTheLeft() {
    // nine exports: 1 up, 5 right, 9 down, 22.5 degrees apart
    assertThat(ArtRules.facingExport(ArtRules.facingDegrees(0, 1), 9)).containsExactly(1, 0);
    assertThat(ArtRules.facingExport(ArtRules.facingDegrees(1, 0), 9)).containsExactly(5, 0);
    assertThat(ArtRules.facingExport(ArtRules.facingDegrees(0, -1), 9)).containsExactly(9, 0);
    assertThat(ArtRules.facingExport(ArtRules.facingDegrees(-1, 0), 9)).containsExactly(5, 1);
    assertThat(ArtRules.facingExport(ArtRules.facingDegrees(1, 1), 9)).containsExactly(3, 0);
    assertThat(ArtRules.facingExport(ArtRules.facingDegrees(-1, -1), 9)).containsExactly(7, 1);
    // a turret's 36 timeline frames over the same half circle: frame 18 (0-based) faces right
    assertThat(ArtRules.facingExport(90f, 36)[0] - 1).isEqualTo(18);
    // a single export never mirrors
    assertThat(ArtRules.facingExport(-90f, 1)).containsExactly(1, 0);
  }

  @Test
  void theArenaIsDrawnShorterThanWideAndHeightsLiftAlongTheCameraTilt() {
    ArtProjection proj = new ArtProjection(29.75f, 0, 0, 18000, 32000);
    ViewOrientation view = new ViewOrientation(1, 18000, 32000);
    assertThat(proj.pixelsPerDisplayUnit()).isCloseTo(1f, within(1e-6f));
    assertThat(proj.pixelsPerTileY()).isCloseTo(24.12f, within(1e-3f));
    assertThat(proj.x(view, 9000)).isCloseTo(proj.arenaOriginX(), within(1e-3f));
    // a Tower Princess (AttachedCharacterHeight 2200) stands 38 display units up
    assertThat(proj.height(2200)).isCloseTo(38.3f, within(0.1f));
  }

  @Test
  void aSceneIsReadAsTheExtractorWritesIt(@TempDir Path dir) throws IOException {
    Path sc = Files.createDirectories(dir.resolve("sc"));
    Files.writeString(
        sc.resolve("chr_test.json"),
        """
        {"format":1,"file":"chr_test.sc","textures":[{"png":"chr_test_0.png","width":4,"height":4}],
         "exports":{"Test_idle1_5":2},
         "shapes":{"1":[{"tex":0,"xy":[0,0,1,0,0,1,1,1],"uv":[0,0,1,0,0,1,1,1]}]},
         "clips":{"2":{"fps":30,"bank":0,"children":[1],"names":["body"],"blend":[0],
                       "frames":[[0,65535,65535],[0,0,65535]],"labels":{"1":"action_frame"},
                       "grid":null}},
         "banks":[{"matrices":[1,0,0,1,5,0],"colors":[]}],"text":[]}
        """);
    ArtCache cache = ArtCache.locateIn(dir).orElseThrow();
    ScScene scene = cache.scene("sc/chr_test.sc");
    assertThat(scene.export("Test_idle1_5")).isEqualTo(2);
    ScScene.Clip clip = scene.clip(2);
    assertThat(clip.frameCount()).isEqualTo(2);
    assertThat(clip.labelFrame("action_frame")).isEqualTo(1);
    assertThat(clip.childNamed("body")).isZero();
    assertThat(scene.shape(1)[0].xy()).hasSize(8);
    assertThat(scene.bank(0).matrices()[4]).isEqualTo(5f);
    assertThat(cache.scene("chr_missing")).isNull();
  }
}
