/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.render.art;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * The client's effect definitions ({@code legacy_vfx/effects.json} and {@code emitters.json}),
 * flattened for {@link EffectLayer}: an effect name resolves to its drawable parts, each an SC2
 * export played once (or looped) or a particle emitter, with nested effects expanded and their
 * delays and scales folded in. Sound-only, filter and 3D prefab components are dropped.
 */
@Slf4j
final class EffectDefs {

  /** Draw layers, bottom to top (docs/realistic-rendering.md §7). */
  enum Layer {
    BASE,
    GROUND,
    GROUND_ABILITY,
    SHADOW,
    OBJECT,
    ABOVE;

    static Layer of(String s) {
      if (s == null) return OBJECT;
      return switch (s) {
        case "Base" -> BASE;
        case "Ground" -> GROUND;
        case "GroundAbility" -> GROUND_ABILITY;
        case "Shadow" -> SHADOW;
        case "Above", "HealthBars" -> ABOVE;
        default -> OBJECT;
      };
    }

    boolean below() {
      return ordinal() <= SHADOW.ordinal();
    }
  }

  /**
   * One SC2 export: a clip (or shape) with its frame count and rate; {@code additive} when it draws
   * with an additive (or screen) blend, a glow that brightens what is under it.
   */
  record Sprite(ScScene scene, int id, int fps, int frames, boolean playOnce, boolean additive) {}

  /** A particle emitter of {@code emitters.json}, numbers as the file gives them (ms, du, %). */
  static final class Emitter {
    String name;
    Sprite[] sprites;
    int count;
    float minLife, maxLife;
    float minInterval, maxInterval;
    float pMinLife, pMaxLife;
    float minAngle, maxAngle;
    boolean randomAngle;
    float minSpeed, maxSpeed;
    float minVz, maxVz;
    float gravity;
    float startZ, startX;
    float minRadius, maxRadius;
    float areaRadius;
    float startScale, endScale;
    float randomScale;
    float fadeIn, fadeOut;
    float inertia;
    boolean rotateToDirection;
    float rotMinSpeed, rotMaxSpeed;
    boolean loopClip, playClipOnce;
    boolean noBounce, stopOnBounce;
    boolean relativeAngle;
  }

  /**
   * One drawable part of an effect.
   *
   * @param delay seconds after the effect starts
   * @param scale the effect's and component's scales multiplied, 1 = as authored
   * @param loop whether the part repeats while its owner lives
   */
  record Part(Layer layer, float delay, float scale, boolean loop, Sprite swf, Emitter emitter) {}

  /** A sound a component names ({@code soundbanks/...}), {@code delay} seconds into the effect. */
  record Sound(float delay, String ref) {}

  /**
   * A {@code Filter} component: a clip whose one placeholder child's per-frame matrix and colour
   * transform apply to the sprite of the unit the effect lands on (§8), e.g. Zap's {@code
   * filter_target} white flash.
   */
  record Filter(float delay, String file, String export) {}

  /**
   * An effect's drawable parts, sounds and unit filters (any may be empty), and its {@code
   * ShakeScreen} strength (the largest of its nested effects', 0 for none).
   */
  record Effect(
      String name,
      List<Part> parts,
      boolean loop,
      List<Sound> sounds,
      List<Filter> filters,
      int shake) {
    boolean drawable() {
      return !parts.isEmpty();
    }
  }

  private static final int MAX_DEPTH = 6;

  private final ArtCache cache;
  private final JsonNode effects;
  private final JsonNode emitters;
  private final Map<String, Optional<Effect>> resolved = new HashMap<>();
  private final Map<String, Optional<Emitter>> emitterCache = new HashMap<>();
  private final Map<String, Float> halfWidths = new HashMap<>();

  EffectDefs(ArtCache cache) {
    this.cache = cache;
    this.effects = read(cache.clientFile("legacy_vfx/effects.json"));
    this.emitters = read(cache.clientFile("legacy_vfx/emitters.json"));
  }

  private static JsonNode read(Optional<Path> file) {
    if (file.isEmpty()) {
      log.info("No effect definitions in the art cache: effects are not drawn");
      return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }
    try {
      return new ObjectMapper().readTree(file.get().toFile());
    } catch (IOException e) {
      log.warn("Cannot read {}: {}", file.get(), e.getMessage());
      return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }
  }

