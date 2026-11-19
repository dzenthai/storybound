package dev.dzenthai.storybound.client.dev;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.dzenthai.storybound.Storybound;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

/**
 * The sky behind the editor's canvas (November 19, 2026; the author's five skies, drawn in Aseprite; before, a flat
 * dark ground): a far layer, as behind the skill trees of games, moving at half the canvas's pace when it is dragged,
 * so the cards seem to float over it. It neither moves nor changes its size when the canvas zooms (scaled to cover the screen), and repeats
 * mirrored, so the endless canvas never shows its edge or a seam. Softened and darkened once, when first drawn: doubled
 * by nearest neighbour, a gaussian blur of one of its pixels, 45% of its light; then drawn smoothly. A sky is drawn at
 * random each time the editor opens, never the one of the time before ({@link #shuffle}; November 19, 2026, the author;
 * the same day, one per story by its id).
 */
final class DevBackground {
    /** How far the sky moves for the canvas's movement (the canvas keeps the sum, see {@code GraphView}). */
    static final double PARALLAX = 0.5;
    /** The light kept: 45% (November 19, 2026, the author; the same day 60%, too bright under the cards). */
    private static final float DARKEN = 0.45f;
    /** The blur's sigma in the doubled image's pixels: one pixel of the drawing. */
    private static final double SIGMA = 2.0;
    private static final List<String> SKIES = List.of("golden_nebula", "worlds_in_gold", "silent_eclipse", "star_rift", "comet_tide");

    /** The softened skies, by name, once made; empty when one could not be read. */
    private static final Map<String, Optional<Sky>> MADE = new HashMap<>();
    /** The sky of this opening of the editor. */
    private static int current = new java.util.Random().nextInt(SKIES.size());

    /** Another sky for a new opening of the editor: any of the others, at random. */
    static void shuffle() {
        current = (current + 1 + java.util.concurrent.ThreadLocalRandom.current().nextInt(SKIES.size() - 1)) % SKIES.size();
    }

    private record Sky(ResourceLocation texture, int width, int height) {}

    private DevBackground() {}

    /**
     * Fills the canvas ({@code left, top, right, bottom}) with the story's sky, moved by {@code skyX, skyY} (half of
     * the canvas's moves, none of its zooming); the screen's size sets its scale, so opening the inspector does not
     * resize it.
     */
    static void draw(GuiGraphics graphics, int left, int top, int right, int bottom, int screenWidth, int screenHeight,
                     double skyX, double skyY) {
        Optional<Sky> made = MADE.computeIfAbsent(SKIES.get(current), DevBackground::make);
        if (made.isEmpty() || right <= left || bottom <= top) {
            return;
        }
        Sky sky = made.get();
        // One copy of the sky on the screen: as large as covers the whole screen, its own pixels doubled.
        float scale = Math.max(screenWidth / (float) sky.width(), screenHeight / (float) sky.height());
        int tileWidth = Math.max(1, Math.round(sky.width() * scale));
        int tileHeight = Math.max(1, Math.round(sky.height() * scale));
        // Placed by the screen, not the canvas (November 19, 2026: centred on the canvas, it jumped when the inspector
        // opened and the canvas narrowed): centred on the screen at the start, then moved; the canvas only shows a part.
        float u = (float) (left - ((screenWidth - tileWidth) / 2.0 + skyX));
        float v = (float) (top - ((screenHeight - tileHeight) / 2.0 + skyY));
        RenderSystem.enableBlend();
        graphics.blit(sky.texture(), left, top, right - left, bottom - top, u, v, right - left, bottom - top, tileWidth, tileHeight);
    }

    /** Reads the sky, softens and darkens it, and gives it to the game as a texture that repeats mirrored. */
    private static Optional<Sky> make(String name) {
        ResourceLocation source = Storybound.id("textures/gui/dev/background/" + name + ".png");
        Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(source);
        if (resource.isEmpty()) {
            Storybound.LOGGER.warn("Storybound: dev mode background {} is missing", source);
            return Optional.empty();
        }
        try (InputStream in = resource.get().open(); NativeImage drawn = NativeImage.read(in)) {
            NativeImage soft = soften(drawn);
            DynamicTexture texture = new DynamicTexture(soft);
            texture.setFilter(true, false);
            texture.bind();
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL14.GL_MIRRORED_REPEAT);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL14.GL_MIRRORED_REPEAT);
            ResourceLocation id = Storybound.id("dev_background/" + name);
            Minecraft.getInstance().getTextureManager().register(id, texture);
            return Optional.of(new Sky(id, soft.getWidth(), soft.getHeight()));
        } catch (Exception e) {
            Storybound.LOGGER.warn("Storybound: dev mode background {} could not be read", source, e);
            return Optional.empty();
        }
    }

    /**
     * Doubled by nearest neighbour (so the blur has room within a pixel of the drawing), blurred by a separable gaussian
     * whose edges reflect as the texture repeats, and darkened; opaque. The channels are treated alike, so the image's
     * byte order does not matter.
     */
    static NativeImage soften(NativeImage drawn) {
        int width = drawn.getWidth() * 2;
        int height = drawn.getHeight() * 2;
        int radius = (int) Math.ceil(SIGMA * 3);
        double[] kernel = new double[radius * 2 + 1];
        double sum = 0;
        for (int i = -radius; i <= radius; i++) {
            kernel[i + radius] = Math.exp(-(i * i) / (2 * SIGMA * SIGMA));
            sum += kernel[i + radius];
        }
        for (int i = 0; i < kernel.length; i++) {
            kernel[i] /= sum;
        }
        float[][] channels = new float[3][width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = drawn.getPixelRGBA(x / 2, y / 2);
                for (int c = 0; c < 3; c++) {
                    channels[c][y * width + x] = (pixel >> (c * 8)) & 0xFF;
                }
            }
        }
        float[] line = new float[Math.max(width, height)];
        for (float[] channel : channels) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    double acc = 0;
                    for (int i = -radius; i <= radius; i++) {
                        acc += channel[y * width + mirror(x + i, width)] * kernel[i + radius];
                    }
                    line[x] = (float) acc;
                }
                System.arraycopy(line, 0, channel, y * width, width);
            }
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    double acc = 0;
                    for (int i = -radius; i <= radius; i++) {
                        acc += channel[mirror(y + i, height) * width + x] * kernel[i + radius];
                    }
                    line[y] = (float) acc;
                }
                for (int y = 0; y < height; y++) {
                    channel[y * width + x] = line[y];
                }
            }
        }
        NativeImage soft = new NativeImage(width, height, false);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = 0xFF000000;
                for (int c = 0; c < 3; c++) {
                    int value = Math.min(255, Math.round(channels[c][y * width + x] * DARKEN));
                    pixel |= value << (c * 8);
                }
                soft.setPixelRGBA(x, y, pixel);
            }
        }
        return soft;
    }

    /** An index reflected back into {@code 0..size-1}, as the mirrored texture repeats. */
    private static int mirror(int index, int size) {
        int period = size * 2;
        int i = Math.floorMod(index, period);
        return i < size ? i : period - 1 - i;
    }
}
