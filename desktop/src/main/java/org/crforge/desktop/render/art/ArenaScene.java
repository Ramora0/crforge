/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.render.art;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.Affine2;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.data.GameTables;
import org.crforge.desktop.battle.BattleFrame;
import org.crforge.desktop.render.ViewOrientation;
import org.crforge.desktop.replay.ReplayFile;

/**
 * The arena as the game composes it: the location row's {@code FileName} names a location file
 * (under {@code locations/}) whose {@code FileName} section is the background (an SC file and
 * export) and whose {@code Objects} section places decorations (rows of the {@code decos} table:
 * {@code FileName}, {@code ExportName}) at game-unit positions, x across the arena and y from its
 * far edge.
 */
@Slf4j
public final class ArenaScene {

  /** The location drawn when the battle's own cannot be resolved. */
  public static final String FALLBACK_LOCATION = "PvP_royal";

  private record Placed(ScScene scene, int export, float x, float y) {}

  private final List<Placed> ground = new ArrayList<>();
  private final Affine2 transform = new Affine2();
  private final Color white = new Color(1, 1, 1, 1);
  private String locationName;
  private int[] shadow = {0, 0, 100, 50};

  /** The location's shadow colour {@code {ShadowR, ShadowG, ShadowB, ShadowA}} (absent = 0). */
  public int[] shadow() {
    return shadow;
  }

  public ArenaScene(ArtCache cache, GameTables tables, ReplayFile replay) {
    this(cache, tables, System.getProperty("crforge.art.location"));
  }

  public ArenaScene(ArtCache cache, GameTables tables, String location) {
    String name = location != null && !location.isBlank() ? location : FALLBACK_LOCATION;
    if (!load(cache, tables, name) && !name.equals(FALLBACK_LOCATION)) {
      load(cache, tables, FALLBACK_LOCATION);
    }
  }

  public String locationName() {
    return locationName;
  }

  private boolean load(ArtCache cache, GameTables tables, String name) {
    ground.clear();
    GameRow row;
    try {
      row = tables.table("locations").has(name) ? tables.table("locations").row(name) : null;
    } catch (RuntimeException e) {
      row = null;
    }
    String file = RealisticRenderer.text(row, "FileName");
    if (file == null) return false;
    shadow =
        new int[] {
          RealisticRenderer.integer(row, "ShadowR", 0),
          RealisticRenderer.integer(row, "ShadowG", 0),
          RealisticRenderer.integer(row, "ShadowB", 0),
          RealisticRenderer.integer(row, "ShadowA", 50)
        };
    Optional<Path> csv = cache.file(file);
    if (csv.isEmpty()) {
      log.warn("Location {} names {}, which the art cache lacks", name, file);
      return false;
    }
    Map<String, String[]> decos = decos(cache);
    List<String[]> rows = CsvRows.read(csv.get());
    String section = "";
    String currentDeco = null;
    for (String[] r : rows) {
      if (r.length > 0 && !r[0].isEmpty()) {
        section = r[0];
        continue;
      }
      if (section.equals("FileName")) {
        if (r.length >= 3 && r[1].endsWith(".sc")) {
          ScScene scene = cache.scene(r[1]);
          int id = scene == null ? -1 : scene.export(r[2]);
          if (id >= 0) ground.add(new Placed(scene, id, 9000, 0));
        }
      } else if (section.equals("Objects")) {
        if (r.length >= 2 && !r[1].isEmpty()) {
          currentDeco = r[1];
        } else if (currentDeco != null && r.length >= 4 && isInt(r[2])) {
          String[] deco = decos.get(currentDeco);
          if (deco == null) continue;
          ScScene scene = cache.scene(deco[0]);
          int id = scene == null ? -1 : scene.export(deco[1]);
          int y = isInt(r[3]) ? Integer.parseInt(r[3]) : 0;
          if (id >= 0) ground.add(new Placed(scene, id, Integer.parseInt(r[2]), y));
        }
      }
    }
    // the background first, then decorations far to near
    if (ground.size() > 1) {
      List<Placed> objects = new ArrayList<>(ground.subList(1, ground.size()));
      objects.sort(Comparator.comparingDouble(Placed::y));
      Placed bg = ground.get(0);
      ground.clear();
      ground.add(bg);
      ground.addAll(objects);
    }
    locationName = name;
    log.info("Arena {}: {} ({} pieces)", name, file, ground.size());
    return !ground.isEmpty();
  }

