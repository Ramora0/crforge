/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.render.art;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.PolygonSpriteBatch;
import com.badlogic.gdx.math.Affine2;

/**
 * Draws an SC2 display object (a movie clip at one of its frames, or a shape) the way the game
 * composes it: each child at its frame's matrix and colour transform, each shape's draw commands as
 * textured triangle strips cut from the atlas. Atlases are premultiplied, so the blend functions
 * below are the premultiplied forms of the clip's blend modes.
 *
 * <p>Nested clips play the parent's frame index modulo their own length. The colour transform's
 * additive part is not applied (the default shader multiplies only).
 */
public final class ScDrawer {

  public static final int BLEND_NORMAL = 0;
  public static final int BLEND_MULTIPLY = 3;
  public static final int BLEND_SCREEN = 4;
  public static final int BLEND_ADD = 8;

  private static final int MAX_DEPTH = 24;
  private static final int MAX_VERTS = 2048;

  private final PolygonSpriteBatch batch;
  private final ArtCache cache;
  private final float[] verts = new float[MAX_VERTS * 5];
  private final short[] tris = new short[MAX_VERTS * 3];
  private final Affine2[] stack = new Affine2[MAX_DEPTH + 1];
  private final Affine2 local = new Affine2();
  private int currentBlend = -1;
  // the colour transforms' additive part, straight colour, per depth of the walk
  private final float[] addR = new float[MAX_DEPTH + 1];
  private final float[] addG = new float[MAX_DEPTH + 1];
  private final float[] addB = new float[MAX_DEPTH + 1];
  private float curAddR = Float.NaN, curAddG, curAddB;
  private com.badlogic.gdx.graphics.glutils.ShaderProgram colorShader;
  private boolean silhouetteOn;

  public ScDrawer(PolygonSpriteBatch batch, ArtCache cache) {
    this.batch = batch;
    this.cache = cache;
    for (int i = 0; i < stack.length; i++) stack[i] = new Affine2();
  }

  public PolygonSpriteBatch batch() {
    return batch;
  }

  private com.badlogic.gdx.graphics.glutils.ShaderProgram silhouette;

  /**
   * Draws with the texture's coverage only, coloured by the tint (a shadow): on, until turned off.
   * Call between draws; it flushes the batch.
   */
  public void setSilhouette(boolean on) {
    if (on && silhouette == null) {
      String vertex =
          "attribute vec4 a_position; attribute vec4 a_color; attribute vec2 a_texCoord0;\n"
              + "uniform mat4 u_projTrans; varying vec4 v_color; varying vec2 v_texCoords;\n"
              + "void main() { v_color = a_color; v_color.a = v_color.a * (255.0/254.0);\n"
              + "  v_texCoords = a_texCoord0; gl_Position = u_projTrans * a_position; }";
      String fragment =
          "#ifdef GL_ES\nprecision mediump float;\n#endif\n"
              + "varying vec4 v_color; varying vec2 v_texCoords; uniform sampler2D u_texture;\n"
              + "void main() { gl_FragColor = v_color * texture2D(u_texture, v_texCoords).a; }";
      silhouette = new com.badlogic.gdx.graphics.glutils.ShaderProgram(vertex, fragment);
      if (!silhouette.isCompiled()) {
        throw new IllegalStateException("silhouette shader: " + silhouette.getLog());
      }
    }
    silhouetteOn = on;
    batch.setShader(on ? silhouette : colorShader());
    curAddR = Float.NaN;
  }

