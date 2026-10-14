package dev.dzenthai.storybound.client.dialogue;

import dev.dzenthai.storybound.client.menu.MenuTheme;
import dev.dzenthai.storybound.client.menu.MenuThemes;
import dev.dzenthai.storybound.module.dialogue.DialogueDefinition.Presentation;
import dev.dzenthai.storybound.module.dialogue.ThinkTime;
import java.util.List;
import net.minecraft.Util;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.FormattedCharSequence;

/** Subtitles of {@code presentation: hud} dialogues, above the hotbar; the player keeps moving. */
public final class DialogueHud {
    private static final int MAX_WIDTH = 300;
    private static final int PADDING = 5;
    private static final int LINE_HEIGHT = 10;
    /** Room for the hotbar, health and the action bar text. */
    private static final int BOTTOM_OFFSET = 72;

    private DialogueHud() {}

    public static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientDialogue.Line line = ClientDialogue.line();
        if (line == null || ClientDialogue.presentation() != Presentation.HUD || minecraft.options.hideGui
                || minecraft.screen instanceof DialogueScreen) {
            return;
        }
        MenuTheme theme = MenuThemes.INSTANCE.theme();
        Font font = minecraft.font;
        int width = Math.min(MAX_WIDTH, graphics.guiWidth() - 20);
        List<FormattedCharSequence> lines = font.split(line.text(), width);
        boolean named = !line.name().getString().isEmpty();
        int textWidth = lines.stream().mapToInt(font::width).max().orElse(0);
        if (named) {
            textWidth = Math.max(textWidth, font.width(line.name()));
        }
        int height = (lines.size() + (named ? 1 : 0)) * LINE_HEIGHT + 2 * PADDING - 1;
        int x = (graphics.guiWidth() - textWidth) / 2;
        int y = graphics.guiHeight() - BOTTOM_OFFSET - height;
        graphics.fill(x - PADDING, y, x + textWidth + PADDING, y + height, theme.hudBackground());
        int textY = y + PADDING;
        if (named) {
            graphics.drawString(font, line.name(), x, textY, theme.hudTitle(), true);
            textY += LINE_HEIGHT;
        }
        if (line.isThinking()) {
            graphics.drawString(font, ThinkTime.dots(Util.getMillis()), x, textY, theme.hudText(), true);
        } else {
            DialogueText.draw(graphics, font, lines, x, textY, LINE_HEIGHT, theme.hudText(), true, line.visibleChars());
        }
    }
}
