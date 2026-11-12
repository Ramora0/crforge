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
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.data.GameTables;
import org.crforge.desktop.battle.BattleFrame;
import org.crforge.desktop.battle.EntityView;
import org.crforge.desktop.render.ViewOrientation;

/**
 * The battle's own HUD drawn with the game's UI art ({@code sc/ui.sc}): health bars and level
 * badges over troops, buildings and towers ({@code csv_client/health_bars.csv} picks the export per
 * {@code HealthBar} class), the replay HUD's two hand panels with elixir bars and card art ({@code
 * sc/ui_spells.sc}, by the cards' {@code IconFile}), the time left and the crowns.
 *
 * <p>Layout: the HUD clips are drawn at {@code width / 600} pixels per display unit (the game's
 * portrait HUD is about 600 units wide); the bottom panel is anchored at the bottom centre of the
 * frame, the top panel and the timer at the top centre, the crowns at the right edge level with the
 * river. Health bars are drawn at the arena's own scale.
 */
@Slf4j
public final class BattleHud implements Disposable {

  /** Design width of the HUD clips in display units. */
  private static final float HUD_WIDTH_DU = 600f;

  /** Share of the frame's height above the arena that the top hand panel fills. */
  private static final float TOP_ZONE = 0.135f;

  /** Depth of the top hand panel (panel and cards) in display units. */
  private static final float TOP_PANEL_DU = 140f;

  /** The crown badge of the king's bar (hp_*_wizard), shown alone while the king is unhurt. */
  private static final int KING_CROWN = 16329;

  private static final int KING_CROWN_SHAPE = 15936;

  /** How far the player's tower hit points are lifted in their clips, in display units. */
  private static final float KING_NUMBER_LIFT = 5f;

  private static final float PRINCESS_NUMBER_LIFT = 3.5f;

  /** Match length before overtime, and overtime, in ms (the Default battle timeline). */
  private static final int REGULAR_MS = 180_000;

  private static final int OVERTIME_MS = 120_000;

  /** One text field of ui.sc: its box in its own display units and its style. */
  private record TextField(
      float left,
      float top,
      float right,
      float bottom,
      int size,
      int color,
      int outline,
      int align) {}

  /** The text fields the HUD fills, read off ui.sc (ids are this file's object ids). */
  private static final Map<Integer, TextField> TEXT = new HashMap<>();

  static {
    int c = Align.center;
    int l = Align.left;
    // the player's tower numbers hang from their box's top, which puts them on the bar (centred
    // in their tall box they would hang below it)
    int lt = Align.left | Align.top;
    // unit bar level badges (player, enemy); a size under the clip's 14 keeps the game font's
    // heavy digits inside the badge, as the game's own render does
    TEXT.put(15485, new TextField(11, 5, 27, 23, 13, 0xffffffff, 0xff022853, c));
    TEXT.put(16060, new TextField(11, 5, 27, 23, 13, 0xffffffff, 0xff510018, c));
    TEXT.put(15487, new TextField(11, 5, 27, 24, 13, 0xffffffff, 0xff022853, c));
    TEXT.put(15489, new TextField(11, 5, 27, 24, 13, 0xffffffff, 0xff022853, c));
    TEXT.put(15493, new TextField(11, 5, 27, 24, 13, 0xffffffff, 0xff510303, c));
    TEXT.put(15476, new TextField(11, 5, 27, 24, 13, 0xffffffff, 0xff510303, c));
    // evolved units' badges (enemy, player)
    TEXT.put(15475, new TextField(11, 5, 27, 23, 13, 0xffffffff, 0xff500007, c));
    TEXT.put(15483, new TextField(11, 5, 27, 23, 13, 0xffffffff, 0xff1f0651, c));
    // princess tower bar: level and hit points (player, enemy)
    TEXT.put(16080, new TextField(-2, 11, 18, 29, 14, 0xfffede2f, 0xff000000, c));
    TEXT.put(16083, new TextField(-2, 11, 90, 31, 16, 0xffbddae9, 0xff013255, lt));
    TEXT.put(16081, new TextField(-2, 11, 90, 31, 16, 0xffffe2ea, 0xff750023, l));
    // king tower bar
    TEXT.put(16084, new TextField(-2, -2, 32, 21, 18, 0xffffdf30, 0xff000000, c));
    TEXT.put(15481, new TextField(-2, -2, 90, 21, 18, 0xffbedbea, 0xff000000, lt));
    TEXT.put(16085, new TextField(-2, -2, 90, 21, 18, 0xfffed5f5, 0xff000000, l));
    // replay hand panel
    TEXT.put(1337, new TextField(-2, -2, 54, 42, 34, 0xffffffff, 0xff000000, c));
    TEXT.put(1340, new TextField(-2, -2, 64, 21, 18, 0xffe1ddc9, 0xff000000, c));
    // card elixir drop
    TEXT.put(4917, new TextField(-6, -2, 28, 36, 28, 0xffffffff, 0xff000000, c));
    // timer
    TEXT.put(1365, new TextField(-16, -2, 84, 30, 28, 0xffffffff, 0xff000000, c));
    TEXT.put(1368, new TextField(17, -2, 107, 16, 14, 0xfffeecad, 0xff000000, c));
    TEXT.put(1366, new TextField(17, -2, 106, 17, 14, 0xffff9900, 0xff000000, c));
    TEXT.put(1367, new TextField(-2, -2, 54, 36, 30, 0xffffe2fd, 0xff000000, c));
    // crowns (enemy, player)
    TEXT.put(1363, new TextField(-2, -2, 54, 36, 31, 0xfff23d41, 0xff000000, c));
    TEXT.put(1364, new TextField(-2, -2, 54, 36, 31, 0xff66ccff, 0xff000000, c));
  }

