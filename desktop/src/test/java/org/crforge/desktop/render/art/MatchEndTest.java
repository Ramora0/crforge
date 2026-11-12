/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.render.art;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.desktop.battle.BattleFrame;
import org.crforge.desktop.battle.EntityView;
import org.crforge.desktop.render.ViewOrientation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MatchEndTest {

  private static final ViewOrientation VIEW = ViewOrientation.SIDE_0;

  private static EntityView troop(int x, int y, int state) {
    return new EntityView(
        7,
        EntityView.Kind.TROOP,
        1,
        "Knight",
        11,
        x,
        y,
        500,
        100,
        100,
        0,
        0,
        false,
        false,
        state,
        false,
        false,
        1200,
        0,
        5500,
        true,
        9000,
        9000,
        0,
        -256,
        0,
        0,
        0,
        0,
        500,
        500,
        500,
        60,
        null,
        null,
        List.of(),
        null,
        EntityView.Bar.HEALTH);
  }

  private static BattleFrame frame(
      int tick, int crowns1, boolean ended, boolean over, int elixir, EntityView e) {
    List<BattleFrame.SideView> sides =
        List.of(
            new BattleFrame.SideView(0, elixir, elixir / 10000, 0, List.of(), null, List.of()),
            new BattleFrame.SideView(
                1, elixir, elixir / 10000, crowns1, List.of(), null, List.of()));
    return new BattleFrame(
        tick,
        (tick - 1) * 50,
        false,
        2,
        List.of(e),
        sides,
        ended,
        ended ? 1 : 0,
        over,
        null,
        List.of());
  }

  @Test
  @DisplayName("after the end the clock and elixir hold and the units stand idle where they stood")
  void freezesAtTheEnd() {
    MatchEnd end = new MatchEnd(null);
    end.update(frame(10, 1, false, false, 40000, troop(1000, 2000, GridEntityState.MOVING)), VIEW);
    BattleFrame atEnd =
        end.update(
            frame(11, 3, true, false, 41000, troop(1100, 2000, GridEntityState.ATTACKING)), VIEW);
    BattleFrame later =
        end.update(
            frame(31, 3, true, false, 60000, troop(1900, 2600, GridEntityState.MOVING)), VIEW);

    EntityView u = later.entities().get(0);
    assertThat(u.x()).isEqualTo(1100);
    assertThat(u.y()).isEqualTo(2000);
    assertThat(u.state()).isEqualTo(GridEntityState.STANDING);
    assertThat(atEnd.entities().get(0).state()).isEqualTo(GridEntityState.STANDING);
    BattleFrame hud = end.hud(later);
    assertThat(hud.timeMs()).isEqualTo(500);
    assertThat(hud.sides().get(1).elixir()).isEqualTo(41000);
    assertThat(hud.sides().get(1).crowns()).isEqualTo(3);
    assertThat(end.endSeconds()).isEqualTo(11 * 0.05f);
  }

  @Test
  @DisplayName("a crown, the end horn and the end screen's jingle and crowns are heard")
  void soundsOfTheEnd() {
    MatchEnd end = new MatchEnd(null);
    end.update(frame(10, 1, false, false, 0, troop(0, 0, 0)), VIEW);
    end.update(frame(11, 3, true, false, 0, troop(0, 0, 0)), VIEW);
    List<String> heard = end.drainSounds().stream().map(s -> s[1]).toList();
    assertThat(heard)
        .containsExactly(
            "soundbanks/ui_battle_general/ui_battle_general_get_star_enemy",
            "soundbanks/music_jingle_general/music_jingle_general_gameend_horn");

    for (int tick = 12; tick <= 100; tick++) {
      end.update(frame(tick, 3, true, tick >= 50, 0, troop(0, 0, 0)), VIEW);
    }
    heard = end.drainSounds().stream().map(s -> s[1]).toList();
    assertThat(heard)
        .containsExactly(
            "soundbanks/music_jingle_general/music_jingle_general_lose",
            "soundbanks/ui_battle_general/ui_battle_general_crown_appear_1",
            "soundbanks/ui_battle_general/ui_battle_general_crown_appear_2",
            "soundbanks/ui_battle_general/ui_battle_general_crown_appear_3");
  }
}
