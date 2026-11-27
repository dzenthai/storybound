package dev.dzenthai.storybound.client.dev;

import com.mojang.blaze3d.platform.NativeImage;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.UiPalette;
import dev.dzenthai.storybound.client.codex.CodexImages;
import dev.dzenthai.storybound.client.sound.StoryAssets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderHighlightEvent;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * Interactive 16:9 camera viewfinder for capturing Codex illustrations directly from the world.
 * Used both by players capturing their own field-journal photos (when discovering a place or from the Codex page)
 * and by story authors in Dev Mode. Hides the held hand, block highlight, and vanilla HUD while framing, then grabs
 * a clean HUD-free frame on the next render pass and area-downsamples it to 384x216.
 */
public final class DevFrameCapture {
    public static final ResourceLocation HUD_LAYER = Storybound.id("dev_frame_capture");
    private static final float FRAME_FRACTION = 0.72f;
    private static final int OUTPUT_W = 384;
    private static final int OUTPUT_H = 216;
    private static final int COOLDOWN = 4;

    private static boolean active;
    /** 0 = framing, 1 = capture requested (wait for next clean frame start), 2 = clean frame rendering now. */
    private static int captureStage;
    private static int cooldown;
    private static @Nullable Runnable returnAction;
    private static @Nullable Component title;
    private static @Nullable Component hint;
    private static @Nullable Consumer<NativeImage> onRawImage;

    private DevFrameCapture() {}

    public static boolean isActive() {
        return active;
    }

    /**
     * Launches the viewfinder for a player capturing their own Codex photo.
     *
     * @param entryTitle title of the Codex entry shown in the viewfinder header
     * @param reopen     optional callback run after capture or cancellation (e.g. reopening the Codex book)
     * @param onImage    receives the cropped 384x216 {@link NativeImage}; caller owns/closes it
     */
    public static void captureForPlayer(Component entryTitle, @Nullable Runnable reopen, Consumer<NativeImage> onImage) {
        clear();
        active = true;
        captureStage = 0;
        cooldown = COOLDOWN;
        returnAction = reopen;
        title = Component.translatable("storybound.codex.photo.viewfinder_title", entryTitle);
        hint = Component.translatable("storybound.codex.photo.viewfinder_hint");
        onRawImage = onImage;
        Minecraft.getInstance().setScreen(null);
    }

    /** Launches the viewfinder from Dev Mode and writes the captured PNG into {@code config/storybound/images/}. */
    public static void start(DevModeScreen screen, ResourceLocation imageId, Consumer<ResourceLocation> captured) {
        clear();
        active = true;
        captureStage = 0;
        cooldown = COOLDOWN;
        returnAction = () -> Minecraft.getInstance().setScreen(screen);
        title = DevUi.text("codex.capture.title");
        hint = DevUi.text("codex.capture.hint");
        onRawImage = cropped -> {
            try {
                Path out = StoryAssets.imageFolder().resolve(imageId.getNamespace()).resolve(imageId.getPath() + ".png");
                Files.createDirectories(out.getParent());
                cropped.writeToFile(out);
                CodexImages.registerLive(imageId, cropped);
                StoryAssets.rescanImages();
                captured.accept(imageId);
            } catch (IOException | RuntimeException e) {
                cropped.close();
                Storybound.LOGGER.warn("Storybound: failed to save Codex screenshot {}: {}", imageId, e.getMessage());
                screen.status(DevUi.text("codex.capture.failed", e.getMessage()), DevUi.BAD);
            }
        };
        Minecraft.getInstance().setScreen(null);
    }

    public static void clear() {
        active = false;
        captureStage = 0;
        cooldown = 0;
        returnAction = null;
        title = null;
        hint = null;
        onRawImage = null;
    }

    public static void tick() {
        if (!active) {
            return;
        }
        if (cooldown > 0) {
            cooldown--;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            clear();
            return;
        }
        if (captureStage == 0 && mc.screen instanceof PauseScreen) {
            Runnable reopen = returnAction;
            clear();
            mc.setScreen(null);
            if (reopen != null) {
                reopen.run();
            }
        }
    }