  /** A row of health_bars.csv. */
  private record BarStyle(
      String player,
      String enemy,
      String noDamagePlayer,
      String noDamageEnemy,
      int segments,
      String playerDivider,
      String enemyDivider) {}

  /** Where a unit's bar goes: its sprite's top (du, y down, already scaled) and offsets. */
  private record BarPlace(
      String barClass, float topDu, float bottomDu, float offsetBlue, float offsetRed) {}

  private final ArtCache cache;
  private final ScDrawer drawer;
  private final HudArt art;
  private final HudFonts fonts = new HudFonts();
  private final VisualLookup lookup;
  private final Affine2 transform = new Affine2();
  private final Affine2 scratch = new Affine2();
  private final Color tint = new Color(1, 1, 1, 1);
  private final Map<String, BarStyle> barStyles = new HashMap<>();
  private final Map<String, Optional<BarPlace>> places = new HashMap<>();
  private final Map<String, Optional<CardArt>> cardArts = new HashMap<>();
  private final Map<Integer, EntityView> before = new HashMap<>();
  private float alpha = 1f;
  private GameTables tables;
  private boolean tablesTried;
  private boolean stylesLoaded;

  public BattleHud(ArtCache cache, ScDrawer drawer) {
    this.cache = cache;
    this.drawer = drawer;
    this.art = new HudArt(cache);
    this.lookup = new VisualLookup(cache);
  }

  /** The tables the units' rows are read from (bar classes, offsets, card icons). */
  public void setTables(GameTables tables) {
    if (this.tables != tables) {
      this.tables = tables;
      lookup.setTables(tables);
      places.clear();
      cardArts.clear();
    }
  }

  private GameTables tables() {
    if (tables == null && !tablesTried) {
      tablesTried = true;
      try {
        var choice = org.crforge.desktop.DataSelection.ofProcess();
        if (choice.problem() == null) setTables(GameTables.load(choice.folder()));
        else setTables(GameTables.loadConfigured());
      } catch (RuntimeException e) {
        log.warn("The HUD has no game tables: {}", e.getMessage());
      }
    }
    return tables;
  }

  public void draw(
      BattleFrame frame,
      ViewOrientation view,
      ArtProjection proj,
      int width,
      int height,
      Matrix4 projection) {
    draw(frame, null, 1f, view, proj, width, height, projection);
  }

  /**
   * Draws the HUD of {@code frame}, the units' bars at their positions interpolated from {@code
   * previous} (may be null) by {@code alpha} in [0, 1], as the renderer places their sprites.
   */
  public void draw(
      BattleFrame frame,
      BattleFrame previous,
      float alpha,
      ViewOrientation view,
      ArtProjection proj,
      int width,
      int height,
      Matrix4 projection) {
    ScScene ui = prepare(frame, previous, alpha);
    if (ui == null) return;
    art.begin(drawer.batch(), projection);
    try {
      drawHealthBars(ui, frame, view, proj);
      drawDeployTimers(ui, frame, view, proj);
      float s = width / HUD_WIDTH_DU;
      // the top panel's lower edge sits on the top zone's edge (the zone is deeper than the
      // panel: the game leaves the phone's status bar above it); the bottom panel sits on the
      // frame's bottom edge
      float topY = height - Math.max(0f, TOP_ZONE * height - TOP_PANEL_DU * s);
      if (frame.sides().size() >= 2) {
        drawPanel(ui, frame, view, width, 0f, s, false);
        drawPanel(ui, frame, view, width, topY, s, true);
      }
      drawTimer(ui, frame, width, topY, s);
      if (frame.sides().size() >= 2) drawCrowns(ui, frame, view, proj, width, s);
    } finally {
      art.end();
    }
  }

  /**
   * Draws only the HUD inside the arena of {@code frame}: the units' and towers' health bars and
   * the deploy clocks, without the hand panels, the timer or the crowns (for views that draw their
   * own). Units are placed as in {@link #draw(BattleFrame, BattleFrame, float, ViewOrientation,
   * ArtProjection, int, int, Matrix4)}.
   */
  public void drawWorld(
      BattleFrame frame,
      BattleFrame previous,
      float alpha,
      ViewOrientation view,
      ArtProjection proj,
      Matrix4 projection) {
    ScScene ui = prepare(frame, previous, alpha);
    if (ui == null) return;
    art.begin(drawer.batch(), projection);
    try {
      drawHealthBars(ui, frame, view, proj);
      drawDeployTimers(ui, frame, view, proj);
    } finally {
      art.end();
    }
  }

