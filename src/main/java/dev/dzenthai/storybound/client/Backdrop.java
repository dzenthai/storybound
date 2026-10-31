package dev.dzenthai.storybound.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;

/**
 * How light the picture is under a HUD element, so it can take its variant for a light or a dark background (October
 * 31, 2026, for the observation bar). A few times a second the region is copied from the screen into a pixel buffer on
 * the graphics card; the copy is read on a later call, long after the card has made it, so a frame never waits for it.
 * Small regions only (at most {@link #MAX_PIXELS}): a few dozen kilobytes ten times a second.
 */
public final class Backdrop {
    private static final int MAX_PIXELS = 512 * 64;
    /** Between a copy asked and read, and so between two readings. */
    private static final long EVERY_MS = 100;

    private static int buffer;
    private static @org.jetbrains.annotations.Nullable ByteBuffer data;
    /** The pixels of the copy on its way; 0 for none. */
    private static int pending;
    private static long askedAt;
    private static float luminance = -1;

    private Backdrop() {}

    /** The region's mean luminance, 0 (black) to 1 (white), eased over the last readings; -1 before the first. */
    public static float luminance() {
        return luminance;
    }

    /**
     * Reads the copy asked before, when it is old enough, and asks for one of the region now: window pixels from the
     * top left. Call it while the element shows, before drawing it, so it does not see itself.
     */
    public static void sample(int x, int y, int width, int height) {
        RenderSystem.assertOnRenderThread();
        long now = Util.getMillis();
        if (pending > 0) {
            if (now - askedAt < EVERY_MS) {
                return;
            }
            float read = read(pending);
            luminance = luminance < 0 ? read : luminance + (read - luminance) * 0.5f;
            pending = 0;
        }
        RenderTarget target = Minecraft.getInstance().getMainRenderTarget();
        int left = Math.max(0, x);
        int top = Math.max(0, y);
        int w = Math.min(width, target.width - left);
        int h = Math.min(height, target.height - top);
        if (w <= 0 || h <= 0) {
            return;
        }
        // Too large: its middle part, as wide as fits.
        if (w * h > MAX_PIXELS) {
            int narrower = Math.max(1, MAX_PIXELS / h);
            left += (w - narrower) / 2;
            w = narrower;
            h = Math.min(h, MAX_PIXELS / w);
        }
        if (buffer == 0) {
            buffer = GL15.glGenBuffers();
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, buffer);
            GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, MAX_PIXELS * 4L, GL15.GL_STREAM_READ);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            data = BufferUtils.createByteBuffer(MAX_PIXELS * 4);
        }
        int readBefore = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, target.frameBufferId);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, buffer);
        // The screen's rows go up from its bottom.
        GL11.glReadPixels(left, target.height - top - h, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readBefore);
        pending = w * h;
        askedAt = now;
    }

    private static float read(int pixels) {
        ByteBuffer bytes = data;
        if (bytes == null) {
            return 0.5f;
        }
        bytes.clear().limit(pixels * 4);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, buffer);
        GL15.glGetBufferSubData(GL21.GL_PIXEL_PACK_BUFFER, 0, bytes);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        double sum = 0;
        for (int i = 0; i < pixels; i++) {
            int r = bytes.get(i * 4) & 0xFF;
            int g = bytes.get(i * 4 + 1) & 0xFF;
            int b = bytes.get(i * 4 + 2) & 0xFF;
            sum += 0.2126 * r + 0.7152 * g + 0.0722 * b;
        }
        return (float) (sum / pixels / 255);
    }
}
