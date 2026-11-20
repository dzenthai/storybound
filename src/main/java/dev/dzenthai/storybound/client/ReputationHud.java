package dev.dzenthai.storybound.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.network.ReputationPayload;
import net.minecraft.Util;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * A player's standing with an NPC: five slots, stars above 0, skulls below, one filled for every 10 of its relationship
 * scale, in a corner of the HUD (top left by default, {@code reputationCorner}): icons alone, twice the size, with no
 * backing and no name, as in Villagers Reborn. Shown for {@link #FLASH_MS} when the standing changes, the slot that
 * filled or emptied pulsing; and all the while the player talks with that NPC. The stars and skulls are stand-ins drawn
 * by the code until the author's {@code textures/gui/hud/rep_star.png} and {@code rep_skull.png} are there (18 x 9: the
 * filled one, then the empty slot), which then take over by themselves.
 */
public final class ReputationHud {
    private static final long FLASH_MS = 4000;
    private static final long FADE_IN_MS = 150;
    private static final long FADE_OUT_MS = 500;
    private static final long PULSE_MS = 400;
    private static final int STEP = 10;
    private static final int SLOTS = 5;
    private static final int ICON = 9;
    private static final int GAP = 1;
    private static final int MARGIN = 4;
    /** An icon's pixel in HUD pixels: the 9 x 9 icons drawn 18 x 18. */
    private static final int SIZE = 2;
    private static final ResourceLocation STAR = Storybound.id("textures/gui/hud/rep_star.png");
    private static final ResourceLocation SKULL = Storybound.id("textures/gui/hud/rep_skull.png");

    private static final String[] STAR_MAP = {
            "....#....", "....#....", "...###...", "#########", ".#######.", "..#####..", "..##.##..", ".##...##.", ".#.....#."};
    private static final String[] SKULL_MAP = {
            "..#####..", ".#######.", "#########", "#..###..#", "#..###..#", "#########", ".###.###.", "..#####..", "..#.#.#.."};

    /** What is shown: a scale, whose name, its value; and, for a change, by how much and when. */
    private record Shown(ResourceLocation scale, Component name, int value, int delta, long since) {}

    private static @Nullable Shown focus;
    private static @Nullable Shown flash;
    /** Whether the author's textures are there, looked up once. */
    private static @Nullable Boolean ownStar;
    private static @Nullable Boolean ownSkull;

    private ReputationHud() {}

    public static void apply(ReputationPayload payload) {
        long now = Util.getMillis();
        if (payload.focus()) {
            focus = payload.scale().map(scale -> new Shown(scale, payload.name(), payload.value(), 0, now)).orElse(null);
            return;
        }
        payload.scale().ifPresent(scale -> {
            flash = new Shown(scale, payload.name(), payload.value(), payload.delta(), now);
            if (focus != null && focus.scale().equals(scale)) {
                focus = new Shown(scale, focus.name(), payload.value(), 0, focus.since());
            }
        });
    }

    public static void clear() {
        focus = null;
        flash = null;
        ownStar = null;
        ownSkull = null;
    }

