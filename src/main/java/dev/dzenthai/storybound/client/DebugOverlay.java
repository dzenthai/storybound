package dev.dzenthai.storybound.client;

import dev.dzenthai.storybound.network.DebugPayload;
import java.util.List;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** The {@code /storybound debug} panel at the top left: whose story, their state, then the last logic lines. */
public final class DebugOverlay {
    private static final int WIDTH = 260;
    private static final int LINE = 10;

    private static volatile DebugPayload panel = DebugPayload.OFF;

    private DebugOverlay() {}

    public static void set(DebugPayload received) {
        panel = received;
    }

    public static void clear() {
        panel = DebugPayload.OFF;
    }

    public static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        DebugPayload shown = panel;
        Minecraft minecraft = Minecraft.getInstance();
        if (shown.player().isEmpty() || minecraft.options.hideGui) {
            return;
        }
        Font font = minecraft.font;
        List<String> state = shown.state().isEmpty() ? List.of(Component.translatable("storybound.debug.nothing").getString()) : shown.state();
        int height = 14 + (state.size() + shown.recent().size()) * LINE + 8;
        int x = 4;
        int y = 4;
        graphics.fill(x, y, x + WIDTH, y + height, 0xB0101418);
        graphics.drawString(font, Component.translatable("storybound.debug.title", shown.player()), x + 4, y + 3, 0xFFE8C66A, false);
        int line = y + 15;
        for (String text : state) {
            graphics.drawString(font, font.plainSubstrByWidth(text, WIDTH - 8), x + 4, line, 0xFFE0E6ED, false);
            line += LINE;
        }
        line += 4;
        for (String text : shown.recent()) {
            int color = text.startsWith("✘") ? 0xFFFF8A80 : text.startsWith("✔") || text.startsWith("▶") ? 0xFFA8E6A3 : 0xFFB0BEC5;
            graphics.drawString(font, font.plainSubstrByWidth(text, WIDTH - 8), x + 4, line, color, false);
            line += LINE;
        }
    }
}
