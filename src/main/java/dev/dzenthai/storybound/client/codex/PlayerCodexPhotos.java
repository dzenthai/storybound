package dev.dzenthai.storybound.client.codex;

import com.mojang.blaze3d.platform.NativeImage;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.ClientCodex;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

/**
 * Per-world album of Codex photos captured by the local player ({@code <gameDir>/storybound/photos/<world>/<ns>/<path>.png}).
 * Kept separate from the modpack's {@code config/storybound/images/} so each playthrough builds its own field journal.
 */
public final class PlayerCodexPhotos {
    private static final Map<ResourceLocation, Optional<CodexImages.Loaded>> CACHE = new HashMap<>();
    private static @Nullable String cachedWorldKey;

    private PlayerCodexPhotos() {}

    /** Returns true if the player has already captured a photo for {@code entryId} in the current world. */
    public static boolean hasPhoto(ResourceLocation entryId) {
        return resolve(entryId) != null;
    }

    /** Resolves the player's captured photo for {@code entryId} in the current world, loading from disk lazily. */
    public static @Nullable CodexImages.Loaded resolve(ResourceLocation entryId) {
        ensureWorld();
        Optional<CodexImages.Loaded> hit = CACHE.get(entryId);
        if (hit != null) {
            return hit.orElse(null);
        }
        Path file = photoPath(entryId);
        if (file == null || !Files.isRegularFile(file)) {
            CACHE.put(entryId, Optional.empty());
            return null;
        }
        try (InputStream stream = Files.newInputStream(file)) {
            NativeImage image = NativeImage.read(stream);
            CodexImages.Loaded loaded = registerTexture(entryId, image);
            CACHE.put(entryId, Optional.of(loaded));
            return loaded;
        } catch (IOException | RuntimeException e) {
            Storybound.LOGGER.warn("Storybound: could not read player Codex photo {}: {}", file, e.getMessage());
            CACHE.put(entryId, Optional.empty());
            return null;
        }
    }

    /**
     * Saves {@code image} into the player's album for the current world and uploads it immediately for Codex rendering.
     * Ownership of {@code image} transfers to the registered {@link DynamicTexture}.
     */
    public static boolean save(ResourceLocation entryId, NativeImage image) {
        ensureWorld();
        Path file = photoPath(entryId);
        if (file == null) {
            image.close();
            return false;
        }
        try {
            Files.createDirectories(file.getParent());
            image.writeToFile(file);
            CodexImages.Loaded loaded = registerTexture(entryId, image);
            CACHE.put(entryId, Optional.of(loaded));
            ClientCodex.bumpVersion();
            return true;
        } catch (IOException | RuntimeException e) {
            Storybound.LOGGER.warn("Storybound: could not save player Codex photo {}: {}", file, e.getMessage());
            image.close();
            return false;
        }
    }

    public static void clear() {
        releaseCachedTextures();
        cachedWorldKey = null;
    }

    private static void releaseCachedTextures() {
        if (CACHE.isEmpty()) {
            return;
        }
        java.util.List<ResourceLocation> toRelease = new java.util.ArrayList<>();
        for (Optional<CodexImages.Loaded> entry : CACHE.values()) {
            entry.ifPresent(loaded -> toRelease.add(loaded.texture()));
        }
        CACHE.clear();
        if (toRelease.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.getTextureManager() != null) {
            Runnable releaseAll = () -> {
                var manager = mc.getTextureManager();
                for (ResourceLocation texture : toRelease) {
                    manager.release(texture);
                }
            };
            if (mc.isSameThread()) {
                releaseAll.run();
            } else {
                mc.execute(releaseAll);
            }
        }
    }

    private static CodexImages.Loaded registerTexture(ResourceLocation entryId, NativeImage image) {
        ResourceLocation texture = textureId(entryId);
        int width = image.getWidth();
        int height = image.getHeight();
        Minecraft.getInstance().getTextureManager().register(texture, new DynamicTexture(image));
        return new CodexImages.Loaded(texture, width, height);
    }

    private static ResourceLocation textureId(ResourceLocation entryId) {
        String safe = (entryId.getNamespace() + "/" + entryId.getPath()).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_./-]", "_");
        return Storybound.id("player_codex_photo/" + safe);
    }

    private static void ensureWorld() {
        String key = currentWorldKey();
        if (key == null || !key.equals(cachedWorldKey)) {
            releaseCachedTextures();
            cachedWorldKey = key;
        }
    }

    private static @Nullable Path photoPath(ResourceLocation entryId) {
        String key = currentWorldKey();
        if (key == null) {
            return null;
        }
        Minecraft mc = Minecraft.getInstance();
        return mc.gameDirectory.toPath()
                .resolve("storybound")
                .resolve("photos")
                .resolve(key)
                .resolve(sanitizeSegment(entryId.getNamespace()))
                .resolve(sanitizePath(entryId.getPath()) + ".png");
    }

    static @Nullable String currentWorldKey() {
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer singleplayer = mc.getSingleplayerServer();
        if (singleplayer != null) {
            Path folder = singleplayer.getWorldPath(LevelResource.ROOT).normalize().getFileName();
            String name = folder != null ? folder.toString() : "world";
            return "local_" + sanitizeSegment(name);
        }
        ServerData server = mc.getCurrentServer();
        if (server != null) {
            return "server_" + sanitizeSegment(server.ip);
        }
        return mc.level != null ? "session" : null;
    }

    static String sanitizeSegment(String raw) {
        String clean = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]+", "_").replaceAll("^_+|_+$", "");
        return clean.isEmpty() ? "world" : clean;
    }

    private static String sanitizePath(String path) {
        String[] parts = path.split("/");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                out.append('/');
            }
            out.append(sanitizeSegment(parts[i]));
        }
        return out.toString();
    }
}

