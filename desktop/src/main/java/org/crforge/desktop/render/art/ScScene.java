/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.render.art;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * One of the game's SC2 display-object files, as the art cache keeps it: the file's shapes (atlas
 * cut-outs drawn as triangle strips), its movie clips (per frame, which children are drawn with
 * which matrix and colour transform) and its matrix banks, read 1:1 from the scene JSON the
 * extractor ({@code tools/game-art}) writes. Nothing here is pre-rendered.
 */
public final class ScScene {

  /** Matrix or colour index meaning identity. */
  public static final int IDENTITY = 0xFFFF;

  /** One draw command: a triangle strip of display-space points and their atlas coordinates. */
  public record Command(int texture, float[] xy, float[] uv) {}

  /** A movie clip: children ids and, per frame, (child index, matrix, colour) triples. */
  public record Clip(
      int id,
      int fps,
      int bank,
      int[] children,
      String[] names,
      int[] blend,
      int[][] frames,
      Map<Integer, String> labels) {

    /** The child index of the instance of that name, or -1. */
    public int childNamed(String name) {
      for (int i = 0; i < names.length; i++) if (name.equals(names[i])) return i;
      return -1;
    }

    public int frameCount() {
      return frames.length;
    }

    /** The first frame carrying that label, or -1. */
    public int labelFrame(String label) {
      for (Map.Entry<Integer, String> e : labels.entrySet()) {
        if (e.getValue().equals(label)) return e.getKey();
      }
      return -1;
    }
  }

  /** A matrix bank: 2x3 matrices (a, b, c, d, tx, ty) and colour transforms (7 bytes each). */
  public record Bank(float[] matrices, int[] colors) {}

  public record TextureRef(String png, int width, int height) {}

  private final Path folder;
  private final String file;
  private final List<TextureRef> textures;
  private final Map<String, Integer> exports;
  private final Map<Integer, Command[]> shapes;
  private final Map<Integer, Clip> clips;
  private final List<Bank> banks;

  private ScScene(
      Path folder,
      String file,
      List<TextureRef> textures,
      Map<String, Integer> exports,
      Map<Integer, Command[]> shapes,
      Map<Integer, Clip> clips,
      List<Bank> banks) {
    this.folder = folder;
    this.file = file;
    this.textures = textures;
    this.exports = exports;
    this.shapes = shapes;
    this.clips = clips;
    this.banks = banks;
  }

  public static ScScene read(Path json) {
    JsonNode root;
    try {
      root = new ObjectMapper().readTree(json.toFile());
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read the scene " + json, e);
    }
    List<TextureRef> textures = new ArrayList<>();
    for (JsonNode t : root.path("textures")) {
      textures.add(
          new TextureRef(
              t.path("png").asText(), t.path("width").asInt(), t.path("height").asInt()));
    }
    Map<String, Integer> exports = new HashMap<>();
    root.path("exports").properties().forEach(e -> exports.put(e.getKey(), e.getValue().asInt()));
    Map<Integer, Command[]> shapes = new HashMap<>();
    root.path("shapes")
        .properties()
        .forEach(
            e -> {
              JsonNode cmds = e.getValue();
              Command[] out = new Command[cmds.size()];
              for (int i = 0; i < cmds.size(); i++) {
                JsonNode c = cmds.get(i);
                out[i] =
                    new Command(c.path("tex").asInt(), floats(c.path("xy")), floats(c.path("uv")));
              }
              shapes.put(Integer.parseInt(e.getKey()), out);
            });
    Map<Integer, Clip> clips = new HashMap<>();
    root.path("clips")
        .properties()
        .forEach(
            e -> {
              JsonNode c = e.getValue();
              JsonNode fr = c.path("frames");
              int[][] frames = new int[fr.size()][];
              for (int i = 0; i < fr.size(); i++) frames[i] = ints(fr.get(i));
              Map<Integer, String> labels = new HashMap<>();
              c.path("labels")
                  .properties()
                  .forEach(l -> labels.put(Integer.parseInt(l.getKey()), l.getValue().asText()));
              int id = Integer.parseInt(e.getKey());
              clips.put(
                  id,
                  new Clip(
                      id,
                      Math.max(1, c.path("fps").asInt(30)),
                      c.path("bank").asInt(),
                      ints(c.path("children")),
                      strings(c.path("names")),
                      ints(c.path("blend")),
                      frames,
                      labels));
            });
    List<Bank> banks = new ArrayList<>();
    for (JsonNode b : root.path("banks")) {
      banks.add(new Bank(floats(b.path("matrices")), ints(b.path("colors"))));
    }
    return new ScScene(
        json.getParent(),
        root.path("file").asText(),
        Collections.unmodifiableList(textures),
        exports,
        shapes,
        clips,
        banks);
  }

  private static float[] floats(JsonNode a) {
    float[] out = new float[a.size()];
    Iterator<JsonNode> it = a.elements();
    for (int i = 0; it.hasNext(); i++) out[i] = (float) it.next().asDouble();
    return out;
  }

  private static String[] strings(JsonNode a) {
    String[] out = new String[a.size()];
    for (int i = 0; i < out.length; i++) out[i] = a.get(i).asText("");
    return out;
  }

  private static int[] ints(JsonNode a) {
    int[] out = new int[a.size()];
    Iterator<JsonNode> it = a.elements();
    for (int i = 0; it.hasNext(); i++) out[i] = it.next().asInt();
    return out;
  }

  public Path folder() {
    return folder;
  }

  public String file() {
    return file;
  }

  public List<TextureRef> textures() {
    return textures;
  }

  public Map<String, Integer> exports() {
    return exports;
  }

  /** The object id an export names, or -1. */
  public int export(String name) {
    Integer id = exports.get(name);
    return id == null ? -1 : id;
  }

  public Command[] shape(int id) {
    return shapes.get(id);
  }

  public Clip clip(int id) {
    return clips.get(id);
  }

  public Bank bank(int index) {
    return index >= 0 && index < banks.size() ? banks.get(index) : null;
  }

  /** Frames of the object (1 for a shape). */
  public int frameCount(int id) {
    Clip c = clips.get(id);
    return c == null ? 1 : Math.max(1, c.frameCount());
  }
}