  /**
   * The colour shader: the premultiplied texture times the vertex colour (the transforms'
   * multipliers) plus the transforms' additive colour, carried in {@code u_add} (straight).
   */
  private com.badlogic.gdx.graphics.glutils.ShaderProgram colorShader() {
    if (colorShader == null) {
      String vertex =
          "attribute vec4 a_position; attribute vec4 a_color; attribute vec2 a_texCoord0;\n"
              + "uniform mat4 u_projTrans; varying vec4 v_color; varying vec2 v_texCoords;\n"
              + "void main() { v_color = a_color; v_color.a = v_color.a * (255.0/254.0);\n"
              + "  v_texCoords = a_texCoord0; gl_Position = u_projTrans * a_position; }";
      String fragment =
          "#ifdef GL_ES\nprecision mediump float;\n#endif\n"
              + "varying vec4 v_color; varying vec2 v_texCoords; uniform sampler2D u_texture;\n"
              + "uniform vec3 u_add;\n"
              + "void main() { vec4 t = texture2D(u_texture, v_texCoords);\n"
              + "  gl_FragColor = v_color * t + vec4(u_add * t.a * v_color.a, 0.0); }";
      colorShader = new com.badlogic.gdx.graphics.glutils.ShaderProgram(vertex, fragment);
      if (!colorShader.isCompiled()) {
        throw new IllegalStateException("colour shader: " + colorShader.getLog());
      }
    }
    return colorShader;
  }

  /** Starts a batch of drawing (blend state reset). */
  public void begin() {
    batch.setShader(silhouetteOn ? silhouette : colorShader());
    curAddR = Float.NaN;
    batch.begin();
    currentBlend = -1;
    setBlend(BLEND_NORMAL);
  }

  public void end() {
    batch.end();
  }

  /**
   * Draws object {@code id} of the scene at {@code frame}, {@code transform} mapping the object's
   * display units (y down) to world pixels, tinted by {@code tint} (straight alpha).
   */
  public void draw(ScScene scene, int id, int frame, Affine2 transform, Color tint) {
    stack[0].set(transform);
    overrides = null;
    addR[0] = addG[0] = addB[0] = 0;
    drawObject(scene, id, frame, 0, tint.r, tint.g, tint.b, tint.a, BLEND_NORMAL);
    setBlend(BLEND_NORMAL);
  }

  /**
   * A named child instance drawn differently: at its own timeline {@code frame} (a turret's
   * rotation), or replaced by another clip ({@code scene}, {@code id}, {@code frame}: a character
   * standing at a placeholder such as the king tower's {@code king_dummy}).
   */
  public record Override(String name, ScScene scene, int id, int frame, boolean flip) {}

  private java.util.List<Override> overrides;

  /** As {@link #draw}, with the named child instances drawn as the overrides say. */
  public void draw(
      ScScene scene,
      int id,
      int frame,
      Affine2 transform,
      Color tint,
      java.util.List<Override> childOverrides) {
    stack[0].set(transform);
    overrides = childOverrides;
    addR[0] = addG[0] = addB[0] = 0;
    drawObject(scene, id, frame, 0, tint.r, tint.g, tint.b, tint.a, BLEND_NORMAL);
    overrides = null;
    setBlend(BLEND_NORMAL);
  }

