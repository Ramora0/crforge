/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.render.art;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.utils.BufferUtils;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.crforge.core.battle.data.GameTables;
import org.crforge.desktop.battle.BattleAdapter;
import org.crforge.desktop.battle.BattleFrame;
import org.crforge.desktop.battle.BattleSession;
import org.crforge.desktop.render.ViewOrientation;
import org.crforge.desktop.replay.ReplayFile;
import org.crforge.desktop.replay.ReplayPlayer;

/**
 * Plays a replay with the game's art into an offscreen frame and writes it as a video (through
 * ffmpeg) or, for a {@code .png} target, as one still. Each battle tick (50 ms) is drawn as {@code
 * fps / 20} frames interpolated between the tick before and the tick.
 */
@Slf4j
public final class CinematicRecorder extends ApplicationAdapter {

  /** What to record. */
  public record Settings(
      Path out, int width, int height, int fps, float fromSeconds, float seconds, int bottomSide) {}

  private static final int TICKS_PER_SECOND = 20;

  private final ReplayFile replay;
  private final GameTables tables;
  private final Settings settings;

  private ArtCache cache;
  private ArenaScene arena;
  private RealisticRenderer renderer;
  private BattleHud hud;
  private EffectLayer effects;
  private BattleSounds battleSounds;
  private MatchEnd matchEnd;
  private float overtimeAt = -1;
  private int startLeftMs = -1;
  private FrameBuffer fbo;
  private OrthographicCamera camera;
  private ReplayPlayer player;
  private ViewOrientation view;
  private ArtProjection proj;
  private Process ffmpeg;
  private OutputStream video;
  private ByteBuffer pixels;
  private byte[] row;
  private BattleFrame previous;
  private BattleFrame current;
  private int subFrame;
  private int framesWritten;
  private int framesWanted;

  /** Ticks drawn after the battle's last one (4.5 s: the end screen plays in them). */
  private static final int OUTRO_TICKS = 90;

  private int outroTicks;
  private final java.util.List<String[]> sounds = new java.util.ArrayList<>();
  private float soundOrigin;
  private boolean still;

  public CinematicRecorder(ReplayFile replay, GameTables tables, Settings settings) {
    this.replay = replay;
    this.tables = tables;
    this.settings = settings;
  }

  @Override
  public void create() {
    cache =
        ArtCache.locate()
            .orElseThrow(
                () -> new IllegalStateException("no game art cache: run tools/game-art first"));
    renderer = new RealisticRenderer(cache);
    int w = settings.width();
    int h = settings.height();
    fbo = new FrameBuffer(Pixmap.Format.RGBA8888, w, h, false);
    camera = new OrthographicCamera(w, h);
    camera.setToOrtho(false, w, h);
    player = new ReplayPlayer(replay, tables);
    BattleSession session = player.getSession();
    if (session == null) throw new IllegalStateException("the replay cannot be played");
    var tileMap = session.getBattle().getWorld().getTileMap();
    view = ViewOrientation.of(tileMap, settings.bottomSide());
    // as the game frames it on a phone: the arena fills the width between the HUD bars at the
    // top (13.5% of the height) and the bottom (12.5%)
    float pixelsPerTile = w / 18.6f;
    float arenaHeight =
        tileMap.heightUnits()
            / 1000f
            * pixelsPerTile
            * ArtProjection.DU_PER_TILE_Y
            / ArtProjection.DU_PER_TILE_X;
    float originX = (w - tileMap.widthUnits() / 1000f * pixelsPerTile) / 2f;
    float bottomHud = h * 0.125f;
    float topHud = h * 0.135f;
    float originY = bottomHud + (h - topHud - bottomHud - arenaHeight) / 2f;
    proj =
        new ArtProjection(
            pixelsPerTile, originX, originY, tileMap.widthUnits(), tileMap.heightUnits());
    arena = new ArenaScene(cache, tables, replay);
    int[] sh = arena.shadow();
    renderer.setShadowColor(sh[0], sh[1], sh[2], sh[3]);
    hud = new BattleHud(cache, renderer.drawer());
    hud.setTables(tables);
    effects = new EffectLayer(cache);
    renderer.setEffects(effects);
    // every hit and every short-lived area effect (a Zap lives 1 ms), as the battle reports them
    session.getBattle().getWorld().addObserver(effects.observer());
    session.getBattle().getWorld().addObserver(renderer.observer());
    battleSounds = new BattleSounds(cache, tables, view, arena.locationName());
    session.getBattle().getWorld().addObserver(battleSounds);

    // fast-forward to the start
    int startTick = Math.round(settings.fromSeconds() * TICKS_PER_SECOND);
    while (session.tick() < startTick && player.step()) {
      player.drainAreaHits();
    }
    matchEnd = new MatchEnd(cache);
    current = matchEnd.update(BattleAdapter.frame(player.getSession(), view::sideName), view);
    renderer.setTickData(
        BattleAdapter.attackTimers(player.getSession()),
        BattleAdapter.projectileFlights(player.getSession()));
    effects.update(current, null, tables, view);
    soundOrigin = current.tick() * 0.05f;
    sounds.addAll(effects.drainSounds());
    battleSounds.drain(); // what the fast-forward heard
    matchEnd.drainSounds();
    if (settings.fromSeconds() <= 0.01f) battleSounds.matchStart(arena.locationName(), soundOrigin);
    startLeftMs = current.overtime() ? -1 : Math.max(0, 180_000 - current.timeMs());
    previous = current;
    still = settings.out().toString().toLowerCase().endsWith(".png");
    framesWanted = still ? 1 : Math.round(settings.seconds() * settings.fps());
    pixels = BufferUtils.newByteBuffer(w * h * 4);
    row = new byte[w * 4];
    if (!still) startEncoder();
    log.info(
        "Recording {} frame(s) {}x{} at {} fps from {}s to {}",
        framesWanted,
        w,
        h,
        settings.fps(),
        settings.fromSeconds(),
        settings.out());
  }

