package dev.dzenthai.storybound.client.scene;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

/**
 * Open while a scene plays: it takes the keys and the mouse, so the player neither moves nor looks around, and Escape
 * opens the pause menu (the scene cannot be skipped). It draws the black bars when the scene has them and the line in
 * subtitles: the speaker's name, then the words, centred above the lower bar.
 */
final class SceneScreen extends Screen {
    /** Each bar's share of the screen's height. */
    private static final float BAR = 0.12f;
    private static final int LINE_WIDTH = 320;
    private static final int SPEAKER_COLOR = 0xFFE1B34D;

    SceneScreen() {
        super(Component.empty());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && minecraft != null) {
            minecraft.setScreen(new PauseScreen(true));
        }
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return true;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Nothing: the world is the picture.
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int bar = ClientScene.letterbox() ? Math.round(height * BAR) : 0;
        if (bar > 0) {
            graphics.fill(0, 0, width, bar, 0xFF000000);
            graphics.fill(0, height - bar, width, height, 0xFF000000);
        }
        Component line = ClientScene.line();
        if (line == null) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        List<FormattedCharSequence> rows = client.font.split(line, Math.min(LINE_WIDTH, width - 40));
        int rowHeight = client.font.lineHeight + 2;
        Component speaker = ClientScene.speaker();
        int lines = rows.size() + (speaker != null ? 1 : 0);
        int bottom = height - Math.max(bar, 24) - 8;
        int y = bottom - lines * rowHeight;
        if (speaker != null) {
            Component name = speaker.copy().withStyle(ChatFormatting.BOLD);
            graphics.drawString(client.font, name, (width - client.font.width(name)) / 2, y, SPEAKER_COLOR, true);
            y += rowHeight;
        }
        for (FormattedCharSequence row : rows) {
            graphics.drawString(client.font, row, (width - client.font.width(row)) / 2, y, 0xFFFFFFFF, true);
            y += rowHeight;
        }
    }
}
