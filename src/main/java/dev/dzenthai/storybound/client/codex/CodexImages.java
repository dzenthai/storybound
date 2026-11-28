package dev.dzenthai.storybound.client.codex;

import com.mojang.blaze3d.platform.NativeImage;
import dev.dzenthai.storybound.client.sound.StoryAssets;
import dev.dzenthai.storybound.client.sound.StoryImageFiles;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import org.jetbrains.annotations.Nullable;

/**
 * Resolves Codex illustration textures from {@link StoryAssets} or resource packs, with live {@link DynamicTexture}
 * uploads so screenshots captured in Dev Mode appear immediately without requiring a full resource reload.
 */
public final class CodexImages implements ResourceManagerReloadListener {
    public static final CodexImages INSTANCE = new CodexImages();

    public record Loaded(ResourceLocation texture, int width, int height) {}

    private static final Map<ResourceLocation, Loaded> LIVE = new HashMap<>();
    private static final Map<ResourceLocation, Optional<Loaded>> RESOURCE_CACHE = new HashMap<>();

    private CodexImages() {}

    /** Uploads a newly captured or saved image into the texture manager immediately. */
    public static void registerLive(ResourceLocation imageId, NativeImage image) {
        ResourceLocation texture = StoryImageFiles.resolveTexture(imageId);
        int width = image.getWidth();
        int height = image.getHeight();
        Minecraft.getInstance().getTextureManager().register(texture, new DynamicTexture(image));
        Loaded loaded = new Loaded(texture, width, height);
        LIVE.put(texture, loaded);
        RESOURCE_CACHE.remove(texture);
    }

    /** Returns the ready-to-render texture and pixel dimensions, or null when missing. */
    public static @Nullable Loaded resolve(ResourceLocation imageId) {
        ResourceLocation texture = StoryImageFiles.resolveTexture(imageId);
        Loaded live = LIVE.get(texture);
        if (live != null) {
            return live;
        }
        Optional<Loaded> cached = RESOURCE_CACHE.get(texture);
        if (cached != null) {
            return cached.orElse(null);
        }
        Minecraft mc = Minecraft.getInstance();
        Optional<Resource> resource = mc.getResourceManager().getResource(texture);
        if (resource.isPresent()) {
            try (InputStream stream = resource.get().open()) {
                int[] size = StoryImageFiles.pngSize(stream);
                if (size != null && size[0] > 0 && size[1] > 0) {
                    Loaded loaded = new Loaded(texture, size[0], size[1]);
                    RESOURCE_CACHE.put(texture, Optional.of(loaded));
                    return loaded;
                }
            } catch (IOException ignored) {
                // Fall through to disk check or missing.
            }
        }
        // Fallback: an image dropped into config/storybound/images/ since the last F3+T can still load directly from disk.
        StoryImageFiles.Image disk = StoryAssets.images().byId(imageId.toString());
        if (disk != null) {
            try (InputStream stream = java.nio.file.Files.newInputStream(disk.file())) {
                NativeImage image = NativeImage.read(stream);
                registerLive(imageId, image);
                return LIVE.get(texture);
            } catch (IOException ignored) {
                // Unreadable image is treated as missing.
            }
        }
        RESOURCE_CACHE.put(texture, Optional.empty());
        return null;
    }

    public static void clear() {
        if (!LIVE.isEmpty()) {
            java.util.List<ResourceLocation> toRelease = new java.util.ArrayList<>(LIVE.keySet());
            LIVE.clear();
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
        RESOURCE_CACHE.clear();
    }

    @Override
    public void onResourceManagerReload(ResourceManager resourceManager) {
        clear();
    }
}