  private void drawObject(
      ScScene scene, int id, int frame, int depth, float r, float g, float b, float a, int blend) {
    if (depth >= MAX_DEPTH || a <= 0.002f) return;
    ScScene.Command[] commands = scene.shape(id);
    if (commands != null) {
      for (ScScene.Command c : commands) {
        drawCommand(scene, c, stack[depth], depth, r, g, b, a, blend);
      }
      return;
    }
    ScScene.Clip clip = scene.clip(id);
    if (clip == null || clip.frameCount() == 0) return;
    int[] elements = clip.frames()[Math.floorMod(frame, clip.frameCount())];
    ScScene.Bank bank = scene.bank(clip.bank());
    for (int k = 0; k + 2 < elements.length; k += 3) {
      int child = elements[k];
      int mi = elements[k + 1];
      int ci = elements[k + 2];
      if (child < 0 || child >= clip.children().length) continue;
      Affine2 m = stack[depth + 1];
      m.set(stack[depth]);
      if (mi != ScScene.IDENTITY && bank != null && mi * 6 + 5 < bank.matrices().length) {
        float[] mm = bank.matrices();
        int o = mi * 6;
        // x' = a*x + c*y + tx ; y' = b*x + d*y + ty
        local.m00 = mm[o];
        local.m10 = mm[o + 1];
        local.m01 = mm[o + 2];
        local.m11 = mm[o + 3];
        local.m02 = mm[o + 4];
        local.m12 = mm[o + 5];
        m.mul(local);
      }
      float cr = r, cg = g, cb = b, ca = a;
      addR[depth + 1] = addR[depth];
      addG[depth + 1] = addG[depth];
      addB[depth + 1] = addB[depth];
      if (ci != ScScene.IDENTITY && bank != null && ci * 7 + 6 < bank.colors().length) {
        int[] cc = bank.colors();
        int o = ci * 7;
        // parent(child(x)) = x * childMul * parentMul + childAdd * parentMul + parentAdd
        addR[depth + 1] = cc[o + 4] / 255f * r + addR[depth];
        addG[depth + 1] = cc[o + 5] / 255f * g + addG[depth];
        addB[depth + 1] = cc[o + 6] / 255f * b + addB[depth];
        cr *= cc[o] / 255f;
        cg *= cc[o + 1] / 255f;
        cb *= cc[o + 2] / 255f;
        ca *= cc[o + 3] / 255f;
      }
      int childBlend =
          child < clip.blend().length && clip.blend()[child] != 0 ? clip.blend()[child] : blend;
      if (overrides != null && child < clip.names().length && !clip.names()[child].isEmpty()) {
        Override o = overrideFor(clip.names()[child]);
        if (o != null) {
          java.util.List<Override> saved = overrides;
          if (o.flip()) stack[depth + 1].scale(-1, 1);
          if (o.scene() == null) {
            overrides = null;
            drawObject(
                scene, clip.children()[child], o.frame(), depth + 1, cr, cg, cb, ca, childBlend);
          } else {
            overrides = null;
            drawObject(o.scene(), o.id(), o.frame(), depth + 1, cr, cg, cb, ca, childBlend);
          }
          overrides = saved;
          continue;
        }
      }
      drawObject(scene, clip.children()[child], frame, depth + 1, cr, cg, cb, ca, childBlend);
    }
  }

  private Override overrideFor(String name) {
    for (Override o : overrides) if (o.name().equals(name)) return o;
    return null;
  }

  private void drawCommand(
      ScScene scene,
      ScScene.Command c,
      Affine2 m,
      int depth,
      float r,
      float g,
      float b,
      float a,
      int blend) {
    int n = c.xy().length / 2;
    if (n < 3 || n > MAX_VERTS) return;
    Texture texture = cache.texture(scene, c.texture());
    // premultiplied vertex colour
    float packed = Color.toFloatBits(r * a, g * a, b * a, a);
    float[] xy = c.xy();
    float[] uv = c.uv();
    for (int i = 0; i < n; i++) {
      float x = xy[i * 2];
      float y = xy[i * 2 + 1];
      int o = i * 5;
      verts[o] = m.m00 * x + m.m01 * y + m.m02;
      verts[o + 1] = m.m10 * x + m.m11 * y + m.m12;
      verts[o + 2] = packed;
      verts[o + 3] = uv[i * 2];
      verts[o + 4] = uv[i * 2 + 1];
    }
    int t = 0;
    for (int i = 0; i + 2 < n; i++) {
      tris[t++] = (short) i;
      tris[t++] = (short) (i + 1);
      tris[t++] = (short) (i + 2);
    }
    setBlend(blend);
    if (!silhouetteOn
        && (addR[depth] != curAddR || addG[depth] != curAddG || addB[depth] != curAddB)) {
      batch.flush();
      curAddR = addR[depth];
      curAddG = addG[depth];
      curAddB = addB[depth];
      colorShader.setUniformf("u_add", curAddR, curAddG, curAddB);
    }
    batch.draw(texture, verts, 0, n * 5, tris, 0, t);
  }

  private void setBlend(int blend) {
    int mode =
        switch (blend) {
          case BLEND_ADD, BLEND_SCREEN, BLEND_MULTIPLY -> blend;
          default -> BLEND_NORMAL;
        };
    if (mode == currentBlend) return;
    currentBlend = mode;
    switch (mode) {
      case BLEND_ADD -> batch.setBlendFunction(GL20.GL_ONE, GL20.GL_ONE);
      case BLEND_SCREEN -> batch.setBlendFunction(GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_COLOR);
      case BLEND_MULTIPLY -> batch.setBlendFunction(GL20.GL_DST_COLOR, GL20.GL_ONE_MINUS_SRC_ALPHA);
      default -> batch.setBlendFunction(GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA);
    }
  }
}