  /** Readies a draw of {@code frame} (interpolation, tables, bar styles); returns the UI scene. */
  private ScScene prepare(BattleFrame frame, BattleFrame previous, float alpha) {
    before.clear();
    if (previous != null && alpha < 1f) {
      for (EntityView e : previous.entities()) before.put(e.id(), e);
    }
    this.alpha = alpha;
    ScScene ui = cache.scene("sc/ui.sc");
    if (ui == null || frame == null) return null;
    tables();
    loadBarStyles();
    return ui;
  }

  // ---------------------------------------------------------------- deploy timers

  /** First tick each deploying unit was seen. */
  private final Map<Integer, Integer> deploySeen = new HashMap<>();

  /**
   * The deploy clock ({@code troopDeployTimer_player/_enemy}: 324 frames at 30 fps, {@code end} at
   * 299) at each card play that is still deploying, one per play (the units of one card appear on
   * one tick), timed to end when the row's {@code DeployTime} runs out.
   */
  private void drawDeployTimers(
      ScScene ui, BattleFrame frame, ViewOrientation view, ArtProjection p) {
    Map<String, float[]> plays = new HashMap<>();
    java.util.Set<Integer> live = new java.util.HashSet<>();
    for (EntityView e : frame.entities()) {
      if (!e.isCharacter() || e.kind() == EntityView.Kind.TOWER) continue;
      if (!e.deploying()) continue;
      live.add(e.id());
      int seen = deploySeen.computeIfAbsent(e.id(), k -> frame.tick());
      String key = seen + "|" + e.name() + "|" + e.side();
      float[] acc = plays.computeIfAbsent(key, k -> new float[] {0, 0, 0, seen, 0});
      acc[0] += e.x();
      acc[1] += e.y();
      acc[2] += 1;
      acc[4] = view.blue(e.side()) ? 1 : 0;
      VisualLookup.Unit unit = lookup.unit(e.name(), view.blue(e.side()));
      int deployMs = unit == null ? 1000 : unit.integer("DeployTime", 1000);
      plays.get(key)[3] = seen + deployMs / 50f; // the tick it ends
    }
    deploySeen.keySet().retainAll(live);
    if (plays.isEmpty()) return;
    float ppdu = p.pixelsPerDisplayUnit();
    float now = frame.tick() - 1 + alpha;
    for (float[] acc : plays.values()) {
      boolean blue = acc[4] > 0;
      int id = ui.export(blue ? "troopDeployTimer_player" : "troopDeployTimer_enemy");
      if (id < 0) continue;
      ScScene.Clip clip = ui.clip(id);
      int endFrame = clip == null ? 299 : Math.max(0, clip.labelFrame("end"));
      float remainingS = Math.max(0f, (acc[3] - now) * 0.05f);
      int f = Math.max(0, Math.round(endFrame - remainingS * (clip == null ? 30 : clip.fps())));
      float x = p.x(view, acc[0] / acc[2]);
      float y = p.y(view, acc[1] / acc[2]);
      transform.setToTranslation(x, y);
      transform.scale(ppdu, -ppdu);
      tint.set(1, 1, 1, 1);
      art.draw(ui, id, f, transform, tint, HudArt.NONE);
    }
  }

  // ---------------------------------------------------------------- health bars

  private void drawHealthBars(
      ScScene ui, BattleFrame frame, ViewOrientation view, ArtProjection p) {
    // towers last (on top), the rest far to near
    List<EntityView> list = new ArrayList<>();
    for (EntityView e : frame.entities()) {
      if (!e.isCharacter() || !e.hasHitPoints()) continue;
      if (e.bar() == EntityView.Bar.NONE || e.deploying() || e.hidden()) continue;
      if (e.state() == 12 || e.state() == 13) continue; // carried by another unit
      list.add(e);
    }
    list.sort(
        (a, b) -> {
          int ta = a.kind() == EntityView.Kind.TOWER ? 1 : 0;
          int tb = b.kind() == EntityView.Kind.TOWER ? 1 : 0;
          if (ta != tb) return ta - tb;
          return Float.compare(p.y(view, b.y()), p.y(view, a.y()));
        });
    for (EntityView e : list) drawHealthBar(ui, e, view, p);
  }

