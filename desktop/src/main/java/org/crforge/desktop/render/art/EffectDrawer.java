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
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Affine2;

/**
 * {@link ScDrawer}'s traversal with the colour transforms' additive part applied, which much of the
 * effect art needs: smoke, rocks and glows are drawn as flat colours (multiplier 0, an added
 * colour) or tinted by an added colour. The added colour of each draw command is a shader uniform,
 * so commands with a different one flush the batch; effects are few enough for that.
 *
 * <p>Used between {@link #begin} and {@link #end} inside the caller's batch; {@link #end} leaves
 * the batch on its default shader and normal (premultiplied) blending, as {@link ScDrawer} keeps it
 * between draws.
 */
final class EffectDrawer {

  private static final int MAX_DEPTH = 24;
  private static final int MAX_VERTS = 2048;

  private static final String VERTEX =
      "attribute vec4 a_position;\n"
          + "attribute vec4 a_color;\n"
          + "attribute vec2 a_texCoord0;\n"
          + "uniform mat4 u_projTrans;\n"
          + "varying vec4 v_color;\n"
          + "varying vec2 v_texCoords;\n"
          + "void main() {\n"
          + "  v_color = a_color;\n"
          + "  v_color.a = v_color.a * (255.0/254.0);\n"
          + "  v_texCoords = a_texCoord0;\n"
          + "  gl_Position = u_projTrans * a_position;\n"
          + "}\n";

  // premultiplied: rgb = (tex * mult + add) * alpha
  private static final String FRAGMENT =
      "#ifdef GL_ES\nprecision mediump float;\n#endif\n"
          + "varying vec4 v_color;\n"
          + "varying vec2 v_texCoords;\n"
          + "uniform sampler2D u_texture;\n"
          + "uniform vec3 u_add;\n"
          + "void main() {\n"
          + "  vec4 t = texture2D(u_texture, v_texCoords);\n"
          + "  vec4 c = v_color * t;\n"
          + "  c.rgb += u_add * (v_color.a * t.a);\n"
          + "  gl_FragColor = c;\n"
          + "}\n";

  private final ArtCache cache;
  private final ShaderProgram shader;
  private final float[] verts = new float[MAX_VERTS * 5];
  private final short[] tris = new short[MAX_VERTS * 3];
  private final Affine2[] stack = new Affine2[MAX_DEPTH + 1];
  private final Affine2 local = new Affine2();
  private PolygonSpriteBatch batch;
  private int currentBlend = -1;
  private float addR = -1, addG = -1, addB = -1;

  EffectDrawer(ArtCache cache) {
    this.cache = cache;
    for (int i = 0; i < stack.length; i++) stack[i] = new Affine2();
    ShaderProgram.pedantic = false;
    ShaderProgram s = new ShaderProgram(VERTEX, FRAGMENT);
    if (!s.isCompiled()) {
      org.slf4j.LoggerFactory.getLogger(EffectDrawer.class)
          .warn("Effect shader: {}; colour adds are not drawn", s.getLog());
      s.dispose();
      s = null;
    }
    this.shader = s;
  }

  /** Starts drawing into {@code batch}, which the caller has begun. */
  void begin(PolygonSpriteBatch batch) {
    this.batch = batch;
    currentBlend = -1;
    addR = addG = addB = -1;
    if (shader != null) batch.setShader(shader);
  }

  void end() {
    if (batch == null) return;
    if (shader != null) batch.setShader(null);
    setBlend(ScDrawer.BLEND_NORMAL);
    batch = null;
  }

  /**
   * Draws object {@code id} at {@code frame}, {@code transform} mapping display units (y down) to
   * world pixels, the result multiplied by {@code tint} (straight alpha), added colours included.
   */
  void draw(ScScene scene, int id, int frame, Affine2 transform, Color tint) {
    stack[0].set(transform);
    drawObject(
        scene,
        id,
        frame,
        0,
        1,
        1,
        1,
        1,
        0,
        0,
        0,
        ScDrawer.BLEND_NORMAL,
        tint.r,
        tint.g,
        tint.b,
        tint.a);
  }