  /** The effect as drawn for a red owner ({@code EnemyVersion}) or a blue one; null if none. */
  Effect effect(String name, boolean red) {
    if (name == null || name.isBlank()) return null;
    String key = name + (red ? "|r" : "|b");
    return resolved
        .computeIfAbsent(
            key,
            k -> {
              List<Part> parts = new ArrayList<>();
              boolean[] loop = {false};
              List<Sound> sounds = new ArrayList<>();
              List<Filter> filters = new ArrayList<>();
              int[] shake = {0};
              expand(name, red, 0f, 1f, false, 0, parts, sounds, filters, shake, loop);
              // the same sound at the same moment plays once
              List<Sound> unique = new ArrayList<>();
              for (Sound so : sounds) if (!unique.contains(so)) unique.add(so);
              sounds = unique;
              Effect e =
                  new Effect(
                      name,
                      Collections.unmodifiableList(parts),
                      loop[0],
                      Collections.unmodifiableList(sounds),
                      Collections.unmodifiableList(filters),
                      shake[0]);
              return e.drawable() || !sounds.isEmpty() || !filters.isEmpty() || shake[0] > 0
                  ? Optional.of(e)
                  : Optional.empty();
            })
        .orElse(null);
  }

  private void expand(
      String name,
      boolean red,
      float delay,
      float scale,
      boolean loop,
      int depth,
      List<Part> out,
      List<Sound> sounds,
      List<Filter> filters,
      int[] shake,
      boolean[] anyLoop) {
    if (depth > MAX_DEPTH || name == null) return;
    JsonNode def = effects.path(name);
    if (def.isMissingNode()) return;
    // only the played effect swaps to its EnemyVersion: nested ones name unrelated effects there
    // (Deploy_ground_fx_largeSize's is giant_skeleton_deploy_enemy, which nests it again: a red
    // Electro Giant's deploy played the Giant Skeleton's and the Giant's deploy sounds six times)
    if (red && depth == 0) {
      String enemy = def.path("EnemyVersion").asText("");
      if (!enemy.isEmpty() && !enemy.equals(name) && effects.has(enemy)) {
        def = effects.path(enemy);
      }
    }
    shake[0] = Math.max(shake[0], def.path("ShakeScreen").asInt(0));
    float s = scale * def.path("Scale").asInt(100) / 100f;
    boolean l = loop || def.path("Loop").asBoolean(false);
    if (l) anyLoop[0] = true;
    for (JsonNode c : def.path("RenderableComponents")) {
      String type = c.path("Type").asText("");
      float d = delay + c.path("Time").asInt(0) / 1000f;
      float cs = s * c.path("RenderableScale").asInt(100) / 100f;
      Layer layer = Layer.of(c.path("Layer").asText(null));
      String sound = c.path("Sound").asText("");
      if (!sound.isBlank()) sounds.add(new Sound(d, sound));
      switch (type) {
        case "SWF" -> {
          Sprite sp = sprite(c.path("FileName").asText(null), c.path("ExportName").asText(null));
          if (sp != null) out.add(new Part(layer, d, cs, l, sp, null));
        }
        case "ParticleEmitter" -> {
          Emitter em = emitter(c.path("ParticleEmitterName").asText(null));
          if (em != null) out.add(new Part(layer, d, cs, l, null, em));
        }
        case "Filter" -> {
          String file = c.path("FileName").asText("");
          String export = c.path("ExportName").asText("");
          if (!file.isBlank() && !export.isBlank()) filters.add(new Filter(d, file, export));
        }
        case "Effect" ->
            expand(
                c.path("Effect").asText(null),
                red,
                d,
                cs,
                l,
                depth + 1,
                out,
                sounds,
                filters,
                shake,
                anyLoop);
        default -> {}
      }
    }
  }

  /**
   * The {@code FlashWidth} of an effect's first component that has one: the length (du) of a beam
   * clip as authored, which is stretched to span from its source to its target ({@code
   * electro_giant_beam}: {@code electro_wiz_beam}, drawn from x 1 to 82). 0 for none.
   */
  float flashWidth(String name) {
    if (name == null) return 0;
    for (JsonNode c : effects.path(name).path("RenderableComponents")) {
      int w = c.path("FlashWidth").asInt(0);
      if (w > 0) return w;
    }
    return 0;
  }

  Sprite sprite(String file, String export) {
    if (file == null || export == null || file.isBlank() || export.isBlank()) return null;
    ScScene scene = cache.scene(file);
    if (scene == null) return null;
    int id = scene.export(export);
    if (id < 0) return null;
    ScScene.Clip clip = scene.clip(id);
    int fps = clip == null ? 30 : clip.fps();
    boolean once = clip != null && clip.labelFrame("playOnce") >= 0;
    return new Sprite(scene, id, fps, scene.frameCount(id), once, additive(scene, id, 0));
  }