  private static boolean isInt(String s) {
    if (s == null || s.isEmpty()) return false;
    try {
      Integer.parseInt(s);
      return true;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  /** decos rows: name -> {FileName, ExportName}. */
  private static Map<String, String[]> decos(ArtCache cache) {
    Map<String, String[]> out = new HashMap<>();
    Optional<Path> file = cache.clientFile("csv_logic/decos.csv");
    if (file.isEmpty()) return out;
    List<String[]> rows = CsvRows.read(file.get());
    if (rows.size() < 2) return out;
    String[] header = rows.get(0);
    int name = indexOf(header, "Name");
    int fileName = indexOf(header, "FileName");
    int export = indexOf(header, "ExportName");
    String lastFile = null;
    for (int i = 2; i < rows.size(); i++) {
      String[] r = rows.get(i);
      if (name < 0 || r.length <= Math.max(fileName, export)) continue;
      String f = r[fileName].isEmpty() ? lastFile : r[fileName];
      lastFile = f;
      if (!r[name].isEmpty()) out.put(r[name], new String[] {f, r[export]});
    }
    return out;
  }

  private static int indexOf(String[] header, String column) {
    for (int i = 0; i < header.length; i++) if (header[i].equals(column)) return i;
    return -1;
  }

  /** Background and decorations. */
  public void drawGround(
      ScDrawer drawer,
      ArtProjection proj,
      ViewOrientation view,
      BattleFrame frame,
      BattleFrame previous,
      float alpha) {
    float s = proj.pixelsPerDisplayUnit();
    float seconds = frame.timeMs() / 1000f;
    drawer.begin();
    for (Placed p : ground) {
      float x = proj.arenaOriginX() + (p.x() - 9000) / 1000f * proj.pixelsPerTile();
      float y = proj.arenaOriginY() - p.y() / 1000f * proj.pixelsPerTileY();
      if (p == ground.get(0)) y -= ArtProjection.ARENA_ART_DY * s; // the background art's fit
      transform.setToTranslation(x, y);
      transform.scale(s, -s);
      ScScene.Clip clip = p.scene().clip(p.export());
      int f = clip == null ? 0 : (int) (seconds * clip.fps());
      drawer.draw(p.scene(), p.export(), f, transform, white);
    }
    drawer.end();
  }

  /** Anything drawn above the units (none yet). */
  public void drawOverlay(
      ScDrawer drawer, ArtProjection proj, ViewOrientation view, BattleFrame frame, float alpha) {}

  /** Minimal CSV reader for the game's decoded tables (quoted fields, no embedded newlines). */
  static final class CsvRows {
    static List<String[]> read(Path file) {
      List<String[]> out = new ArrayList<>();
      try {
        for (String line : Files.readAllLines(file)) out.add(split(line));
      } catch (IOException e) {
        log.warn("Cannot read {}: {}", file, e.getMessage());
      }
      return out;
    }

    static String[] split(String line) {
      List<String> cells = new ArrayList<>();
      StringBuilder cur = new StringBuilder();
      boolean quoted = false;
      for (int i = 0; i < line.length(); i++) {
        char c = line.charAt(i);
        if (quoted) {
          if (c == '"') {
            if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
              cur.append('"');
              i++;
            } else quoted = false;
          } else cur.append(c);
        } else if (c == '"') quoted = true;
        else if (c == ',') {
          cells.add(cur.toString());
          cur.setLength(0);
        } else cur.append(c);
      }
      cells.add(cur.toString());
      return cells.toArray(new String[0]);
    }
  }
}