    public static void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui || minecraft.player == null) {
            return;
        }
        long now = Util.getMillis();
        Shown shown = flash != null && now - flash.since() < FLASH_MS ? flash : focus;
        if (shown == null) {
            return;
        }
        float alpha = 1f;
        if (shown == flash) {
            long age = now - flash.since();
            boolean stays = focus != null && focus.scale().equals(flash.scale());
            alpha = Math.min(1f, age / (float) FADE_IN_MS);
            if (!stays) {
                alpha = Math.min(alpha, (FLASH_MS - age) / (float) FADE_OUT_MS);
            }
        }
        if (alpha <= 0.02f) {
            return;
        }
        StoryboundClientConfig.Corner corner = StoryboundClientConfig.INSTANCE.reputationCorner.get();
        // In icon pixels from here: the HUD's, SIZE times.
        int iconsWidth = SLOTS * ICON + (SLOTS - 1) * GAP;
        HudScale.push(graphics);
        graphics.pose().pushPose();
        graphics.pose().scale(SIZE, SIZE, 1);
        int across = HudScale.width() / SIZE;
        int down = HudScale.height() / SIZE;
        int margin = Math.max(1, MARGIN / SIZE);
        int x = corner.right() ? across - iconsWidth - margin : margin;
        int iconY = corner.bottom() ? down - ICON - margin : margin;
        RenderSystem.enableBlend();
        int value = shown.value();
        boolean stars = value >= 0;
        int filled = symbols(value);
        // The slot that filled or emptied with this change pulses.
        int changed = -1;
        float pulse = 0;
        if (shown == flash && shown.delta() != 0 && now - shown.since() < PULSE_MS) {
            int before = symbols(value - shown.delta());
            if (before != filled || (value - shown.delta() >= 0) != stars) {
                changed = Math.max(0, Math.max(filled, before) - 1);
                float t = (now - shown.since()) / (float) PULSE_MS;
                pulse = (1 - t) * (1 - t);
            }
        }
        for (int slot = 0; slot < SLOTS; slot++) {
            int iconX = x + slot * (ICON + GAP);
            float scale = slot == changed ? 1 + 0.6f * pulse : 1;
            graphics.pose().pushPose();
            graphics.pose().translate(iconX + ICON / 2f, iconY + ICON / 2f, 0);
            graphics.pose().scale(scale, scale, 1);
            icon(graphics, stars, slot < filled, -ICON / 2, -ICON / 2, alpha);
            graphics.pose().popPose();
        }
        graphics.pose().popPose();
        HudScale.pop(graphics);
    }

    /** Stars (above 0) or skulls (below), 0 to 5: one for every 10, toward zero, within -50 to 50. */
    static int symbols(int value) {
        return Math.min(SLOTS, Math.abs(Math.max(-SLOTS * STEP, Math.min(SLOTS * STEP, value))) / STEP);
    }

    private static void icon(GuiGraphics graphics, boolean star, boolean full, int x, int y, float alpha) {
        if (own(star)) {
            graphics.setColor(1, 1, 1, alpha);
            graphics.blit(star ? STAR : SKULL, x, y, full ? 0 : ICON, 0, ICON, ICON, 2 * ICON, ICON);
            graphics.setColor(1, 1, 1, 1);
            return;
        }
        String[] map = star ? STAR_MAP : SKULL_MAP;
        // With no backing, a shadow a pixel down and right keeps them seen on snow and at night alike.
        for (int row = 0; row < ICON; row++) {
            for (int column = 0; column < ICON; column++) {
                if (map[row].charAt(column) == '#') {
                    graphics.fill(x + column + 1, y + row + 1, x + column + 2, y + row + 2, faded(full ? 0xA0000000 : 0x70000000, alpha));
                }
            }
        }
        for (int row = 0; row < ICON; row++) {
            for (int column = 0; column < ICON; column++) {
                if (map[row].charAt(column) != '#') {
                    continue;
                }
                int color;
                if (!full) {
                    // An empty slot: the shape pale, half seen, over its shadow.
                    color = 0x80D8C8A8;
                } else if (star) {
                    color = row < 3 ? 0xFFFFE08A : row < 6 ? 0xFFE1B34D : 0xFFB08031;
                } else {
                    color = row < 3 ? 0xFFF5DFB2 : row < 6 ? 0xFFE6C68C : 0xFFCDA269;
                }
                graphics.fill(x + column, y + row, x + column + 1, y + row + 1, faded(color, alpha));
            }
        }
    }

    private static boolean own(boolean star) {
        if (star && ownStar == null) {
            ownStar = Minecraft.getInstance().getResourceManager().getResource(STAR).isPresent();
        }
        if (!star && ownSkull == null) {
            ownSkull = Minecraft.getInstance().getResourceManager().getResource(SKULL).isPresent();
        }
        return star ? ownStar : ownSkull;
    }

    private static int faded(int argb, float alpha) {
        int a = Math.round(((argb >>> 24) & 0xFF) * alpha);
        return (a << 24) | (argb & 0xFFFFFF);
    }
}