  private static boolean additive(ScScene scene, int id, int depth) {
    ScScene.Clip clip = scene.clip(id);
    if (clip == null || depth > 6) return false;
    for (int b : clip.blend()) {
      if (b == ScDrawer.BLEND_ADD || b == ScDrawer.BLEND_SCREEN) return true;
    }
    for (int child : clip.children()) if (additive(scene, child, depth + 1)) return true;
    return false;
  }

  /**
   * A filter clip's transform {@code seconds} into it (§8), or null once it has played or when the
   * cache lacks it: {@code {a, b, c, d, tx, ty, alpha, addR, addG, addB, mulR, mulG, mulB}}, the
   * matrix in the unit's own display units (origin at its feet, y down), the multipliers and the
   * added colour in 0..1 (straight colour).
   */
  float[] filterAt(String file, String export, float seconds) {
    if (seconds < 0) return null;
    Sprite sp = sprite(file, export);
    if (sp == null) return null;
    ScScene.Clip c = sp.scene().clip(sp.id());
    if (c == null || c.frameCount() == 0) return null;
    int f = (int) Math.floor(seconds * c.fps());
    if (f >= c.frameCount()) return null;
    int[] el = c.frames()[f];
    float[] out = {1, 0, 0, 1, 0, 0, 1, 0, 0, 0, 1, 1, 1};
    if (el.length < 3) return out;
    ScScene.Bank bank = sp.scene().bank(c.bank());
    if (bank == null) return out;
    if (el[1] != ScScene.IDENTITY && el[1] * 6 + 5 < bank.matrices().length) {
      System.arraycopy(bank.matrices(), el[1] * 6, out, 0, 6);
    }
    if (el[2] != ScScene.IDENTITY && el[2] * 7 + 6 < bank.colors().length) {
      int[] cc = bank.colors();
      int o = el[2] * 7;
      out[10] = cc[o] / 255f;
      out[11] = cc[o + 1] / 255f;
      out[12] = cc[o + 2] / 255f;
      out[6] = cc[o + 3] / 255f;
      out[7] = cc[o + 4] / 255f;
      out[8] = cc[o + 5] / 255f;
      out[9] = cc[o + 6] / 255f;
    }
    return out;
  }

  private Emitter emitter(String name) {
    if (name == null || name.isBlank()) return null;
    return emitterCache
        .computeIfAbsent(name, k -> Optional.ofNullable(buildEmitter(k)))
        .orElse(null);
  }

  private Emitter buildEmitter(String name) {
    JsonNode n = emitters.path(name);
    if (n.isMissingNode()) return null;
    List<Sprite> sprites = new ArrayList<>();
    JsonNode names = n.path("ParticleExportName");
    JsonNode files = n.path("ParticleResource");
    for (int i = 0; i < names.size(); i++) {
      String export = names.get(i).asText();
      // tower ruins are drawn by EffectLayer itself, kept for the rest of the battle
      if (export.toLowerCase(Locale.ROOT).startsWith("tower_destroyed_ground")) continue;
      String file = i < files.size() ? files.get(i).asText() : "sc/effects.sc";
      Sprite sp = sprite(file, export);
      if (sp != null) sprites.add(sp);
    }
    if (sprites.isEmpty()) return null;
    Emitter e = new Emitter();
    e.name = name;
    e.sprites = sprites.toArray(new Sprite[0]);
    e.count = Math.max(1, n.path("ParticleCount").asInt(1));
    e.maxLife = f(n, "MaxLife", f(n, "MinLife", 0));
    e.minLife = f(n, "MinLife", e.maxLife);
    e.maxInterval = f(n, "ParticleMaxInterval", f(n, "ParticleMinInterval", 0));
    e.minInterval = f(n, "ParticleMinInterval", e.maxInterval);
    e.pMaxLife = f(n, "ParticleMaxLife", f(n, "ParticleMinLife", 1000));
    e.pMinLife = f(n, "ParticleMinLife", e.pMaxLife);
    e.maxAngle = f(n, "ParticleMaxAngle", 0);
    e.minAngle = f(n, "ParticleMinAngle", 0);
    e.randomAngle = n.path("ParticleRandomAngle").asBoolean(false);
    e.maxSpeed = f(n, "ParticleMaxSpeed", f(n, "ParticleMinSpeed", 0));
    e.minSpeed = f(n, "ParticleMinSpeed", n.has("ParticleMaxSpeed") ? 0 : e.maxSpeed);
    e.maxVz = f(n, "ParticleMaxVelocityZ", f(n, "ParticleMinVelocityZ", 0));
    e.minVz = f(n, "ParticleMinVelocityZ", n.has("ParticleMaxVelocityZ") ? 0 : e.maxVz);
    e.gravity = f(n, "ParticleGravity", 0);
    e.startZ = f(n, "ParticleStartZ", 0);
    e.startX = f(n, "ParticleStartX", 0);
    e.maxRadius = f(n, "ParticleMaxRadius", 0);
    e.minRadius = f(n, "ParticleMinRadius", 0);
    e.areaRadius = f(n, "ParticleStartXYAreaRadius", 0);
    e.startScale = f(n, "StartScale", 100);
    e.endScale = f(n, "EndScale", e.startScale);
    e.randomScale = f(n, "RandomScale", 0);
    e.fadeIn = f(n, "FadeInDuration", 0);
    e.fadeOut = f(n, "FadeOutDuration", 0);
    e.inertia = f(n, "Inertia", 0);
    e.rotateToDirection = n.path("RotateToDirection").asBoolean(false);
    e.rotMaxSpeed = f(n, "RotateMaxSpeed", 0);
    e.rotMinSpeed = f(n, "RotateMinSpeed", -e.rotMaxSpeed);
    e.loopClip = n.path("LoopParticleClip").asBoolean(false);
    e.playClipOnce = n.path("PlayParticleClipOnce").asBoolean(false);
    e.noBounce = n.path("NoBounce").asBoolean(false);
    e.stopOnBounce = n.path("StopOnBounce").asBoolean(false);
    e.relativeAngle = n.path("ParticleAngleRelativeToParent").asBoolean(false);
    return e;
  }

