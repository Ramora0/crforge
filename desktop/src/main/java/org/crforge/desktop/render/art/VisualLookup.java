/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.render.art;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Arrays;
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
 * <name>_<action><n>_<facing>} (docs/realistic-rendering.md, sections 1 and 2).
 */
final class VisualLookup {

  private final ArtCache cache;
  private GameTables tables;
  private final Map<String, Optional<Unit>> units = new HashMap<>();
  private final Map<String, Optional<Projectile>> projectiles = new HashMap<>();
  private ScScene effects;

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

  ScScene effectsScene() {
    if (effects == null) effects = cache.scene("sc/effects.sc");
    return effects;
  }

  /** A character or building's art and the columns that steer it. */
  static final class Unit {
    final ScScene scene;
    final GameRow row;
    final float scale;
    final Map<String, int[]> animations = new HashMap<>();
    int baseExport = -1;
    int topExport = -1;
    int anyExport = -1;
    Unit attached;
    float attachedHeight;
    float flyingHeight;
    boolean rotationOnTimeline;
    int rotationFrames;
    int deployFilter = -1;

    Unit(ScScene scene, GameRow row, float scale) {
      this.scene = scene;
      this.row = row;
      this.scale = scale;
    }

    boolean isTowerLike() {
      return baseExport >= 0 && animations.isEmpty();
    }

    boolean has(String action) {
      return animations.containsKey(action);
    }

    /** Direction exports of the action (0 if none). */
    int directions(String action) {
      int[] dirs = animations.get(action);
      if (dirs == null) return 0;
      int n = 0;
      for (int i = 0; i < dirs.length; i++) if (dirs[i] >= 0) n = i + 1;
      return n;
    }

    /** The export of that action at that facing (1-based), or -1. */
    int animation(String action, int direction) {
      int[] dirs = animations.get(action);
      if (dirs == null) return -1;
      if (direction >= 1 && direction - 1 < dirs.length && dirs[direction - 1] >= 0) {
        return dirs[direction - 1];
      }
      for (int d : dirs) if (d >= 0) return d;
      return -1;
    }

    int integer(String column, int fallback) {
      return RealisticRenderer.integer(row, column, fallback);
    }

    boolean bool(String column) {
      if (row == null) return false;
      JsonNode v = row.columns().get(column);
      return v != null && v.asBoolean(false);
    }
  }

  record Projectile(ScScene scene, int export, int shadow, float scale, GameRow row) {}