  private void drawHealthBar(ScScene ui, EntityView e, ViewOrientation view, ArtProjection p) {
    boolean blue = view.blue(e.side());
    BarPlace place = place(e, blue);
    boolean tower = e.kind() == EntityView.Kind.TOWER;
    String barClass =
        place != null ? place.barClass() : tower ? (e.king() ? "Summoner" : "Tower") : "Medium";
    // a shield class shows the shield while it holds, then the plain bar of that size
    boolean shieldClass = barClass.startsWith("Shield") || barClass.startsWith("MediumShield");
    boolean shieldUp = shieldClass && e.shield() > 0 && e.maxShield() > 0;
    if (shieldClass && !shieldUp) {
      barClass =
          (barClass.toLowerCase(java.util.Locale.ROOT).contains("small") ? "Small" : "Medium")
              + (barClass.endsWith("_EV1") ? "_EV1" : "");
    }
    BarStyle style = barStyles.get(barClass);
    if (style == null) style = barStyles.get(tower ? "Tower" : "Medium");
    if (style == null) return;
    float share =
        e.bar() == EntityView.Bar.TIMER && e.lifetimeMs() > 0
            ? (float) e.lifeMs() / e.lifetimeMs()
            : shieldUp ? (float) e.shield() / e.maxShield() : e.healthShare();
    share = Math.max(0f, Math.min(1f, share));
    boolean damaged =
        e.bar() == EntityView.Bar.TIMER
            || e.hitPoints() < e.maxHitPoints()
            || (shieldUp && e.shield() < e.maxShield());
    boolean king = "Summoner".equals(barClass);
    String exportName;
    if (damaged || (tower && !king)) exportName = blue ? style.player() : style.enemy();
    else exportName = blue ? style.noDamagePlayer() : style.noDamageEnemy();
    int id = ui.export(exportName);
    if (id < 0) return;

    float ppdu = p.pixelsPerDisplayUnit();
    EntityView prev = before.get(e.id());
    float gx = prev == null ? e.x() : prev.x() + (e.x() - prev.x()) * alpha;
    float gy = prev == null ? e.y() : prev.y() + (e.y() - prev.y()) * alpha;
    float x = p.x(view, gx);
    float ground = p.y(view, gy);
    VisualLookup.Unit unit = lookup.unit(e.name(), blue);
    if (e.air() && unit != null) ground += p.height(unit.flyingHeight);
    float topDu = place != null ? place.topDu() : -60f;
    float offset = place != null ? (blue ? place.offsetBlue() : place.offsetRed()) : -10f;
    float barDu = topDu + offset;
    // the bar of a tower drawn under it stays on the tower's art (this arena's towers are
    // shorter than the ones the offsets were tuned for)
    if (tower && place != null) barDu = Math.min(barDu, place.bottomDu() - 12f);
    float barY = ground - barDu * ppdu;
    transform.setToTranslation(x, barY);
    transform.scale(ppdu, -ppdu);
    tint.set(1, 1, 1, 1);
    final int barFrame = Math.round((1f - share) * 99f);
    final boolean showBar = damaged || (tower && !king);
    final String level = e.level() > 0 ? Integer.toString(e.level()) : "";
    final String hp = Integer.toString(Math.max(0, e.hitPoints()));
    final boolean kingUndamaged = king && !damaged;
    art.draw(
        ui,
        id,
        king && damaged ? 61 : 0,
        transform,
        tint,
        new HudArt.Hook() {
          @Override
          public int child(String name, int childId, Affine2 local, int frame) {
            switch (name) {
              case "downgrade", "chef_upgrade", "chef_upgrade_shield", "ammo", "prestige", "buff":
                return HudArt.SKIP;
              case "bar":
                return showBar ? barFrame : HudArt.SKIP;
              case "hpNumber":
                if (!showBar && !tower) return HudArt.SKIP;
                // the player's numbers sit low in their clips: lift them onto the bar, a little
                // below its middle as the game draws them
                if (blue) local.m12 -= king ? KING_NUMBER_LIFT : PRINCESS_NUMBER_LIFT;
                return 0;
              default:
                // an undamaged king shows its crown badge alone
                if (kingUndamaged
                    && childId != KING_CROWN
                    && childId != KING_CROWN_SHAPE
                    && !name.equals("level")) {
                  return HudArt.SKIP;
                }
                return frame;
            }
          }

          @Override
          public void text(String name, int textId, Affine2 world, float alpha) {
            String value =
                switch (name) {
                  case "level" -> level;
                  case "txt" -> hp;
                  default -> null;
                };
            if (value != null) drawText(textId, value, world, alpha);
          }
        });
  }

  /** Where the bar of a unit row goes, read from its row and its art's bounds. */
  private BarPlace place(EntityView e, boolean blue) {
    String key = e.name() + (blue ? "|b" : "|r");
    return places.computeIfAbsent(key, k -> Optional.ofNullable(buildPlace(e, blue))).orElse(null);
  }

  private BarPlace buildPlace(EntityView e, boolean blue) {
    GameRow row = row("characters", e.name());
    if (row == null) row = row("buildings", e.name());
    String barClass = text(row, "HealthBar");
    boolean tower = e.kind() == EntityView.Kind.TOWER;
    if (barClass == null) barClass = tower ? (e.king() ? "Summoner" : "Tower") : "Medium";
    int base = integer(row, "HealthBarOffsetY", tower ? 0 : -10);
    float offBlue = integer(row, "HealthBarOffsetYBlue", base);
    float offRed = integer(row, "HealthBarOffsetYRed", base);
    float top = -60f;
    float bottom = 20f;
    VisualLookup.Unit unit = lookup.unit(e.name(), blue);
    if (unit != null) {
      float[] b = null;
      if (unit.isTowerLike() || tower) {
        b = merge(b, art.bounds(unit.scene, unit.baseExport, 0, HudArt.NONE));
        if (unit.topExport >= 0) {
          b = merge(b, art.bounds(unit.scene, unit.topExport, 0, HudArt.NONE));
        }
      } else {
        int id = unit.animation("idle", 5);
        if (id < 0) id = unit.anyExport;
        if (id >= 0) b = art.bounds(unit.scene, id, 0, HudArt.NONE);
      }
      if (b != null) {
        top = b[1] * unit.scale;
        bottom = b[3] * unit.scale;
      }
    }
    return new BarPlace(barClass, top, bottom, offBlue, offRed);
  }