  private static float f(JsonNode n, String key, float fallback) {
    JsonNode v = n.get(key);
    return v == null || !v.isNumber() ? fallback : (float) v.asDouble();
  }

  /**
   * The largest horizontal half-width (du, at scale 1) of an effect's SWF parts over their first
   * frames: the authored radius of a spell's ground ring. 0 when it has none.
   */
  float swfHalfWidth(Effect effect) {
    return halfWidths.computeIfAbsent(
        effect.name(),
        k -> {
          float best = 0;
          for (Part p : effect.parts()) {
            if (p.swf() == null) continue;
            Sprite s = p.swf();
            int frames = Math.min(s.frames(), 40);
            float[] box = {Float.MAX_VALUE, -Float.MAX_VALUE};
            for (int f = 0; f < frames; f++) {
              bounds(s.scene(), s.id(), f, new float[] {1, 0, 0, 1, 0, 0}, 0, box);
            }
            if (box[1] > box[0]) {
              best = Math.max(best, Math.max(Math.abs(box[0]), Math.abs(box[1])) * p.scale());
            }
          }
          return best;
        });
  }

  /**
   * Grows {@code box} (min x, max x) by the object at that frame under matrix m (a,b,c,d,tx,ty).
   */
  private static void bounds(ScScene scene, int id, int frame, float[] m, int depth, float[] box) {
    if (depth > 24) return;
    ScScene.Command[] commands = scene.shape(id);
    if (commands != null) {
      for (ScScene.Command c : commands) {
        float[] xy = c.xy();
        for (int i = 0; i + 1 < xy.length; i += 2) {
          float x = m[0] * xy[i] + m[2] * xy[i + 1] + m[4];
          box[0] = Math.min(box[0], x);
          box[1] = Math.max(box[1], x);
        }
      }
      return;
    }
    ScScene.Clip clip = scene.clip(id);
    if (clip == null || clip.frameCount() == 0) return;
    int[] el = clip.frames()[Math.floorMod(frame, clip.frameCount())];
    ScScene.Bank bank = scene.bank(clip.bank());
    for (int k = 0; k + 2 < el.length; k += 3) {
      int child = el[k];
      int mi = el[k + 1];
      if (child < 0 || child >= clip.children().length) continue;
      float[] n = m.clone();
      if (mi != ScScene.IDENTITY && bank != null && mi * 6 + 5 < bank.matrices().length) {
        float[] mm = bank.matrices();
        int o = mi * 6;
        float a = mm[o],
            b = mm[o + 1],
            c = mm[o + 2],
            d = mm[o + 3],
            tx = mm[o + 4],
            ty = mm[o + 5];
        n[0] = m[0] * a + m[2] * b;
        n[1] = m[1] * a + m[3] * b;
        n[2] = m[0] * c + m[2] * d;
        n[3] = m[1] * c + m[3] * d;
        n[4] = m[0] * tx + m[2] * ty + m[4];
        n[5] = m[1] * tx + m[3] * ty + m[5];
      }
      bounds(scene, clip.children()[child], frame, n, depth + 1, box);
    }
  }
}
