package dev.dzenthai.storybound.client.dialogue;

import dev.dzenthai.storybound.client.StoryboundClient;
import dev.dzenthai.storybound.client.UiPalette;
import dev.dzenthai.storybound.client.menu.MenuTheme;
import dev.dzenthai.storybound.client.menu.MenuThemes;
import dev.dzenthai.storybound.module.dialogue.ThinkTime;
import java.util.List;
import net.minecraft.Util;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Subtitles of {@code presentation: hud} dialogues (and of bubble lines with no bubble to show), above the hotbar; the player keeps moving. */
public final class DialogueHud {
    private static final int MAX_WIDTH = 300;
    private static final int PADDING = 5;
    private static final int LINE_HEIGHT = 10;
    /** Room for the hotbar, health and the action bar text. */
    private static final int BOTTOM_OFFSET = 72;
    private static final int PROMPT_HEIGHT = 11;

    private DialogueHud() {}

    public static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui || minecraft.screen instanceof DialogueScreen) {
            return;
        }
        int top = renderLine(graphics, minecraft);
        if (ClientDialogue.waitsForAnswer()) {
            renderPrompt(graphics, minecraft, top >= 0 ? top : graphics.guiHeight() - BOTTOM_OFFSET);
        }
    }

    /**
     * "[H] Answer" over the subtitles while a choice waits for the talk key (November 6, 2026), in the talk hint's look,
     * with its timer under it as a shrinking bar, as in the window. Drawn by the code until the author's art replaces it.
     */
    private static void renderPrompt(GuiGraphics graphics, Minecraft minecraft, int above) {
        ClientDialogue.Choices choices = ClientDialogue.choices();
        if (choices == null) {
            return;
        }
        MenuTheme theme = MenuThemes.INSTANCE.theme();
        Font font = minecraft.font;
        Component key = StoryboundClient.TALK.getTranslatedKeyMessage();
        Component label = Component.translatable("storybound.dialogue.answer_prompt");
        int keyWidth = font.width(key) + 7;
        int width = keyWidth + 4 + font.width(label);
        int x = (graphics.guiWidth() - width) / 2;
        int y = above - 4 - PROMPT_HEIGHT - (choices.timeoutTicks() > 0 ? 4 : 0);
        graphics.fill(x, y, x + keyWidth, y + PROMPT_HEIGHT, theme.hudBackground());
        graphics.renderOutline(x, y, keyWidth, PROMPT_HEIGHT, theme.hudTitle());
        graphics.drawString(font, key, x + 4, y + 2, theme.hudTitle(), false);
        graphics.drawString(font, label, x + keyWidth + 4, y + 2, theme.hudText(), true);
        if (choices.timeoutTicks() > 0) {
            int barY = y + PROMPT_HEIGHT + 2;
            graphics.fill(x, barY, x + width, barY + 2, (0xB0 << 24) | UiPalette.SHADOW_DEEP);
            graphics.fill(x, barY, x + Math.round(width * choices.timeLeft()), barY + 2, DialogueChrome.GOLD);
        }
    }

    /** The subtitle box of the line, if it has one; returns its top, or -1. */
    private static int renderLine(GuiGraphics graphics, Minecraft minecraft) {
        Font font = minecraft.font;
        long now = Util.getMillis();
        ClientDialogue.Chosen chosen = ClientDialogue.chosen();
        if (chosen != null) {
            DialogueChrome.renderChosen(graphics, font, chosen, now,
                    graphics.guiWidth() * 62 / 100, graphics.guiHeight() * 38 / 100);
        }
        ClientDialogue.Line line = ClientDialogue.line();
        if (line == null || !subtitled(line, minecraft)) {
            return -1;
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
        return boxY;
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
