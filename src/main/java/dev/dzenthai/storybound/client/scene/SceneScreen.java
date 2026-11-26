package dev.dzenthai.storybound.client.scene;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

/**
 * Open while a scene plays: it takes the keys and the mouse, so the player neither moves nor looks around; holding
 * Space skips a skippable scene and Escape opens the pause menu. It draws the black bars when the scene has them and
 * the line in subtitles: the speaker's name, then the words, centred above the lower bar.
 */
final class SceneScreen extends Screen {
    /** Each bar's share of the screen's height. */
    private static final float BAR = 0.12f;
    private static final int LINE_WIDTH = 320;
    private static final int SPEAKER_COLOR = 0xFFE1B34D;
    /** How long Space must be held continuously to skip a skippable scene, so mid-jump taps never skip by mistake. */
    static final long SKIP_HOLD_MS = 2200L;

    private long skipHoldStartMs = -1L;
    private boolean requireFreshSpacePress;
    private boolean skipSent;

    SceneScreen() {
        super(Component.empty());
    }

    @Override
    protected void init() {
        super.init();
        resetSkip();
    }

    /** Clears any in-flight hold state when a new scene starts or the screen re-initialises. */
    void resetSkip() {
        skipHoldStartMs = -1L;
        skipSent = false;
        requireFreshSpacePress = isSpaceDown();
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
            skipHoldStartMs = -1L;
            minecraft.setScreen(new PauseScreen(true));
        } else if (keyCode == GLFW.GLFW_KEY_SPACE && ClientScene.skippable() && !skipSent && !requireFreshSpacePress && skipHoldStartMs < 0L) {
            skipHoldStartMs = Util.getMillis();
        }
        return true;
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_SPACE) {
            skipHoldStartMs = -1L;
            requireFreshSpacePress = false;
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
        float holdProgress = updateSkipHold();
        int bar = Math.round(height * BAR * ClientScene.letterboxProgress(partialTick));
        if (bar > 0) {
            graphics.fill(0, 0, width, bar, 0xFF000000);
            graphics.fill(0, height - bar, width, height, 0xFF000000);
        }
        caption(graphics, bar, partialTick);
        skipHint(graphics, bar, partialTick, holdProgress);
        Component line = ClientScene.line();
        if (line == null) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        float alpha = ClientScene.lineAlpha(partialTick);
        if (alpha <= 0.02f) {
            // Below that the game draws text opaque: nothing is left of it anyway.
            return;
        }
        int shade = (int) (alpha * 255) << 24;
        List<FormattedCharSequence> rows = client.font.split(line, Math.min(LINE_WIDTH, width - 40));
        int rowHeight = client.font.lineHeight + 2;
        Component speaker = ClientScene.speaker();
        int lines = rows.size() + (speaker != null ? 1 : 0);
        int bottom = height - Math.max(bar, 24) - 8;
        int y = bottom - lines * rowHeight;
        if (speaker != null) {
            Component name = speaker.copy().withStyle(ChatFormatting.BOLD);
            graphics.drawString(client.font, name, (width - client.font.width(name)) / 2, y, shade | (SPEAKER_COLOR & 0xFFFFFF), true);
            y += rowHeight;
        }
        for (FormattedCharSequence row : rows) {
            graphics.drawString(client.font, row, (width - client.font.width(row)) / 2, y, shade | 0xFFFFFF, true);
            y += rowHeight;
        }
    }

    /** Updates the Space hold timer and triggers the skip once held long enough; returns 0..1 progress. */
    private float updateSkipHold() {
        if (!ClientScene.skippable() || skipSent) {
            skipHoldStartMs = -1L;
            return skipSent ? 1f : 0f;
        }
        boolean down = isSpaceDown();
        if (!down) {
            requireFreshSpacePress = false;
            skipHoldStartMs = -1L;
            return 0f;
        }
        if (requireFreshSpacePress || skipHoldStartMs < 0L) {
            return 0f;
        }
        float progress = skipHoldProgress(Util.getMillis() - skipHoldStartMs);
        if (progress >= 1f) {
            skipSent = true;
            skipHoldStartMs = -1L;
            ClientScene.skip();
        }
        return progress;
    }

    private boolean isSpaceDown() {
        Minecraft client = minecraft != null ? minecraft : Minecraft.getInstance();
        return client.getWindow() != null && InputConstants.isKeyDown(client.getWindow().getWindow(), GLFW.GLFW_KEY_SPACE);
    }

    /** Share of the skip hold completed (0 to 1) after {@code elapsedMs} milliseconds of holding Space. */
    static float skipHoldProgress(long elapsedMs) {
        if (elapsedMs <= 0L) {
            return 0f;
        }
        return Math.min(1f, elapsedMs / (float) SKIP_HOLD_MS);
    }

    /**
     * The caption: in the lower or upper left corner (inside the bars) or in the middle, fading in and out; on black, the
     * screen goes black under it as a silent film's title card.
     */
    private void caption(GuiGraphics graphics, int bar, float partialTick) {
        Component caption = ClientScene.caption();
        if (caption == null) {
            return;
        }
        float alpha = ClientScene.captionAlpha(partialTick);
        if (alpha <= 0.02f) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (ClientScene.captionBlack()) {
            graphics.fill(0, 0, width, height, ((int) (alpha * 255) << 24));
        }
        int textColor = ((int) (alpha * 255) << 24) | 0xF2EAD8;
        int position = ClientScene.captionPosition();
        List<FormattedCharSequence> rows = client.font.split(caption, Math.min(LINE_WIDTH, width - 48));
        int rowHeight = client.font.lineHeight + 3;
        int blockHeight = rows.size() * rowHeight;
        int margin = 24;
        int y = switch (position) {
            case 1 -> Math.max(bar, 12) + 12;
            case 2 -> (height - blockHeight) / 2;
            default -> height - Math.max(bar, 12) - 12 - blockHeight;
        };
        for (FormattedCharSequence row : rows) {
            int x = position == 2 ? (width - client.font.width(row)) / 2 : margin;
            graphics.drawString(client.font, row, x, y, textColor, !ClientScene.captionBlack());
            y += rowHeight;
        }
    }

    /** Quiet skip prompt in the lower right corner when the scene allows skipping, with a hold progress bar. */
    private void skipHint(GuiGraphics graphics, int bar, float partialTick, float holdProgress) {
        if (!ClientScene.skippable()) {
            return;
        }
        double fade = Math.min(1.0, ClientScene.time(partialTick) / 12.0);
        boolean holding = holdProgress > 0f;
        int a = (int) Math.round((holding ? Math.max(fade, 0.92) : fade) * (holding ? 0xF0 : 0xB8));
        if (a <= 5) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        Component hint = Component.translatable("storybound.scene.controls.skip");
        int textWidth = client.font.width(hint);
        int x = width - textWidth - 16;
        int y = bar > client.font.lineHeight + 8
                ? height - bar / 2 - client.font.lineHeight / 2 - 2
                : height - client.font.lineHeight - 14;
        int rgb = holding ? 0xF5EFE0 : 0xD6D0C4;
        graphics.drawString(client.font, hint, x, y, (a << 24) | rgb, true);
        int barY = y + client.font.lineHeight + 3;
        int trackAlpha = holding ? 0x99 : (int) Math.round(fade * 0x45);
        graphics.fill(x, barY, x + textWidth, barY + 2, (trackAlpha << 24) | 0x3A342C);
        if (holding) {
            int filled = Math.max(1, Math.round(textWidth * holdProgress));
            graphics.fill(x, barY, x + filled, barY + 2, 0xFFE1B34D);
        }
    }
}
