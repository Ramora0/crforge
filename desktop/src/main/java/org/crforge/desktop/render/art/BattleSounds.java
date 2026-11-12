/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.render.art;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.unit.TowerEntity;
import org.crforge.core.battle.unit.WorldEntity;
import org.crforge.core.battle.unit.WorldObserver;
import org.crforge.desktop.render.ViewOrientation;

/**
 * The battle's own sounds that no unit effect starts, as the battle reports them: a card's
 * {@code CastSound} and its spell {@code Effect}'s sounds when it is played, and the crowd's
 * reaction ({@code CrowdTowerTakeDamage}, its {@code EnemyVersion} for the top side) when a crown
 * tower takes damage in an arena with {@code CrowdEffects}, at most once per 30 s per side.
 */
public final class BattleSounds implements WorldObserver {

  private static final double TICK = 0.05;
  private static final double CROWD_COOLDOWN = 30.0;

  private final GameTables tables;
  private final ViewOrientation view;
  private final boolean crowd;
  private final Map<String, List<String>> effectSounds = new HashMap<>();
  private final Map<String, String> enemyVersion = new HashMap<>();
  private final List<String[]> sounds = new ArrayList<>();
  private final Set<String> heard = new HashSet<>();
  private final double[] lastCrowd = {-1e9, -1e9};

  public BattleSounds(ArtCache cache, GameTables tables, ViewOrientation view, String location) {
    this.tables = tables;
    this.view = view;
    GameRow loc = row("locations", location);
    JsonNode crowdColumn = loc == null ? null : loc.columns().get("CrowdEffects");
    this.crowd = crowdColumn != null && crowdColumn.asBoolean(false);
    cache
        .clientFile("legacy_vfx/effects.json")
        .ifPresent(
            p -> {
              try {
                JsonNode root = new ObjectMapper().readTree(p.toFile());
                root.properties()
                    .forEach(
                        e -> {
                          List<String> refs = new ArrayList<>();
                          for (JsonNode c : e.getValue().path("RenderableComponents")) {
                            String s = c.path("Sound").asText("");
                            if (!s.isEmpty()) refs.add(s);
                          }
                          effectSounds.put(e.getKey(), refs);
                          String enemy = e.getValue().path("EnemyVersion").asText("");
                          if (!enemy.isEmpty()) enemyVersion.put(e.getKey(), enemy);
                        });
              } catch (IOException ex) {
                // no effect sounds: the cards' own CastSound still plays
              }
            });
  }

  /** The location's match-start jingle (its {@code Sound} column), for a recording from 0 s. */
  public void matchStart(String location, double seconds) {
    String jingle = RealisticRenderer.text(row("locations", location), "Sound");
    if (jingle != null && !jingle.isEmpty()) add(seconds, jingle);
  }

  /** Returns and clears the sounds heard so far, as {@code {seconds, soundRef}}. */
  public List<String[]> drain() {
    List<String[]> out = new ArrayList<>(sounds);
    sounds.clear();
    return out;
  }

  @Override
  public void cardPlayHeard(
      int tick,
      WorldEntity owner,
      String action,
      int side,
      String played,
      String deployed,
      int total,
      String scheduled) {
    if (played == null || !heard.add(tick + "|" + side + "|" + played)) return;
    GameRow card = null;
    boolean spell = false;
    for (String table :
        List.of("spells_characters", "spells_buildings", "spells_other", "spells_evolved")) {
      card = row(table, played);
      if (card != null) {
        spell = table.equals("spells_other");
        break;
      }
    }
    if (card == null) return;
    double t = tick * TICK;
    String cast = RealisticRenderer.text(card, "CastSound");
    if (cast != null && !cast.isEmpty()) add(t, cast);
    // a spell's cast effect (Arrows' Cast_RainOfArrows): troops' deploy sounds come with their
    // SpawnEffect from the effect layer
    String effect = RealisticRenderer.text(card, "Effect");
    if (spell && effect != null && !effect.isEmpty()) {
      boolean top = !view.blue(side);
      String name = top ? enemyVersion.getOrDefault(effect, effect) : effect;
      for (String s : effectSounds.getOrDefault(name, List.of())) add(t, s);
    }
  }

  @Override
  public void damageDealt(
      int tick,
      WorldEntity target,
      int damage,
      org.crforge.core.pathfinding.combat.DamageResult result) {
    if (!crowd || !(target instanceof TowerEntity) || damage <= 0) return;
    int side = target.side();
    double t = tick * TICK;
    int k = view.blue(side) ? 0 : 1;
    if (t - lastCrowd[k] < CROWD_COOLDOWN) return;
    lastCrowd[k] = t;
    String effect = "CrowdTowerTakeDamage";
    if (k == 1) effect = enemyVersion.getOrDefault(effect, effect);
    for (String s : effectSounds.getOrDefault(effect, List.of())) add(t, s);
  }

  private void add(double seconds, String ref) {
    sounds.add(new String[] {String.valueOf((float) seconds), ref});
  }

  private GameRow row(String table, String name) {
    if (tables == null || name == null) return null;
    try {
      var t = tables.table(table);
      return t != null && t.has(name) ? t.row(name) : null;
    } catch (RuntimeException e) {
      return null;
    }
  }
}