  private static float[] merge(float[] a, float[] b) {
    if (a == null) return b;
    if (b == null) return a;
    return new float[] {
      Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[2], b[2]), Math.max(a[3], b[3])
    };
  }

  private void loadBarStyles() {
    if (stylesLoaded) return;
    stylesLoaded = true;
    Optional<Path> csv = cache.clientFile("csv_client/health_bars.csv");
    List<String[]> rows = new ArrayList<>();
    if (csv.isPresent()) {
      try {
        for (String line : Files.readAllLines(csv.get(), StandardCharsets.UTF_8)) {
          rows.add(splitCsv(line));
        }
      } catch (IOException e) {
        log.warn("Cannot read {}: {}", csv.get(), e.getMessage());
      }
    }
    if (rows.size() > 2) {
      String[] header = rows.get(0);
      for (String[] r : rows.subList(2, rows.size())) {
        String name = col(header, r, "Name");
        if (name == null || name.isEmpty()) continue;
        String seg = col(header, r, "SegmentsCount");
        barStyles.put(
            name,
            new BarStyle(
                col(header, r, "PlayerExportName"),
                col(header, r, "EnemyExportName"),
                col(header, r, "NoDamagePlayerExportName"),
                col(header, r, "NoDamageEnemyExportName"),
                seg == null || seg.isEmpty() ? 0 : Integer.parseInt(seg.trim()),
                col(header, r, "PlayerDivider"),
                col(header, r, "EnemyDivider")));
      }
    }
    // the client's own rows as of 16.635, should the table be missing
    barStyles.putIfAbsent(
        "Small",
        new BarStyle(
            "hp_player_small",
            "hp_enemy_small",
            "hp_player_number",
            "hp_enemy_number",
            0,
            null,
            null));
    barStyles.putIfAbsent(
        "Medium",
        new BarStyle(
            "hp_player_medium",
            "hp_enemy_medium",
            "hp_player_number",
            "hp_enemy_number",
            0,
            null,
            null));
    barStyles.putIfAbsent(
        "High",
        new BarStyle(
            "hp_player_high",
            "hp_enemy_high",
            "hp_player_number",
            "hp_enemy_number",
            0,
            null,
            null));
    barStyles.putIfAbsent(
        "Tower",
        new BarStyle(
            "hp_player_tower_withNumber",
            "hp_enemy_tower_withNumber",
            "hp_player_tower_withNumber",
            "hp_enemy_tower_withNumber",
            0,
            null,
            null));
    barStyles.putIfAbsent(
        "Summoner",
        new BarStyle(
            "hp_player_wizard",
            "hp_enemy_wizard",
            "hp_player_wizard",
            "hp_enemy_wizard",
            0,
            null,
            null));
  }

  private static String col(String[] header, String[] row, String name) {
    for (int i = 0; i < header.length; i++) {
      if (header[i].equals(name)) return i < row.length ? row[i] : null;
    }
    return null;
  }

  private static String[] splitCsv(String line) {
    List<String> out = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < line.length(); i++) {
      char ch = line.charAt(i);
      if (quoted) {
        if (ch == '"') {
          if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
            cur.append('"');
            i++;
          } else {
            quoted = false;
          }
        } else {
          cur.append(ch);
        }
      } else if (ch == '"') {
        quoted = true;
      } else if (ch == ',') {
        out.add(cur.toString());
        cur.setLength(0);
      } else {
        cur.append(ch);
      }
    }
    out.add(cur.toString());
    return out.toArray(new String[0]);
  }

  // ---------------------------------------------------------------- hand panels

  /** A card's icon: the scene and export its spell row's IconFile names. */
  private record CardArt(ScScene scene, int export, String rarity) {}

  // hand layout in the replay panel's display units (bottom panel; the top one mirrors y)
  private static final float CARDS_LEFT = -214.3f;
  private static final float CARDS_RIGHT = 90.9f;
  private static final float CARDS_TOP = -138.2f;
  private static final float CARDS_BOTTOM = -47.0f;
  private static final float NEXT_X = -253.8f;
  // the next card sits above the "Next:" label, clear of it with its elixir drop
  private static final float NEXT_Y = -110f;
  private static final float CARD_SCALE = 0.6f;
  private static final float NEXT_SCALE = 0.32f;
  private static final float ICON_SCALE = 0.49f;
  private static final float ELIXIR_BAR_DU = 500f;

  /**
   * The replay panel's colour on screen, as multipliers of its clip's own (the game recolours the
   * panel per team at run time): the player's blue (52, 96, 164) and the opponent's red (148, 65,
   * 54), as the game draws them.
   */
  private static final float[] PANEL_BLUE = {0.51f, 0.627f, 0.854f};

  private static final float[] PANEL_RED = {0.66f, 0.605f, 0.66f};

  /** Depth of the light bevel along the panel's edge facing the arena, in display units. */
  private static final float PANEL_BEVEL_DU = 2.5f;

  /** Opacity of the elixir pip being filled, over the bar's dark back. */
  private static final float PIP_FILLING_ALPHA = 0.45f;

  /** How far an unaffordable card is turned grey (the client's Grayscale button state). */
  private static final float UNAFFORDABLE_GRAY = 1f;

  private void drawPanel(
      ScScene ui,
      BattleFrame frame,
      ViewOrientation view,
      int width,
      float anchorY,
      float s,
      boolean top) {
    int side = top ? view.topSide() : view.bottomSide();
    BattleFrame.SideView sv = null;
    for (BattleFrame.SideView v : frame.sides()) if (v.side() == side) sv = v;
    if (sv == null) return;
    int id = ui.export(top ? "Replay_HUD_enermy_topMiddle" : "Replay_HUD_player_bottomMiddle");
    if (id < 0) return;
    Affine2 panel = new Affine2().setToTranslation(width / 2f, anchorY);
    panel.scale(s, -s);
    float fill = Math.max(0f, Math.min(1f, sv.elixir() / 100000f));
    int wholeElixir = Math.max(0, Math.min(10, sv.wholeElixir()));
    float wholeFill = wholeElixir / 10f;
    String whole = Integer.toString(sv.wholeElixir());
    // the panel itself: its clip's own lighter colour first, showing as the bevel along the edge
    // facing the arena, then the team's colour over it, short of that edge, with the shading
    // along the panel's edges
    tint.set(1, 1, 1, 1);
    art.draw(
        ui,
        id,
        0,
        panel,
        tint,
        new HudArt.Hook() {
          @Override
          public int child(String name, int childId, Affine2 local, int frame) {
            return name.equals("panel") ? frame : HudArt.SKIP;
          }
        });
    float[] shade = view.blue(side) ? PANEL_BLUE : PANEL_RED;
    tint.set(shade[0], shade[1], shade[2], 1);
    float bevel = top ? -PANEL_BEVEL_DU : PANEL_BEVEL_DU;
    art.draw(
        ui,
        id,
        0,
        panel,
        tint,
        new HudArt.Hook() {
          @Override
          public int child(String name, int childId, Affine2 local, int frame) {
            boolean banner =
                childId == 1172 || childId == 1173 || childId == 1185 || childId == 1186;
            // the panel's front layer: the "panel" that holds a "panel" of its own (the
            // innermost one is the shape's holder)
            if (name.equals("panel")
                && java.util.Arrays.asList(namesOf(ui, childId)).contains("panel")) {
              local.m12 += bevel;
            }
            return !banner
                    && (name.isEmpty()
                        || name.equals("panel")
                        || name.equals("panel_back")
                        || name.equals("darken"))
                ? frame
                : HudArt.SKIP;
          }
        });
    tint.set(1, 1, 1, 1);
    // the elixir bar: whole pips bright (the bar's body runs to the last whole one), then the
    // pip being filled dimmer
    art.draw(
        ui,
        id,
        0,
        panel,
        tint,
        new HudArt.Hook() {
          @Override
          public int child(String name, int childId, Affine2 local, int frame) {
            switch (name) {
              case "panel",
              "panel_back",
              "darken",
              "ghost",
              "elixirRequirementBar",
              "bar_tip",
              "bar_flow",
              "bar_refund",
              "elixir_warning",
              "cardArea",
              "clan_icon",
              "nextCard1",
              "player",
              "clan":
                return HudArt.SKIP;
              case "bar_body":
                if (wholeElixir == 0) return HudArt.SKIP;
                local.m00 = ELIXIR_BAR_DU * wholeFill / 18f;
                local.m02 = -1.5f;
                return frame;
              case "bar_end":
                if (wholeElixir == 0) return HudArt.SKIP;
                local.m02 = -1.5f + ELIXIR_BAR_DU * wholeFill - 0.7f;
                return frame;
              default:
                if (name.length() == 2
                    && name.charAt(0) == 'd'
                    && Character.isDigit(name.charAt(1))) {
                  local.m02 = -1.5f + ELIXIR_BAR_DU * (name.charAt(1) - '0') / 10f;
                  return frame;
                }
                // the name banner beside the hand
                if (childId == 1172 || childId == 1173 || childId == 1185 || childId == 1186) {
                  return HudArt.SKIP;
                }
                return frame;
            }
          }

          @Override
          public void text(String name, int textId, Affine2 world, float alpha) {
            switch (name) {
              case "txt" -> drawText(textId, whole, world, alpha);
              case "TID_NEXT_CARD" -> drawText(textId, "Next:", world, alpha);
              default -> {}
            }
          }
        });
    float filling = fill - wholeFill;
    if (filling > 0.002f) {
      tint.set(1, 1, 1, PIP_FILLING_ALPHA);
      art.draw(
          ui,
          id,
          0,
          panel,
          tint,
          new HudArt.Hook() {
            /** Whether the walk is inside the bar's body, whose shape is unnamed. */
            private boolean inBody;

            @Override
            public int child(String name, int childId, Affine2 local, int frame) {
              if (name.isEmpty()) return inBody ? frame : HudArt.SKIP;
              inBody = name.equals("bar_body");
              switch (name) {
                case "elixir_bar":
                  return frame;
                case "bar_body":
                  local.m00 = ELIXIR_BAR_DU * filling / 18f;
                  local.m02 = -1.5f + ELIXIR_BAR_DU * wholeFill;
                  return frame;
                default:
                  return HudArt.SKIP;
              }
            }
          });
      tint.set(1, 1, 1, 1);
    }

    // the four cards and the next one, each with its elixir drop
    float cardsTop = top ? -CARDS_BOTTOM : CARDS_TOP;
    float cardsBottom = top ? -CARDS_TOP : CARDS_BOTTOM;
    float cy = (cardsTop + cardsBottom) / 2f;
    float slot = (CARDS_RIGHT - CARDS_LEFT) / 4f;
    for (int i = 0; i < sv.hand().size() && i < 4; i++) {
      BattleFrame.CardView card = sv.hand().get(i);
      if (card == null) continue;
      float cx = CARDS_LEFT + slot * (i + 0.5f);
      boolean affordable = card.cost() <= sv.wholeElixir();
      drawCard(ui, card, panel, cx, cy - 4f, CARD_SCALE, affordable);
    }
    if (sv.next() != null) {
      float ny = top ? -NEXT_Y - 14f : NEXT_Y;
      boolean affordable = sv.next().cost() <= sv.wholeElixir();
      drawCard(ui, sv.next(), panel, NEXT_X, ny, NEXT_SCALE, affordable);
    }
  }

  /**
   * One card of a hand at ({@code cx}, {@code cy}) in the panel's units: its frame, its icon and
   * its elixir drop, turned grey while the player cannot afford it (as the game shows it).
   */
  private void drawCard(
      ScScene ui,
      BattleFrame.CardView card,
      Affine2 panel,
      float cx,
      float cy,
      float scale,
      boolean affordable) {
    int id = ui.export("spell_card_ingame");
    if (id < 0) return;
    CardArt icon = cardArt(card.name());
    Affine2 t = new Affine2(panel).translate(cx, cy).scale(scale, scale);
    tint.set(1, 1, 1, 1);
    art.setGray(affordable ? 0f : UNAFFORDABLE_GRAY);
    try {
      drawCardLayers(ui, id, icon, card, t);
    } finally {
      art.setGray(0f);
      tint.set(1, 1, 1, 1);
    }
  }

  private void drawCardLayers(
      ScScene ui, int id, CardArt icon, BattleFrame.CardView card, Affine2 t) {
    boolean legendary = icon != null && "Legendary".equalsIgnoreCase(icon.rarity());
    final Affine2[] imageLocal = {null};
    // pass 1: the card's back and, in its image's place, the card's icon
    art.draw(
        ui,
        id,
        0,
        t,
        tint,
        new HudArt.Hook() {
          @Override
          public int child(String name, int childId, Affine2 local, int frame) {
            if (name.equals("card_image")) {
              imageLocal[0] = new Affine2(local);
              return icon == null ? frame : HudArt.SKIP;
            }
            return name.equals("image_back") ? frame : HudArt.SKIP;
          }
        });
    if (icon != null && imageLocal[0] != null) {
      Affine2 it = new Affine2(t).mul(imageLocal[0]).scale(ICON_SCALE, ICON_SCALE);
      art.draw(icon.scene(), icon.export(), 0, it, tint, HudArt.NONE);
    }
    // pass 2: the frame and the elixir drop
    art.draw(
        ui,
        id,
        0,
        t,
        tint,
        new HudArt.Hook() {
          @Override
          public int child(String name, int childId, Affine2 local, int frame) {
            return switch (name) {
              case "frame" -> legendary ? HudArt.SKIP : 0;
              case "frame_legendary" -> legendary ? 0 : HudArt.SKIP;
              case "elixar_count", "card_txt_bg", "count", "" -> frame;
              default -> HudArt.SKIP;
            };
          }

          @Override
          public void text(String name, int textId, Affine2 world, float alpha) {
            if (name.equals("count")) drawText(textId, Integer.toString(card.cost()), world, alpha);
          }
        });
    if (icon == null) {
      // no icon art in the cache: the card's name across it
      float cx0 = t.m02 - 55f * t.m00;
      float w = 110f * t.m00;
      float sz = Math.abs(t.m11);
      art.straightAlpha();
      fonts.draw(
          art.batch(),
          shortName(card.name()),
          cx0,
          t.m12 - 15f * sz,
          w,
          30f * sz,
          15f * sz,
          0xffffffff,
          0xff000000,
          Align.center,
          1f);
    }
  }

  /** A card row name split into words and cut to fit a card ("MiniPekka" to "Mini Pekka"). */
  private static String shortName(String name) {
    String spaced = name.replaceAll("([a-z])([A-Z])", "$1 $2");
    return spaced.length() > 12 ? spaced.substring(0, 11) + "." : spaced;
  }

  private CardArt cardArt(String name) {
    return cardArts
        .computeIfAbsent(name, k -> Optional.ofNullable(buildCardArt(name)))
        .orElse(null);
  }

  private CardArt buildCardArt(String name) {
    GameRow row = null;
    for (String table :
        new String[] {
          "spells_characters",
          "spells_buildings",
          "spells_other",
          "spells_evolved",
          "spells_hero_form"
        }) {
      row = row(table, name);
      if (row != null) break;
    }
    if (row == null) {
      return null;
    }
    String icon = text(row, "IconFile");
    if (icon == null) return null;
    String rarity = text(row, "Rarity");
    String swf = text(row, "IconSWF");
    for (String file : new String[] {swf, "sc/ui_spells.sc"}) {
      if (file == null) continue;
      ScScene scene = cache.scene(file);
      if (scene == null) continue;
      for (String candidate :
          new String[] {
            icon,
            icon.replaceAll("_card$", ""),
            icon.replaceAll("(_\\d+)?_card$", ""),
            icon.replaceAll("_card$", "s")
          }) {
        int id = scene.export(candidate);
        if (id >= 0) return new CardArt(scene, id, rarity);
      }
    }
    return null;
  }

  /** The child names of clip {@code id} of {@code scene}, empty when it has none. */
  private String[] namesOf(ScScene scene, int id) {
    String[] n = art.names(scene, id);
    return n == null ? new String[0] : n;
  }

  // ---------------------------------------------------------------- timer and crowns

  private void drawTimer(ScScene ui, BattleFrame frame, int width, float topY, float s) {
    int id = ui.export("HUD_topRight_replay");
    if (id < 0) return;
    int leftMs;
    if (frame.overtime()) {
      leftMs = Math.max(0, REGULAR_MS + OVERTIME_MS - frame.timeMs());
    } else {
      leftMs = Math.max(0, REGULAR_MS - frame.timeMs());
    }
    int secs = (leftMs + 999) / 1000;
    String time = (secs / 60) + ":" + String.format("%02d", secs % 60);
    boolean overtime = frame.overtime();
    String label = overtime ? "Overtime" : "Time left:";
    String rate = frame.elixirRate() > 1 ? "x" + frame.elixirRate() : null;
    Affine2 t = new Affine2().setToTranslation(width / 2f, topY);
    t.scale(s, -s);
    tint.set(1, 1, 1, 1);
    art.draw(
        ui,
        id,
        0,
        t,
        tint,
        new HudArt.Hook() {
          @Override
          public int child(String name, int childId, Affine2 local, int frame) {
            if (name.equals("elixirRegen")) return rate == null ? HudArt.SKIP : 39;
            return frame;
          }

          @Override
          public void text(String name, int textId, Affine2 world, float alpha) {
            switch (name) {
              case "txt" -> {
                if (textId == 1365) drawText(textId, time, world, alpha);
                else if (textId == 1367 && rate != null) drawText(textId, rate, world, alpha);
              }
              case "TID_TIME_LEFT_vcenter" ->
                  drawText(overtime ? 1366 : textId, label, world, alpha);
              default -> {}
            }
          }
        });
  }

  private void drawCrowns(
      ScScene ui, BattleFrame frame, ViewOrientation view, ArtProjection p, int width, float s) {
    int id = ui.export("HUD_rightMiddle");
    if (id < 0) return;
    int blueCrowns = 0;
    int redCrowns = 0;
    for (BattleFrame.SideView v : frame.sides()) {
      if (view.blue(v.side())) blueCrowns = v.crowns();
      else redCrowns = v.crowns();
    }
    final String blue = Integer.toString(blueCrowns);
    final String red = Integer.toString(redCrowns);
    float river = p.y(view, view.heightUnits() / 2f);
    Affine2 t = new Affine2().setToTranslation(width, river);
    t.scale(s, -s);
    tint.set(1, 1, 1, 1);
    art.draw(
        ui,
        id,
        0,
        t,
        tint,
        new HudArt.Hook() {
          @Override
          public int child(String name, int childId, Affine2 local, int frame) {
            if (name.startsWith("cheer_button")) return HudArt.SKIP;
            return frame;
          }

          @Override
          public void text(String name, int textId, Affine2 world, float alpha) {
            if (textId == 1363) drawText(textId, red, world, alpha);
            else if (textId == 1364) drawText(textId, blue, world, alpha);
          }
        });
  }

  // ---------------------------------------------------------------- text

  /** Draws {@code value} in the box of text field {@code textId}, {@code world} its transform. */
  private void drawText(int textId, String value, Affine2 world, float alpha) {
    TextField f = TEXT.get(textId);
    if (f == null || value == null || value.isEmpty() || alpha <= 0.01f) return;
    float x0 = world.m00 * f.left() + world.m01 * f.top() + world.m02;
    float y0 = world.m10 * f.left() + world.m11 * f.top() + world.m12;
    float x1 = world.m00 * f.right() + world.m01 * f.bottom() + world.m02;
    float y1 = world.m10 * f.right() + world.m11 * f.bottom() + world.m12;
    float sy = (float) Math.sqrt(world.m01 * world.m01 + world.m11 * world.m11);
    float px = f.size() * sy;
    if (px < 4f) return;
    art.straightAlpha();
    fonts.draw(
        art.batch(),
        value,
        Math.min(x0, x1),
        Math.min(y0, y1),
        Math.abs(x1 - x0),
        Math.abs(y1 - y0),
        px,
        f.color(),
        f.outline(),
        f.align(),
        alpha);
  }

  // ---------------------------------------------------------------- rows

  private GameRow row(String table, String name) {
    if (tables == null || name == null) return null;
    try {
      var t = tables.table(table);
      return t != null && t.has(name) ? t.row(name) : null;
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static String text(GameRow row, String column) {
    if (row == null) return null;
    JsonNode v = row.columns().get(column);
    return v == null || v.isNull() || v.asText().isEmpty() ? null : v.asText();
  }

  private static int integer(GameRow row, String column, int fallback) {
    if (row == null) return fallback;
    JsonNode v = row.columns().get(column);
    return v == null || !v.canConvertToInt() ? fallback : v.asInt();
  }

  @Override
  public void dispose() {
    fonts.dispose();
    art.dispose();
  }
}
