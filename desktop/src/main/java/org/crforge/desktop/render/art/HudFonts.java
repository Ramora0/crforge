/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.render.art;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator.FreeTypeFontParameter;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Disposable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * The HUD's text in the game's own font (Supercell-Magic, from the user's game files; never part of
 * this repository), with the dark outline and drop the game draws under it. Falls back to the
 * repository's font when the game's is missing. Fonts are baked per pixel size and colours.
 */
@Slf4j
final class HudFonts implements Disposable {

  /** System property naming the game font file (a .ttf). */
  static final String PROPERTY = "crforge.hudFont";

  private static final String[] CANDIDATES = {
    System.getProperty("user.home")
        + "/coding/python/ai/crai/game_assets/pack/assets/font/supercell-magic_0.ttf",
    System.getProperty("user.home") + "/.crforge/fonts/supercell-magic_0.ttf",
  };

  /** Gap between a top-aligned field's top and its capitals, in font sizes. */
  private static final float TOP_INSET = 0.1f;

  private static final String CHARS = FreeTypeFontGenerator.DEFAULT_CHARS + "×";

  private FreeTypeFontGenerator generator;
  private boolean gameFont;
  private final Map<String, BitmapFont> fonts = new HashMap<>();
  private final GlyphLayout layout = new GlyphLayout();

  private FreeTypeFontGenerator generator() {
    if (generator != null) return generator;
    String configured = System.getProperty(PROPERTY);
    for (String candidate : configured != null ? new String[] {configured} : CANDIDATES) {
      Path p = Paths.get(candidate);
      if (Files.isRegularFile(p)) {
        try {
          generator = new FreeTypeFontGenerator(new FileHandle(p.toFile()));
          gameFont = true;
          log.info("HUD font: {}", p);
          return generator;
        } catch (RuntimeException e) {
          log.warn("Cannot load the HUD font {}: {}", p, e.getMessage());
        }
      }
    }
    generator = new FreeTypeFontGenerator(Gdx.files.classpath("fonts/RobotoMono-Regular.ttf"));
    log.info("HUD font: the game's font is missing, using RobotoMono");
    return generator;
  }

  /**
   * A font {@code px} pixels high, drawn in {@code color} with an {@code outline} border (alpha 0
   * for none).
   */
  BitmapFont font(int px, int color, int outline) {
    px = Math.max(6, Math.min(px, 160));
    String key = px + "|" + Integer.toHexString(color) + "|" + Integer.toHexString(outline);
    BitmapFont f = fonts.get(key);
    if (f != null) return f;
    FreeTypeFontParameter p = new FreeTypeFontParameter();
    p.size = px;
    p.characters = CHARS;
    p.color = argb(color);
    p.minFilter = TextureFilter.Linear;
    p.magFilter = TextureFilter.Linear;
    p.kerning = true;
    if ((outline >>> 24) != 0) {
      Color oc = argb(outline);
      p.borderColor = oc;
      p.borderWidth = Math.max(1f, px * (gameFont ? 0.09f : 0.08f));
      p.borderStraight = false;
      p.shadowColor = new Color(oc.r * 0.6f, oc.g * 0.6f, oc.b * 0.6f, oc.a);
      p.shadowOffsetY = Math.max(1, Math.round(px * 0.08f));
      p.shadowOffsetX = 0;
    }
    f = generator().generateFont(p);
    f.setUseIntegerPositions(false);
    fonts.put(key, f);
    return f;
  }

  /** An ARGB int (as the SC text fields store colours) as a colour. */
  static Color argb(int argb) {
    return new Color(
        ((argb >> 16) & 0xFF) / 255f,
        ((argb >> 8) & 0xFF) / 255f,
        (argb & 0xFF) / 255f,
        ((argb >>> 24) & 0xFF) / 255f);
  }

  /**
   * Draws {@code text} in the box (world pixels, y up: left, bottom, width, height), horizontally
   * aligned by {@code align} ({@link Align#left}, {@link Align#center} or {@link Align#right}) and
   * centred vertically, or hung from the box's top when {@code align} has {@link Align#top}.
   */
  void draw(
      Batch batch,
      String text,
      float left,
      float bottom,
      float width,
      float height,
      float px,
      int color,
      int outline,
      int align,
      float alpha) {
    if (text == null || text.isEmpty()) return;
    BitmapFont f = font(Math.round(px), color, outline);
    f.setColor(1, 1, 1, alpha);
    layout.setText(f, text);
    float x = left;
    if ((align & Align.center) != 0 && (align & (Align.left | Align.right)) == 0) {
      x = left + (width - layout.width) / 2f;
    } else if ((align & Align.right) != 0) {
      x = left + width - layout.width;
    }
    // BitmapFont draws from the top of the capitals: a top-aligned field (as the game sets the
    // fields it does not centre) hangs them from the box's top, else they are centred in it
    float capHeight = f.getCapHeight();
    float y =
        (align & Align.top) != 0
            ? bottom + height - px * TOP_INSET
            : bottom + height / 2f + capHeight / 2f;
    f.draw(batch, text, x, y);
  }

  @Override
  public void dispose() {
    fonts.values().forEach(BitmapFont::dispose);
    fonts.clear();
    if (generator != null) generator.dispose();
    generator = null;
  }
}
