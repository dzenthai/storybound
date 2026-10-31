package dev.dzenthai.storybound.client;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * The scale of Storybound's HUD, what lies over the world beside Minecraft's hotbar, chat and other mods' HUDs: the
 * quest tracker, the waypoint markers, the observation bar and its words. It keeps to Minecraft's GUI scale, so it
 * stays in proportion with them and follows the player's choice (often made for eyes that need larger text), but never
 * under the window's height over {@link #WINDOW_PER_MINIMUM} (2 window pixels at 1080, 4 at 2160), where pixel art and
 * text stop reading; plus the player's steps ({@code hud.size} in {@code storybound-client.toml}). What is held rather
 * than laid over the world, the Adventure Menu and the documents with their items and text, has a scale of its own
 * from the window ({@code BookViewport}). Drawn between {@link #push} and {@link #pop}, one unit is a HUD pixel and
 * (0, 0) the window's top left, so whole units fall on whole window pixels; the HUD's width and height are in them.
 * The dialogue's hint and subtitles keep the plain GUI scale until their art is redone.
 * <p>
 * October 31, 2026; before, the same day, the window's height over 360 whatever the GUI scale (3 at 1080): at GUI
 * scale 1 the tracker, the markers and the words came out three times the hotbar's size.
 */
public final class HudScale {
    /** The smallest HUD pixel is the window's height over this, in window pixels. */
    private static final int WINDOW_PER_MINIMUM = 540;

    private HudScale() {}

    /** Window pixels to a HUD pixel: the GUI scale, at least the window's minimum, plus the player's steps. */
    public static int pixel() {
        Window window = Minecraft.getInstance().getWindow();
        int base = Math.max((int) window.getGuiScale(), window.getHeight() / WINDOW_PER_MINIMUM);
        return Math.max(1, base + StoryboundClientConfig.INSTANCE.hudSize.get());
    }

    public static int width() {
        return Minecraft.getInstance().getWindow().getWidth() / pixel();
    }

    public static int height() {
        return Minecraft.getInstance().getWindow().getHeight() / pixel();
    }

    /** From here, draw in HUD pixels from the window's top left. */
    public static void push(GuiGraphics graphics) {
        float scale = (float) (pixel() / Minecraft.getInstance().getWindow().getGuiScale());
        graphics.pose().pushPose();
        graphics.pose().scale(scale, scale, 1);
    }

    public static void pop(GuiGraphics graphics) {
        graphics.pose().popPose();
    }
}