  Unit unit(String name, boolean blue) {
    if (tables == null) return null;
    return units
        .computeIfAbsent(
            name + (blue ? "|b" : "|r"), k -> Optional.ofNullable(buildUnit(name, blue, 0)))
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

  private Unit buildUnit(String name, boolean blue, int depth) {
    if (depth > 3) return null;
    GameRow row = row("characters", name);
    if (row == null) row = row("buildings", name);
    if (row == null) return null;
    String file = RealisticRenderer.text(row, "FileName");
    ScScene scene = cache.scene(file);
    if (scene == null) return null;
    String base = RealisticRenderer.text(row, blue ? "BlueExportName" : "RedExportName");
    if (base == null || base.isEmpty()) base = RealisticRenderer.text(row, "BlueExportName");
    if (base == null || base.isEmpty()) return null;
    float scale = RealisticRenderer.integer(row, "Scale", 100) / 100f;
    Unit unit = new Unit(scene, row, scale <= 0 ? 1f : scale);
    unit.baseExport = scene.export(base);
    String top = RealisticRenderer.text(row, blue ? "BlueTopExportName" : "RedTopExportName");
    if (top != null && !top.isEmpty()) unit.topExport = scene.export(top);
    collectAnimations(unit, scene, base, "");
    collectAnimations(unit, scene, base + "_shield", "shield:");
    unit.anyExport = unit.baseExport;
    if (unit.anyExport < 0) unit.anyExport = unit.animation("idle", 5);
    if (unit.anyExport < 0) {
      for (Map.Entry<String, Integer> e : scene.exports().entrySet()) {
        if (e.getKey().startsWith(base)) {
          unit.anyExport = e.getValue();
          break;
        }
      }
    }
    unit.rotationOnTimeline = unit.bool("HasRotationOnTimeline");
    if (unit.rotationOnTimeline && unit.baseExport >= 0) {
      unit.rotationFrames = maxFrames(scene, unit.baseExport, 0);
    }
    String rider = RealisticRenderer.text(row, "AttachedCharacter");
    if (rider != null && !rider.isEmpty() && !rider.equals(name)) {
      unit.attached = buildUnit(rider, blue, depth + 1);
      unit.attachedHeight = RealisticRenderer.integer(row, "AttachedCharacterHeight", 0);
    }
    unit.flyingHeight = RealisticRenderer.integer(row, "FlyingHeight", 0);
    String filter = RealisticRenderer.text(row, "DeployBaseAnimExportName");
    ScScene fx = effectsScene();
    if (fx != null && filter != null && !filter.isEmpty()) unit.deployFilter = fx.export(filter);
    return unit;
  }

  /** The longest timeline among a clip and its children (a turret's rotation frames). */
  private static int maxFrames(ScScene scene, int id, int depth) {
    ScScene.Clip c = scene.clip(id);
    if (c == null || depth > 4) return 1;
    int n = c.frameCount();
    for (int child : c.children()) n = Math.max(n, maxFrames(scene, child, depth + 1));
    return n;
  }

  private static void collectAnimations(Unit unit, ScScene scene, String base, String prefix) {
    Pattern p = Pattern.compile("^" + Pattern.quote(base) + "_([A-Za-z]+)(\\d*)_(\\d+)$");
    // per action, the lowest variant number wins; attack2/attack3 stay separate (attack sequence)
    Map<String, Integer> variantOf = new HashMap<>();
    for (Map.Entry<String, Integer> e : scene.exports().entrySet()) {
      Matcher m = p.matcher(e.getKey());
      if (!m.matches()) continue;
      String action = m.group(1).toLowerCase(Locale.ROOT);
      int variant = m.group(2).isEmpty() ? 1 : Integer.parseInt(m.group(2));
      int dir = Integer.parseInt(m.group(3));
      if (dir < 1 || dir > 64) continue;
      String key = prefix + (action.equals("attack") && variant > 1 ? "attack" + variant : action);
      int v = key.equals(prefix + action) ? variant : 1;
      Integer best = variantOf.get(key);
      if (best != null && v > best) continue;
      if (best == null || v < best) {
        variantOf.put(key, v);
        unit.animations.put(key, filled(new int[18]));
      }
      int[] dirs = unit.animations.get(key);
      if (dir > dirs.length) {
        int[] grown = filled(new int[dir]);
        System.arraycopy(dirs, 0, grown, 0, dirs.length);
        dirs = grown;
        unit.animations.put(key, dirs);
      }
      dirs[dir - 1] = e.getValue();
    }
  }

  private static int[] filled(int[] a) {
    Arrays.fill(a, -1);
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
              String export = blue ? null : RealisticRenderer.text(row, "RedExportName");
              if (export == null || export.isEmpty()) {
                export = RealisticRenderer.text(row, "ExportName");
              }
              int id = export == null ? -1 : scene.export(export);
              if (id < 0) return Optional.empty();
              String shadowName = blue ? null : RealisticRenderer.text(row, "RedShadowExportName");
              if (shadowName == null || shadowName.isEmpty()) {
                shadowName = RealisticRenderer.text(row, "ShadowExportName");
              }
              int shadow = shadowName == null ? -1 : scene.export(shadowName);
              float scale = RealisticRenderer.integer(row, "Scale", 100) / 100f;
              return Optional.of(new Projectile(scene, id, shadow, scale <= 0 ? 1f : scale, row));
            })
        .orElse(null);
  }
}
