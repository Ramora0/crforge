/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.desktop.render.art;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * The art cache the extractor ({@code tools/game-art}) builds from the user's own game install: one
 * folder per content sha, holding the scene JSON and decoded atlases of each SC2 file under {@code
 * sc/}. The game's art is never part of this repository; without a cache the realistic view is
 * simply unavailable.
 *
 * <p>The folder is the system property {@code crforge.art} (a content folder, or a cache root
 * holding {@code current.json}), else {@code ~/.crforge/art/current.json}'s content folder.
 */
@Slf4j
public final class ArtCache {

  public static final String PROPERTY = "crforge.art";

  private final Path folder;
  private final Map<String, Optional<ScScene>> scenes = new HashMap<>();
  private final Map<Path, Texture> textures = new HashMap<>();

  private ArtCache(Path folder) {
    this.folder = folder;
  }

  public Path folder() {
    return folder;
  }

  /** The cache this process is configured with, if one exists on disk. */
  public static Optional<ArtCache> locate() {
    String configured = System.getProperty(PROPERTY);
    Path root =
        configured != null && !configured.isBlank()
            ? Paths.get(configured)
            : Paths.get(System.getProperty("user.home"), ".crforge", "art");
    Optional<Path> content = contentFolder(root);
    if (content.isEmpty()) {
      log.info("No game art cache at {} (see tools/game-art)", root);
      return Optional.empty();
    }
    log.info("Game art cache: {}", content.get());
    return Optional.of(new ArtCache(content.get()));
  }

  static Optional<Path> contentFolder(Path root) {
    if (Files.isDirectory(root.resolve("sc"))) return Optional.of(root);
    Path current = root.resolve("current.json");
    if (Files.isRegularFile(current)) {
      try {
        JsonNode node = new ObjectMapper().readTree(current.toFile());
        Path content = root.resolve(node.path("content_sha").asText());
        if (Files.isDirectory(content.resolve("sc"))) return Optional.of(content);
      } catch (IOException e) {
        log.warn("Cannot read {}: {}", current, e.getMessage());
      }
    }
    return Optional.empty();
  }

  /** A decoded file of the cache by its path in the game's assets ({@code locations/x.csv}). */
  public Optional<Path> file(String relative) {
    Path p = folder.resolve(relative);
    return Files.isRegularFile(p) ? Optional.of(p) : Optional.empty();
  }

  /** The client table file (decoded text) at that path under {@code client/}, if extracted. */
  public Optional<Path> clientFile(String relative) {
    Path p = folder.resolve("client").resolve(relative);
    return Files.isRegularFile(p) ? Optional.of(p) : Optional.empty();
  }

  /**
   * The scene of an SC2 file named as the game's tables name it ({@code sc/chr_knight.sc}) or by
   * its stem, or null when the cache does not hold it.
   */
  public ScScene scene(String fileName) {
    if (fileName == null || fileName.isBlank()) return null;
    String stem = fileName;
    int slash = stem.lastIndexOf('/');
    if (slash >= 0) stem = stem.substring(slash + 1);
    if (stem.endsWith(".sc")) stem = stem.substring(0, stem.length() - 3);
    final String key = stem;
    return scenes
        .computeIfAbsent(
            key,
            k -> {
              Path json = folder.resolve("sc").resolve(k + ".json");
              if (!Files.isRegularFile(json)) {
                log.debug("No scene {} in the art cache", k);
                return Optional.empty();
              }
              try {
                return Optional.of(ScScene.read(json));
              } catch (RuntimeException e) {
                log.warn("Cannot read scene {}: {}", json, e.getMessage());
                return Optional.empty();
              }
            })
        .orElse(null);
  }

  /** The atlas of a scene, loaded on first use with mipmaps and premultiplied alpha. */
  public Texture texture(ScScene scene, int index) {
    ScScene.TextureRef ref = scene.textures().get(index);
    Path png = scene.folder().resolve(ref.png());
    return textures.computeIfAbsent(png, ArtCache::loadPremultiplied);
  }

  private static Texture loadPremultiplied(Path png) {
    Pixmap pixmap = new Pixmap(new com.badlogic.gdx.files.FileHandle(png.toFile()));
    if (pixmap.getFormat() != Pixmap.Format.RGBA8888) {
      Pixmap converted = new Pixmap(pixmap.getWidth(), pixmap.getHeight(), Pixmap.Format.RGBA8888);
      converted.setBlending(Pixmap.Blending.None);
      converted.drawPixmap(pixmap, 0, 0);
      pixmap.dispose();
      pixmap = converted;
    }
    ByteBuffer px = pixmap.getPixels();
    int n = pixmap.getWidth() * pixmap.getHeight();
    for (int i = 0; i < n; i++) {
      int o = i * 4;
      int a = px.get(o + 3) & 0xFF;
      if (a == 255) continue;
      px.put(o, (byte) (((px.get(o) & 0xFF) * a + 127) / 255));
      px.put(o + 1, (byte) (((px.get(o + 1) & 0xFF) * a + 127) / 255));
      px.put(o + 2, (byte) (((px.get(o + 2) & 0xFF) * a + 127) / 255));
    }
    Texture texture = new Texture(pixmap, true);
    texture.setFilter(TextureFilter.MipMapLinearLinear, TextureFilter.Linear);
    pixmap.dispose();
    return texture;
  }

  public void dispose() {
    textures.values().forEach(Texture::dispose);
    textures.clear();
  }
}