  private void startEncoder() {
    List<String> cmd = new ArrayList<>();
    cmd.addAll(
        List.of(
            "ffmpeg",
            "-loglevel",
            "error",
            "-y",
            "-f",
            "rawvideo",
            "-pix_fmt",
            "rgba",
            "-s",
            settings.width() + "x" + settings.height(),
            "-r",
            Integer.toString(settings.fps()),
            "-i",
            "-",
            "-c:v",
            "libx264",
            "-preset",
            "medium",
            "-crf",
            "17",
            "-pix_fmt",
            "yuv420p",
            "-movflags",
            "+faststart",
            settings.out().toString()));
    try {
      ffmpeg =
          new ProcessBuilder(cmd)
              .redirectErrorStream(true)
              .redirectOutput(ProcessBuilder.Redirect.INHERIT)
              .start();
      video = ffmpeg.getOutputStream();
    } catch (IOException e) {
      throw new IllegalStateException("cannot start ffmpeg", e);
    }
  }

  @Override
  public void render() {
    int perTick = Math.max(1, settings.fps() / TICKS_PER_SECOND);
    for (int batchFrames = 0; batchFrames < 8 && framesWritten < framesWanted; batchFrames++) {
      if (!still && subFrame == 0 && framesWritten > 0) {
        previous = current;
        if (outroTicks > 0 || !player.step()) {
          // the battle is over: hold its last frame for a short outro, the clock running on so
          // animations and effects (a falling king tower) play out
          if (outroTicks >= OUTRO_TICKS) {
            framesWanted = framesWritten;
            break;
          }
          outroTicks++;
          matchEnd.stopped();
          current = matchEnd.update(later(current), view);
        } else {
          current = matchEnd.update(BattleAdapter.frame(player.getSession(), view::sideName), view);
        }
        renderer.setTickData(
            BattleAdapter.attackTimers(player.getSession()),
            BattleAdapter.projectileFlights(player.getSession()));
        effects.update(current, previous, tables, view);
        sounds.addAll(effects.drainSounds());
        sounds.addAll(battleSounds.drain());
        sounds.addAll(matchEnd.drainSounds());
        if (current.overtime() && overtimeAt < 0) overtimeAt = current.tick() * 0.05f;
      }
      float alpha = still ? 1f : (subFrame + 1f) / perTick;
      drawFrame(alpha);
      subFrame = (subFrame + 1) % perTick;
      framesWritten++;
      if (framesWritten % (settings.fps() * 5) == 0) {
        log.info("{} / {} frames (tick {})", framesWritten, framesWanted, current.tick());
      }
    }
    if (framesWritten >= framesWanted) finish();
  }

  /** The frame one tick later with nothing changed: the outro's clock. */
  private static BattleFrame later(BattleFrame f) {
    return new BattleFrame(
        f.tick() + 1,
        f.timeMs() + 50,
        f.overtime(),
        f.elixirRate(),
        f.entities(),
        f.sides(),
        f.ended(),
        f.winner(),
        f.over(),
        f.halted(),
        f.messages());
  }

