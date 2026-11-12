/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.render.art;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.data.GameTables;

/**
 * Resolves a table row to the art drawn for it: the SC2 file its {@code FileName} names and the
 * exports of its {@code BlueExportName} / {@code RedExportName}, grouped as {@code
 * <name>_<action><n>_<facing>}.
 */
final class VisualLookup {

  private final ArtCache cache;
  private GameTables tables;
  private final Map<String, Optional<Unit>> units = new HashMap<>();
  private final Map<String, Optional<Projectile>> projectiles = new HashMap<>();

  VisualLookup(ArtCache cache) {
    this.cache = cache;
  }

  void setTables(GameTables tables) {
    if (this.tables != tables) {
      this.tables = tables;
      units.clear();
      projectiles.clear();
    }
  }

  /** A character or building's art. */
  static final class Unit {
    private final ScScene scene;
    private final float scale;
    private final Map<String, int[]> animations = new HashMap<>();
    private int baseExport = -1;
    private int topExport = -1;
    private int anyExport = -1;
    private Unit attached;
    private float attachedHeight;
    private float flyingHeight;

    Unit(ScScene scene, float scale) {
      this.scene = scene;
      this.scale = scale;
    }

    ScScene scene() {
      return scene;
    }

    float scale() {
      return scale;
    }

    int baseExport() {
      return baseExport;
    }

    int topExport() {
      return topExport;
    }

    int anyExport() {
      return anyExport;
    }

    Unit attached() {
      return attached;
    }

    /** Height of the attached character above the ground, game units. */
    float attachedHeight() {
      return attachedHeight;
    }

    /** Flying height, game units. */
    float flyingHeight() {
      return flyingHeight;
    }

    boolean isTowerLike() {
      return baseExport >= 0 && animations.isEmpty();
    }

    /** The export of that action at that facing (1-based), or -1. */
    int animation(String action, int direction) {
      int[] dirs = animations.get(action);
      if (dirs == null) return -1;
      if (direction - 1 < dirs.length && dirs[direction - 1] >= 0) return dirs[direction - 1];
      for (int d : dirs) if (d >= 0) return d;
      return -1;
    }
  }

  record Projectile(ScScene scene, int export, float scale) {}

  Unit unit(String name, boolean blue) {
    if (tables == null) return null;
    return units
        .computeIfAbsent(
            name + (blue ? "|b" : "|r"), k -> Optional.ofNullable(buildUnit(name, blue)))
        .orElse(null);
  }

  private GameRow row(String table, String name) {
    try {
      var t = tables.table(table);
      return t != null && t.has(name) ? t.row(name) : null;
    } catch (RuntimeException e) {
      return null;
    }
  }

  private Unit buildUnit(String name, boolean blue) {
    GameRow row = row("characters", name);
    if (row == null) row = row("buildings", name);
    if (row == null) return null;
    String file = RealisticRenderer.text(row, "FileName");
    ScScene scene = cache.scene(file);
    if (scene == null) return null;
    String base = RealisticRenderer.text(row, blue ? "BlueExportName" : "RedExportName");
    if (base == null) base = RealisticRenderer.text(row, "BlueExportName");
    if (base == null) return null;
    float scale = RealisticRenderer.integer(row, "Scale", 100) / 100f;
    Unit unit = new Unit(scene, scale <= 0 ? 1f : scale);
    unit.baseExport = scene.export(base);
    String top = RealisticRenderer.text(row, blue ? "BlueTopExportName" : "RedTopExportName");
    if (top != null) unit.topExport = scene.export(top);
    collectAnimations(unit, scene, base);
    unit.anyExport = unit.baseExport;
    if (unit.anyExport < 0) {
      for (Map.Entry<String, Integer> e : scene.exports().entrySet()) {
        if (e.getKey().startsWith(base)) {
          unit.anyExport = e.getValue();
          break;
        }
      }
    }
    String rider = RealisticRenderer.text(row, "AttachedCharacter");
    if (rider != null && !rider.equals(name)) {
      unit.attached = buildUnit(rider, blue);
      unit.attachedHeight = RealisticRenderer.integer(row, "AttachedCharacterHeight", 0);
    }
    unit.flyingHeight = RealisticRenderer.integer(row, "FlyingHeight", 0);
    return unit;
  }

  private static void collectAnimations(Unit unit, ScScene scene, String base) {
    Pattern p = Pattern.compile("^" + Pattern.quote(base) + "_([A-Za-z]+)(\\d*)_(\\d+)$");
    // prefer variant 1 (or no variant) of each action
    Map<String, Integer> variantOf = new HashMap<>();
    for (Map.Entry<String, Integer> e : scene.exports().entrySet()) {
      Matcher m = p.matcher(e.getKey());
      if (!m.matches()) continue;
      String action = m.group(1).toLowerCase(Locale.ROOT);
      int variant = m.group(2).isEmpty() ? 1 : Integer.parseInt(m.group(2));
      int dir = Integer.parseInt(m.group(3));
      if (dir < 1 || dir > 32) continue;
      Integer best = variantOf.get(action);
      if (best != null && best < variant) continue;
      if (best == null || variant < best) {
        variantOf.put(action, variant);
        unit.animations.put(action, filled(new int[ArtRules.DIRECTIONS]));
      }
      int[] dirs = unit.animations.get(action);
      if (dir > dirs.length) {
        int[] grown = filled(new int[dir]);
        System.arraycopy(dirs, 0, grown, 0, dirs.length);
        dirs = grown;
        unit.animations.put(action, dirs);
      }
      dirs[dir - 1] = e.getValue();
    }
  }

  private static int[] filled(int[] a) {
    java.util.Arrays.fill(a, -1);
    return a;
  }

  Projectile projectile(String name, boolean blue) {
    return visual("projectiles", name, blue);
  }

  Projectile areaEffect(String name, boolean blue) {
    return visual("area_effect_objects", name, blue);
  }

  private Projectile visual(String table, String name, boolean blue) {
    if (tables == null) return null;
    return projectiles
        .computeIfAbsent(
            table + "|" + name + (blue ? "|b" : "|r"),
            k -> {
              GameRow row = row(table, name);
              if (row == null) return Optional.empty();
              ScScene scene = cache.scene(RealisticRenderer.text(row, "FileName"));
              if (scene == null) return Optional.empty();
              String export = RealisticRenderer.text(row, blue ? "ExportName" : "RedExportName");
              if (export == null) export = RealisticRenderer.text(row, "ExportName");
              int id = export == null ? -1 : scene.export(export);
              if (id < 0) return Optional.empty();
              float scale = RealisticRenderer.integer(row, "Scale", 100) / 100f;
              return Optional.of(new Projectile(scene, id, scale <= 0 ? 1f : scale));
            })
        .orElse(null);
  }
}