  private void drawObject(
      ScScene scene,
      int id,
      int frame,
      int depth,
      float r,
      float g,
      float b,
      float a,
      float ar,
      float ag,
      float ab,
      int blend,
      float tr,
      float tg,
      float tb,
      float ta) {
    if (depth >= MAX_DEPTH || a * ta <= 0.002f) return;
    ScScene.Command[] commands = scene.shape(id);
    if (commands != null) {
      for (ScScene.Command c : commands) {
        drawCommand(
            scene,
            c,
            stack[depth],
            r * tr,
            g * tg,
            b * tb,
            a * ta,
            ar * tr,
            ag * tg,
            ab * tb,
            blend);
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
        local.m00 = mm[o];
        local.m10 = mm[o + 1];
        local.m01 = mm[o + 2];
        local.m11 = mm[o + 3];
        local.m02 = mm[o + 4];
        local.m12 = mm[o + 5];
        m.mul(local);
      }
      float cr = r, cg = g, cb = b, ca = a, xr = ar, xg = ag, xb = ab;
      if (ci != ScScene.IDENTITY && bank != null && ci * 7 + 6 < bank.colors().length) {
        int[] cc = bank.colors();
        int o = ci * 7;
        // the child's colour c becomes c * mult + add, then the parent's transform applies
        float mr = cc[o] / 255f, mg = cc[o + 1] / 255f, mb = cc[o + 2] / 255f;
        xr = cc[o + 4] / 255f * r + ar;
        xg = cc[o + 5] / 255f * g + ag;
        xb = cc[o + 6] / 255f * b + ab;
        cr = r * mr;
        cg = g * mg;
        cb = b * mb;
        ca = a * cc[o + 3] / 255f;
      }
      int childBlend =
          child < clip.blend().length && clip.blend()[child] != 0 ? clip.blend()[child] : blend;
      drawObject(
          scene,
          clip.children()[child],
          frame,
          depth + 1,
          cr,
          cg,
          cb,
          ca,
          xr,
          xg,
          xb,
          childBlend,
          tr,
          tg,
          tb,
          ta);
    }
  }

  private void drawCommand(
      ScScene scene,
      ScScene.Command c,
      Affine2 m,
      float r,
      float g,
      float b,
      float a,
      float ar,
      float ag,
      float ab,
      int blend) {
    int n = c.xy().length / 2;
    if (n < 3 || n > MAX_VERTS) return;
    Texture texture = cache.texture(scene, c.texture());
    float packed = Color.toFloatBits(Math.min(1, r) * a, Math.min(1, g) * a, Math.min(1, b) * a, a);
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
    setAdd(Math.min(1, ar), Math.min(1, ag), Math.min(1, ab));
    batch.draw(texture, verts, 0, n * 5, tris, 0, t);
  }

  private void setAdd(float r, float g, float b) {
    if (shader == null) return;
    if (Math.abs(r - addR) < 0.004f && Math.abs(g - addG) < 0.004f && Math.abs(b - addB) < 0.004f) {
      return;
    }
    batch.flush();
    addR = r;
    addG = g;
    addB = b;
    shader.setUniformf("u_add", r, g, b);
  }

  private void setBlend(int blend) {
    int mode =
        switch (blend) {
          case ScDrawer.BLEND_ADD, ScDrawer.BLEND_SCREEN, ScDrawer.BLEND_MULTIPLY -> blend;
          default -> ScDrawer.BLEND_NORMAL;
        };
    if (mode == currentBlend) return;
    currentBlend = mode;
    switch (mode) {
      case ScDrawer.BLEND_ADD -> batch.setBlendFunction(GL20.GL_ONE, GL20.GL_ONE);
      case ScDrawer.BLEND_SCREEN ->
          batch.setBlendFunction(GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_COLOR);
      case ScDrawer.BLEND_MULTIPLY ->
          batch.setBlendFunction(GL20.GL_DST_COLOR, GL20.GL_ONE_MINUS_SRC_ALPHA);
      default -> batch.setBlendFunction(GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA);
    }
  }

  void dispose() {
    if (shader != null) shader.dispose();
  }
}