  private void drawFrame(float alpha) {
    fbo.begin();
    Gdx.gl.glClearColor(0.11f, 0.13f, 0.12f, 1f);
    Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
    Gdx.gl.glEnable(GL20.GL_BLEND);
    float seconds = (previous.timeMs() + (current.timeMs() - previous.timeMs()) * alpha) / 1000f;
    // the effects' screen shake moves the arena, not the HUD
    float[] shake = effects.shake(seconds);
    float ppdu = proj.pixelsPerDisplayUnit();
    float dx = shake == null ? 0 : shake[0] * ppdu;
    float dy = shake == null ? 0 : shake[1] * ppdu;
    camera.position.set(settings.width() / 2f - dx, settings.height() / 2f - dy, 0);
    camera.update();
    renderer.drawer().batch().setProjectionMatrix(camera.combined);
    renderer.setEffectClock(seconds);
    arena.drawGround(renderer.drawer(), proj, view, current, previous, alpha);
    renderer.drawer().begin();
    effects.drawBelow(renderer.drawer(), proj, view, seconds);
    renderer.drawer().end();
    renderer.render(current, previous, alpha, tables, view, proj, camera.combined);
    renderer.drawer().begin();
    effects.drawAbove(renderer.drawer(), proj, view, seconds);
    renderer.drawer().end();
    arena.drawOverlay(renderer.drawer(), proj, view, current, alpha);
    camera.position.set(settings.width() / 2f, settings.height() / 2f, 0);
    camera.update();
    hud.draw(
        matchEnd.hud(current),
        matchEnd.hud(previous),
        alpha,
        view,
        proj,
        settings.width(),
        settings.height(),
        camera.combined);
    float tickSeconds = (previous.tick() + (current.tick() - previous.tick()) * alpha) * 0.05f;
    matchEnd.draw(
        tickSeconds, settings.width(), settings.height(), renderer.drawer(), camera.combined);
    pixels.clear();
    Gdx.gl.glPixelStorei(GL20.GL_PACK_ALIGNMENT, 1);
    Gdx.gl.glReadPixels(
        0, 0, settings.width(), settings.height(), GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, pixels);
    fbo.end();
    if (still) {
      writeStill();
    } else {
      writeVideoFrame();
    }
  }

  private void writeVideoFrame() {
    int w = settings.width();
    int h = settings.height();
    try {
      // GL rows are bottom-up
      for (int y = h - 1; y >= 0; y--) {
        pixels.position(y * w * 4);
        pixels.get(row, 0, w * 4);
        for (int i = 3; i < row.length; i += 4) row[i] = (byte) 255;
        video.write(row);
      }
    } catch (IOException e) {
      throw new IllegalStateException("ffmpeg stopped", e);
    }
  }

  private void writeStill() {
    int w = settings.width();
    int h = settings.height();
    Pixmap pm = new Pixmap(w, h, Pixmap.Format.RGBA8888);
    ByteBuffer dst = pm.getPixels();
    for (int y = 0; y < h; y++) {
      pixels.position((h - 1 - y) * w * 4);
      pixels.get(row, 0, w * 4);
      for (int i = 3; i < row.length; i += 4) row[i] = (byte) 255;
      dst.position(y * w * 4);
      dst.put(row);
    }
    dst.rewind();
    PixmapIO.writePNG(Gdx.files.absolute(settings.out().toAbsolutePath().toString()), pm);
    pm.dispose();
  }

  private void finish() {
    if (video != null) {
      try {
        video.close();
        ffmpeg.waitFor();
      } catch (IOException | InterruptedException e) {
        log.warn("ffmpeg: {}", e.getMessage());
      }
      video = null;
    }
    if (!still) writeSounds();
    log.info("Wrote {} frame(s) to {}", framesWritten, settings.out());
    Gdx.app.exit();
  }

  /**
   * The sounds the effects started, one JSON line each ({@code {"t": seconds into the video,
   * "sound": "soundbanks/<bank>/<event>"}}), beside the video, for {@code crforge-art audio} to
   * mix.
   */
  private void writeSounds() {
    java.nio.file.Path out =
        settings.out().resolveSibling(settings.out().getFileName() + ".sounds.jsonl");
    StringBuilder sb = new StringBuilder();
    // the battle clock, for the mixer's timed sounds and music (warnings, countdown, overtime)
    if (startLeftMs >= 0) {
      sb.append(
          String.format(
              java.util.Locale.ROOT, "{\"t\": 0.000, \"clock\": %.3f}%n", startLeftMs / 1000f));
    }
    if (matchEnd.ended()) {
      // the match's end: the mixer stops the battle music and the clock's sounds here
      sb.append(
          String.format(
              java.util.Locale.ROOT,
              "{\"t\": %.3f, \"end\": true}%n",
              Math.max(0f, matchEnd.endSeconds() - soundOrigin)));
    }
    if (overtimeAt >= 0) {
      sb.append(
          String.format(
              java.util.Locale.ROOT,
              "{\"t\": %.3f, \"overtime\": true}%n",
              overtimeAt - soundOrigin));
    }
    for (String[] s : sounds) {
      float t = Float.parseFloat(s[0]) - soundOrigin;
      if (t < 0 || s[1] == null || s[1].isEmpty()) continue;
      sb.append("{\"t\": ")
          .append(String.format(java.util.Locale.ROOT, "%.3f", t))
          .append(", \"sound\": \"")
          .append(s[1].replace("\"", ""))
          .append("\"}\n");
    }
    try {
      java.nio.file.Files.writeString(out, sb.toString());
      log.info("Wrote {} sound events to {}", sounds.size(), out);
    } catch (IOException e) {
      log.warn("Cannot write {}: {}", out, e.getMessage());
    }
  }

  @Override
  public void dispose() {
    if (effects != null) effects.dispose();
    if (matchEnd != null) matchEnd.dispose();
    if (fbo != null) fbo.dispose();
    if (renderer != null) renderer.dispose();
    if (cache != null) cache.dispose();
    if (hud != null) hud.dispose();
  }
}
