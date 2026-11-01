package dev.dzenthai.storybound.client.dialogue;

import dev.dzenthai.storybound.client.menu.MenuTheme;
import dev.dzenthai.storybound.client.menu.MenuThemes;
import dev.dzenthai.storybound.module.dialogue.ThinkTime;
import java.util.List;
import net.minecraft.Util;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.FormattedCharSequence;

/** Subtitles of {@code presentation: hud} dialogues (and of bubble lines with no bubble to show), above the hotbar; the player keeps moving. */
public final class DialogueHud {
    private static final int MAX_WIDTH = 300;
    private static final int PADDING = 5;
    private static final int LINE_HEIGHT = 10;
    /** Room for the hotbar, health and the action bar text. */
    private static final int BOTTOM_OFFSET = 72;

    private DialogueHud() {}

    public static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui || minecraft.screen instanceof DialogueScreen) {
            return;
        }
        Font font = minecraft.font;
        long now = Util.getMillis();
        ClientDialogue.Chosen chosen = ClientDialogue.chosen();
        if (chosen != null) {
            DialogueChrome.renderChosen(graphics, font, chosen, now,
                    graphics.guiWidth() * 62 / 100, graphics.guiHeight() * 38 / 100);
        }
        ClientDialogue.Line line = ClientDialogue.line();
        if (line == null || !subtitled(line, minecraft)) {
            return;
        }
        MenuTheme theme = MenuThemes.INSTANCE.theme();
        boolean portrait = DialogueChrome.hasPortrait(minecraft, line);
        int portraitSpace = portrait ? DialogueChrome.PORTRAIT_SIZE + 8 : 0;
        int maxTextWidth = Math.max(80, Math.min(MAX_WIDTH, graphics.guiWidth() - 24 - portraitSpace - PADDING * 2));
        List<FormattedCharSequence> lines = font.split(line.text(), maxTextWidth);
        boolean named = !line.name().getString().isEmpty();
        int textWidth = lines.stream().mapToInt(font::width).max().orElse(0);
        if (named) {
            textWidth = Math.max(textWidth, font.width(line.name()));
        }
        textWidth = Math.max(60, textWidth);
        int contentHeight = (lines.size() + (named ? 1 : 0)) * LINE_HEIGHT - 1;
        int boxWidth = portraitSpace + textWidth + PADDING * 2;
        int boxHeight = Math.max(portrait ? DialogueChrome.PORTRAIT_SIZE + PADDING * 2 : 20, contentHeight + PADDING * 2);
        int boxX = (graphics.guiWidth() - boxWidth) / 2;
        int boxY = graphics.guiHeight() - BOTTOM_OFFSET - boxHeight;
        DialogueChrome.box(graphics, boxX, boxY, boxWidth, boxHeight, theme.hudBackground());
        if (portrait) {
            DialogueChrome.renderFrontPortrait(minecraft, graphics, line,
                    boxX + PADDING, boxY + (boxHeight - DialogueChrome.PORTRAIT_SIZE) / 2, DialogueChrome.PORTRAIT_SIZE);
        }
        int textX = boxX + PADDING + portraitSpace;
        int textY = boxY + (boxHeight - contentHeight) / 2;
        if (named) {
            graphics.drawString(font, line.name(), textX, textY, theme.hudTitle(), true);
            textY += LINE_HEIGHT;
        }
        if (line.isThinking()) {
            graphics.drawString(font, ThinkTime.dots(now), textX, textY, theme.hudText(), true);
        } else {
            DialogueText.draw(graphics, font, lines, textX, textY, LINE_HEIGHT, theme.hudText(), true, line.visibleChars());
        }
    }

    /** Every line of a {@code hud} dialogue; in a {@code bubble} one, the lines without a bubble the player can see. */
    private static boolean subtitled(ClientDialogue.Line line, Minecraft minecraft) {
        return switch (ClientDialogue.presentation()) {
            case HUD -> true;
            // A narrator has no head to speak over, and the player's own head is out of sight in first person.
            case BUBBLE -> line.speaker().entityId() == -1 || minecraft.player != null && line.speaker().entityId() == minecraft.player.getId();
            case SCREEN -> false;
        };
    }
}