    public static void onInteractionTriggered(InputEvent.InteractionKeyMappingTriggered event) {
        if (!active) {
            return;
        }
        event.setCanceled(true);
        event.setSwingHand(false);
        if (cooldown == 0 && captureStage == 0) {
            captureStage = 1;
        }
    }

    public static void onKey(InputEvent.Key event) {
        if (!active || captureStage != 0 || cooldown > 0 || event.getAction() != GLFW.GLFW_PRESS) {
            return;
        }
        int key = event.getKey();
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            captureStage = 1;
        }
    }

    public static void onRenderGuiLayer(RenderGuiLayerEvent.Pre event) {
        if (!active) {
            return;
        }
        // Hide everything during the clean capture frame; hide vanilla HUD layers while framing.
        if (captureStage >= 2 || !event.getName().equals(HUD_LAYER)) {
            event.setCanceled(true);
        }
    }

    public static void onRenderHand(RenderHandEvent event) {
        if (active) {
            event.setCanceled(true);
        }
    }

    public static void onRenderHighlight(RenderHighlightEvent.Block event) {
        if (active) {
            event.setCanceled(true);
        }
    }

    public static void onRenderFramePre(RenderFrameEvent.Pre event) {
        if (active && captureStage == 1) {
            captureStage = 2;
        }
    }

    public static void onRenderFramePost(RenderFrameEvent.Post event) {
        if (!active || captureStage != 2) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Runnable reopen = returnAction;
        Consumer<NativeImage> callback = onRawImage;
        clear();
        if (callback == null) {
            return;
        }
        try (NativeImage raw = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            NativeImage cropped = cropAndDownsample(raw, OUTPUT_W, OUTPUT_H);
            if (mc.player != null) {
                mc.player.playSound(SoundEvents.SPYGLASS_USE, 0.7f, 1.25f);
            }
            mc.execute(() -> {
                callback.accept(cropped);
                if (reopen != null) {
                    reopen.run();
                }
            });
        } catch (RuntimeException e) {
            Storybound.LOGGER.warn("Storybound: failed to capture viewfinder frame: {}", e.getMessage());
            mc.execute(() -> {
                if (reopen != null) {
                    reopen.run();
                }
            });
        }
    }

    /** Renders the 16:9 framing mask, golden corner guides, and caption while picking an angle. */
    public static void renderHud(GuiGraphics graphics, DeltaTracker deltaTracker) {
        if (!active || captureStage >= 2) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int w = graphics.guiWidth();
        int h = graphics.guiHeight();
        int[] box = frameBox(w, h);
        int fx = box[0];
        int fy = box[1];
        int fw = box[2];
        int fh = box[3];

        // Darken outside the 16:9 crop area so the exact captured framing is obvious.
        int mask = 0x8C0C0C0C;
        graphics.fill(0, 0, w, fy, mask);
        graphics.fill(0, fy + fh, w, h, mask);
        graphics.fill(0, fy, fx, fy + fh, mask);
        graphics.fill(fx + fw, fy, w, fy + fh, mask);

        // Subtle rule-of-thirds guides inside the frame.
        int guide = 0x24FFF4C4;
        graphics.fill(fx + fw / 3, fy, fx + fw / 3 + 1, fy + fh, guide);
        graphics.fill(fx + (fw * 2) / 3, fy, fx + (fw * 2) / 3 + 1, fy + fh, guide);
        graphics.fill(fx, fy + fh / 3, fx + fw, fy + fh / 3 + 1, guide);
        graphics.fill(fx, fy + (fh * 2) / 3, fx + fw, fy + (fh * 2) / 3 + 1, guide);

        // Thin golden perimeter + bold brass corner brackets.
        int border = 0xAA000000 | UiPalette.GOLD;
        graphics.fill(fx, fy, fx + fw, fy + 1, border);
        graphics.fill(fx, fy + fh - 1, fx + fw, fy + fh, border);
        graphics.fill(fx, fy, fx + 1, fy + fh, border);
        graphics.fill(fx + fw - 1, fy, fx + fw, fy + fh, border);

        int corner = UiPalette.opaque(UiPalette.GOLD_LIGHT);
        int arm = 14;
        graphics.fill(fx - 1, fy - 1, fx + arm, fy + 1, corner);
        graphics.fill(fx - 1, fy - 1, fx + 1, fy + arm, corner);
        graphics.fill(fx + fw - arm, fy - 1, fx + fw + 1, fy + 1, corner);
        graphics.fill(fx + fw - 1, fy - 1, fx + fw + 1, fy + arm, corner);
        graphics.fill(fx - 1, fy + fh - 1, fx + arm, fy + fh + 1, corner);
        graphics.fill(fx - 1, fy + fh - arm, fx + 1, fy + fh + 1, corner);
        graphics.fill(fx + fw - arm, fy + fh - 1, fx + fw + 1, fy + fh + 1, corner);
        graphics.fill(fx + fw - 1, fy + fh - arm, fx + fw + 1, fy + fh + 1, corner);

        Component heading = Component.literal("📷 ").append(title != null ? title : Component.empty());
        Component caption = hint != null ? hint : Component.empty();
        int boxW = Math.max(font.width(heading), font.width(caption)) + 24;
        int boxX = (w - boxW) / 2;
        int boxY = Math.max(6, (fy - 32) / 2);
        graphics.fill(boxX - 1, boxY - 1, boxX + boxW + 1, boxY + 31, DevUi.ACCENT);
        graphics.fill(boxX, boxY, boxX + boxW, boxY + 30, 0xEE16191F);
        graphics.drawString(font, heading, (w - font.width(heading)) / 2, boxY + 5, DevUi.TEXT, false);
        graphics.drawString(font, caption, (w - font.width(caption)) / 2, boxY + 17, DevUi.DETAIL, false);
    }

    /** Centered 16:9 crop rectangle (x, y, width, height) inside any viewport of size {@code width x height}. */
    static int[] frameBox(int width, int height) {
        int fw = Math.max(16, Math.round(width * FRAME_FRACTION));
        int fh = Math.max(9, Math.round(fw * 9.0f / 16.0f));
        int maxH = Math.max(9, Math.round(height * FRAME_FRACTION));
        if (fh > maxH) {
            fh = maxH;
            fw = Math.max(16, Math.round(fh * 16.0f / 9.0f));
        }
        return new int[]{(width - fw) / 2, (height - fh) / 2, fw, fh};
    }

    /** Crops the centered 16:9 viewfinder region from {@code source} and area-averages it into {@code outW x outH}. */
    private static NativeImage cropAndDownsample(NativeImage source, int outW, int outH) {
        int[] box = frameBox(source.getWidth(), source.getHeight());
        int sx0 = box[0];
        int sy0 = box[1];
        int sw = box[2];
        int sh = box[3];
        NativeImage out = new NativeImage(outW, outH, false);
        for (int y = 0; y < outH; y++) {
            int yStart = sy0 + (y * sh) / outH;
            int yEnd = Math.max(yStart + 1, sy0 + ((y + 1) * sh) / outH);
            yEnd = Math.min(source.getHeight(), yEnd);
            for (int x = 0; x < outW; x++) {
                int xStart = sx0 + (x * sw) / outW;
                int xEnd = Math.max(xStart + 1, sx0 + ((x + 1) * sw) / outW);
                xEnd = Math.min(source.getWidth(), xEnd);
                long r = 0;
                long g = 0;
                long b = 0;
                int count = 0;
                for (int py = yStart; py < yEnd; py++) {
                    for (int px = xStart; px < xEnd; px++) {
                        int abgr = source.getPixelRGBA(px, py);
                        r += abgr & 0xFF;
                        g += (abgr >>> 8) & 0xFF;
                        b += (abgr >>> 16) & 0xFF;
                        count++;
                    }
                }
                int pr = (int) (r / count);
                int pg = (int) (g / count);
                int pb = (int) (b / count);
                out.setPixelRGBA(x, y, 0xFF000000 | (pb << 16) | (pg << 8) | pr);
            }
        }
        return out;
    }
}
