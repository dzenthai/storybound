package dev.dzenthai.storybound.client.dialogue;

import dev.dzenthai.storybound.client.UiPalette;
import dev.dzenthai.storybound.client.camera.CameraShots.ScreenPoint;
import dev.dzenthai.storybound.client.camera.DialogueCamera;
import dev.dzenthai.storybound.client.menu.MenuTheme;
import dev.dzenthai.storybound.client.menu.MenuThemes;
import dev.dzenthai.storybound.module.dialogue.ThinkTime;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

/** A paper conversation overlay. Session state, replies and camera remain owned by ClientDialogue. */
public final class DialogueScreen extends Screen {
    private static final int MARGIN = 18;
    private static final int BOTTOM_OFFSET = 56;
    /**
     * Answers opened from the prompt in subtitles keep the line where the subtitles had it (November 6, 2026; before, it
     * dropped to the screen's place, over the health and hunger bars, which stay shown while the player moves).
     */
    private static final int SUBTITLES_BOTTOM_OFFSET = 72;
    private static final int PADDING = 5;
    private static final int MAX_TEXT_WIDTH = 300;
    private static final int LINE_HEIGHT = 10;
    private static final int REPLY_LINE_HEIGHT = 11;
    private static final int OPTION_GAP = 4;
    private final List<ReplyButton> optionButtons = new ArrayList<>();
    private ClientDialogue.Line builtLine;
    private ClientDialogue.Choices builtChoices;
    private List<FormattedCharSequence> textLines = List.of();
    private List<FormattedCharSequence> nameLines = List.of();
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int textX;
    private int textY;
    private int textWidth;
    private int textHeight;
    private int textScroll;
    private boolean manualTextScroll;
    private boolean portrait;
    private int choicesX;
    private int choicesWidth;
    private int choicesTop;
    private int choicesBottom;
    private int choicesHeight;
    private int choicesScroll;
    private int selectedReply = -1;

    public DialogueScreen() {
        super(Component.translatable("storybound.dialogue.title"));
    }

    @Override
    protected void init() {
        builtLine = ClientDialogue.line();
        builtChoices = ClientDialogue.choices();
        textScroll = 0;
        manualTextScroll = false;
        choicesScroll = 0;
        selectedReply = -1;
        optionButtons.clear();
        if (builtLine != null) {
            portrait = DialogueChrome.hasPortrait(minecraft, builtLine);
            int portraitSpace = portrait ? DialogueChrome.PORTRAIT_SIZE + 8 : 0;
            int maxText = Math.max(80, Math.min(MAX_TEXT_WIDTH, width - MARGIN * 2 - portraitSpace - PADDING * 2));
            boolean named = !builtLine.name().getString().isEmpty();
            nameLines = named ? font.split(builtLine.name(), maxText) : List.of();
            textLines = font.split(builtLine.text(), maxText);
            int measuredWidth = textLines.stream().mapToInt(font::width).max().orElse(0);
            for (FormattedCharSequence nameLine : nameLines) {
                measuredWidth = Math.max(measuredWidth, font.width(nameLine));
            }
            textWidth = Math.max(80, measuredWidth);
            int headingHeight = nameLines.size() * LINE_HEIGHT;
            int fullTextHeight = Math.max(LINE_HEIGHT, textLines.size() * LINE_HEIGHT);
            int maxBodyHeight = Math.max(LINE_HEIGHT * 3, height / 4);
            textHeight = Math.min(fullTextHeight, (maxBodyHeight / LINE_HEIGHT) * LINE_HEIGHT);
            int contentHeight = headingHeight + textHeight - 1;
            panelWidth = portraitSpace + textWidth + PADDING * 2;
            panelHeight = Math.max(portrait ? DialogueChrome.PORTRAIT_SIZE + PADDING * 2 : 22, contentHeight + PADDING * 2);
            panelX = (width - panelWidth) / 2;
            panelY = height - bottomOffset() - panelHeight;
            textX = panelX + PADDING + portraitSpace;
            textY = panelY + (panelHeight - contentHeight) / 2 + headingHeight;
        } else {
            portrait = false;
            nameLines = List.of();
            textLines = List.of();
            panelWidth = 240;
            panelHeight = 0;
            panelX = (width - panelWidth) / 2;
            panelY = height - bottomOffset();
        }
        choicesWidth = Math.min(240, Math.max(150, width * 42 / 100));
        choicesHeight = 0;
        if (builtChoices != null) {
            int maxOptWidth = 120;
            for (int i = 0; i < builtChoices.options().size(); i++) {
                ClientDialogue.Option option = builtChoices.options().get(i);
                ReplyButton button = new ReplyButton(i, option);
                optionButtons.add(addRenderableWidget(button));
                choicesHeight += button.getHeight() + OPTION_GAP;
                maxOptWidth = Math.max(maxOptWidth, button.contentWidth + 28);
            }
            choicesWidth = Math.min(choicesWidth, maxOptWidth);
            for (ReplyButton button : optionButtons) {
                button.setWidth(choicesWidth);
            }
            choicesHeight = Math.max(0, choicesHeight - OPTION_GAP);
            updateChoicesAnchor();
        }
    }

    private void updateChoicesAnchor() {
        if (builtChoices == null || optionButtons.isEmpty()) {
            return;
        }
        int bottomLimit = builtLine != null ? panelY - 10 : height - MARGIN - 16;
        int maxViewport = Math.max(48, Math.min(150, bottomLimit - (MARGIN + 8)));
        int viewHeight = Math.min(choicesHeight, maxViewport);
        double aspect = (double) minecraft.getWindow().getWidth() / Math.max(1, minecraft.getWindow().getHeight());
        ScreenPoint projected = DialogueCamera.projectFocus(aspect);
        int targetX = width * 62 / 100;
        int targetTop = Math.max(MARGIN + 8, (bottomLimit - viewHeight) * 55 / 100);
        if (projected != null) {
            int charScreenX = (int) Math.round((projected.x() + 1.0) * 0.5 * width);
            int charScreenY = (int) Math.round((1.0 - projected.y()) * 0.5 * height);
            targetX = Math.max(width * 60 / 100, charScreenX + 96);
            targetTop = charScreenY - viewHeight / 4;
        }
        choicesX = Math.clamp(targetX, MARGIN, Math.max(MARGIN, width - choicesWidth - MARGIN));
        choicesTop = Math.clamp(targetTop, MARGIN + 8, Math.max(MARGIN + 8, bottomLimit - viewHeight));
        choicesBottom = choicesTop + viewHeight;
        positionChoices();
    }

    private void positionChoices() {
        int y = choicesTop - choicesScroll;
        boolean revealed = (builtLine == null || builtLine.isRevealed()) && selectedReply < 0 && ClientDialogue.chosen() == null;
        for (ReplyButton button : optionButtons) {
            button.setX(choicesX);
            button.setY(y);
            button.visible = revealed && y < choicesBottom && y + button.getHeight() > choicesTop;
            y += button.getHeight() + OPTION_GAP;
        }
    }

    @Override
    public void tick() {
        if (ClientDialogue.line() != builtLine || ClientDialogue.choices() != builtChoices) {
            rebuildWidgets();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (ClientDialogue.line() != builtLine || ClientDialogue.choices() != builtChoices) {
            rebuildWidgets();
        }
        renderBackground(graphics, mouseX, mouseY, partialTick);
        MenuTheme theme = MenuThemes.INSTANCE.theme();
        if (builtLine != null) {
            DialogueChrome.box(graphics, panelX, panelY, panelWidth, panelHeight, theme.hudBackground());
            renderLine(graphics, theme);
        }
        if (builtLine != null || builtChoices != null) {
            renderFooter(graphics, theme);
        }
        updateChoicesAnchor();
        long now = Util.getMillis();
        ClientDialogue.Chosen chosen = ClientDialogue.chosen();
        if (chosen != null) {
            DialogueChrome.renderChosen(graphics, font, chosen, now, choicesX + 8, choicesTop);
        } else if (!optionButtons.isEmpty() && (builtLine == null || builtLine.isRevealed())) {
            clip(graphics, choicesX - 4, choicesTop - 2, choicesX + choicesWidth + 4, choicesBottom + 2);
            for (ReplyButton button : optionButtons) {
                button.render(graphics, mouseX, mouseY >= choicesTop && mouseY < choicesBottom ? mouseY : -1, partialTick);
            }
            graphics.disableScissor();
            DialogueChrome.scrollbar(graphics, choicesX + choicesWidth + 3, choicesTop, choicesBottom - choicesTop, choicesHeight, choicesScroll);
            if (builtChoices.timeoutTicks() > 0 && ClientDialogue.timerRunning()) {
                int barLeft = choicesX + 8;
                int barRight = choicesX + choicesWidth - 4;
                int barWidth = Math.max(20, barRight - barLeft);
                graphics.fill(barLeft, choicesTop - 5, barRight, choicesTop - 3, (0xB0 << 24) | UiPalette.SHADOW_DEEP);
                graphics.fill(barLeft, choicesTop - 5,
                        barLeft + Math.round(barWidth * builtChoices.timeLeft()), choicesTop - 3, DialogueChrome.GOLD);
            }
            for (ReplyButton button : optionButtons) {
                boolean hovered = mouseY >= choicesTop && mouseY < choicesBottom
                        && mouseX >= button.getX() && mouseX < button.getX() + button.getWidth()
                        && mouseY >= button.getY() && mouseY < button.getY() + button.getHeight();
                if (button.visible && button.hint != null && (hovered || button.isFocused())) {
                    graphics.renderTooltip(font, font.split(button.hint, Math.min(240, width - 30)),
                            hovered ? mouseX : button.getX() + 20, hovered ? mouseY : Math.min(button.getY(), choicesBottom - 8));
                }
            }
        }
    }

    private void renderLine(GuiGraphics graphics, MenuTheme theme) {
        if (portrait) {
            DialogueChrome.renderFrontPortrait(minecraft, graphics, builtLine,
                    panelX + PADDING, panelY + (panelHeight - DialogueChrome.PORTRAIT_SIZE) / 2, DialogueChrome.PORTRAIT_SIZE);
        }
        int nameY = textY - nameLines.size() * LINE_HEIGHT;
        for (FormattedCharSequence name : nameLines) {
            graphics.drawString(font, name, textX, nameY, theme.hudTitle(), true);
            nameY += LINE_HEIGHT;
        }
        int visible = builtLine.visibleChars();
        if (!manualTextScroll && !builtLine.isRevealed()) {
            int remaining = visible;
            int shownRows = 0;
            for (FormattedCharSequence row : textLines) {
                if (remaining <= 0) break;
                remaining -= DialogueText.length(row);
                shownRows++;
            }
            textScroll = Math.max(0, shownRows * LINE_HEIGHT - textHeight);
        }
        clip(graphics, textX, textY - 1, textX + textWidth + 2, textY + textHeight + 1);
        if (builtLine.isThinking()) {
            graphics.drawString(font, ThinkTime.dots(Util.getMillis()), textX, textY, theme.hudText(), true);
        } else {
            DialogueText.draw(graphics, font, textLines, textX, textY - textScroll, LINE_HEIGHT, theme.hudText(), true, visible);
        }
        graphics.disableScissor();
        DialogueChrome.scrollbar(graphics, textX + textWidth + 3, textY, textHeight, textLines.size() * LINE_HEIGHT, textScroll);
    }

    private void renderFooter(GuiGraphics graphics, MenuTheme theme) {
        if (ClientDialogue.chosen() != null) {
            return;
        }
        Component action;
        if (builtChoices != null) action = Component.translatable("storybound.dialogue.controls.choose", Math.min(9, optionButtons.size()));
        else if (builtLine != null && !builtLine.isRevealed()) action = Component.translatable("storybound.dialogue.controls.reveal");
        else if (builtLine != null && builtLine.timed()) action = Component.translatable("storybound.dialogue.controls.wait");
        else action = Component.translatable("storybound.dialogue.controls.continue");
        Component hint = action.copy();
        if (textLines.size() * LINE_HEIGHT > textHeight || choicesHeight > choicesBottom - choicesTop)
            hint = hint.copy().append(Component.translatable("storybound.dialogue.controls.scroll"));
        // From the prompt ESC goes back to it, whether the dialogue may be left or not.
        if (ClientDialogue.backToPrompt()) hint = hint.copy().append(Component.translatable("storybound.dialogue.controls.back"));
        else if (ClientDialogue.closable()) hint = hint.copy().append(Component.translatable("storybound.dialogue.controls.leave"));
        var lines = font.split(hint, width - MARGIN * 2);
        if (!lines.isEmpty()) {
            graphics.drawString(font, lines.getFirst(),
                    (width - font.width(lines.getFirst())) / 2, panelY + panelHeight + 4, theme.hudText(), true);
        }
    }

    private static int bottomOffset() {
        return ClientDialogue.backToPrompt() ? SUBTITLES_BOTTOM_OFFSET : BOTTOM_OFFSET;
    }

    private void clip(GuiGraphics graphics, int left, int top, int right, int bottom) {
        graphics.enableScissor(left, top, right, bottom);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fillGradient(0, height * 2 / 3, width, height, 0x00000000, 0x58000000);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (ClientDialogue.chosen() != null) {
            return true;
        }
        if (mouseY >= choicesTop && mouseY < choicesBottom && super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            proceed();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (!optionButtons.isEmpty() && x >= choicesX && x < choicesX + choicesWidth + 6
                && y >= choicesTop && y < choicesBottom) {
            choicesScroll = Math.clamp(choicesScroll - (int) (vertical * REPLY_LINE_HEIGHT * 2), 0, Math.max(0, choicesHeight - (choicesBottom - choicesTop)));
            positionChoices();
        } else if (x >= textX && x < textX + textWidth + 6 && y >= textY && y < textY + textHeight) {
            scrollText(-(int) vertical * LINE_HEIGHT * 2);
        }
        return true;
    }

    private void scrollText(int amount) {
        textScroll = Math.clamp(textScroll + amount, 0, Math.max(0, textLines.size() * LINE_HEIGHT - textHeight));
        manualTextScroll = true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            // Answers opened from the prompt in subtitles: back to it; otherwise ESC leaves (a closable dialogue).
            if (ClientDialogue.backToPrompt()) {
                ClientDialogue.hideChoices();
            } else {
                ClientDialogue.leave();
            }
            return true;
        }
        if (ClientDialogue.chosen() != null) {
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_PAGE_DOWN || keyCode == GLFW.GLFW_KEY_PAGE_UP) {
            scrollText((keyCode == GLFW.GLFW_KEY_PAGE_DOWN ? 1 : -1) * textHeight);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_TAB && !optionButtons.isEmpty() && (builtLine == null || builtLine.isRevealed())) {
            int direction = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0 ? -1 : 1;
            int index = optionButtons.indexOf(getFocused());
            if (index < 0) index = direction > 0 ? -1 : 0;
            for (int attempts = 0; attempts < optionButtons.size(); attempts++) {
                index = Math.floorMod(index + direction, optionButtons.size());
                ReplyButton candidate = optionButtons.get(index);
                if (candidate.active) {
                    setFocused(candidate);
                    revealChoice(candidate);
                    break;
                }
            }
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_SPACE || keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            if (getFocused() instanceof ReplyButton button && button.visible && button.active) button.onPress();
            else proceed();
            return true;
        }
        if (keyCode >= GLFW.GLFW_KEY_1 && keyCode <= GLFW.GLFW_KEY_9 && optionButtons.stream().anyMatch(b -> b.visible)) {
            chooseReply(keyCode - GLFW.GLFW_KEY_1);
            return true;
        }
        boolean handled = super.keyPressed(keyCode, scanCode, modifiers);
        if (getFocused() instanceof ReplyButton button) revealChoice(button);
        return handled;
    }

    private void revealChoice(ReplyButton button) {
        if (button.getHeight() > choicesBottom - choicesTop) choicesScroll += button.getY() - choicesTop;
        else if (button.getY() < choicesTop) choicesScroll -= choicesTop - button.getY();
        else if (button.getY() + button.getHeight() > choicesBottom) choicesScroll += button.getY() + button.getHeight() - choicesBottom;
        choicesScroll = Math.clamp(choicesScroll, 0, Math.max(0, choicesHeight - (choicesBottom - choicesTop)));
        positionChoices();
    }

    private void proceed() {
        ClientDialogue.Line line = ClientDialogue.line();
        if (line != null && !line.isRevealed()) line.revealAll();
        else ClientDialogue.advance();
    }

    private void chooseReply(int index) {
        if (selectedReply >= 0 || ClientDialogue.chosen() != null || builtChoices == null
                || index < 0 || index >= builtChoices.options().size()
                || !builtChoices.options().get(index).enabled()) return;
        selectedReply = index;
        ReplyButton button = index < optionButtons.size() ? optionButtons.get(index) : null;
        int bx = button != null ? button.getX() + 8 : choicesX + 8;
        int by = button != null ? button.getY() + 3 : choicesTop;
        ClientDialogue.choose(index, bx, by, choicesWidth - 8);
    }

    @Override
    public boolean shouldCloseOnEsc() { return false; }

    @Override
    public boolean isPauseScreen() { return false; }

    private final class ReplyButton extends Button {
        private final List<FormattedCharSequence> lines;
        private final Component hint;
        private final int number;
        private final int contentWidth;

        ReplyButton(int index, ClientDialogue.Option option) {
            super(choicesX, 0, choicesWidth, 16, Component.literal((index + 1) + ". ").append(option.text()),
                    pressed -> chooseReply(index), DEFAULT_NARRATION);
            number = index + 1;
            hint = option.hint();
            lines = font.split(option.text(), choicesWidth - 28);
            int maxLine = lines.stream().mapToInt(font::width).max().orElse(0);
            contentWidth = maxLine + 16;
            setHeight(Math.max(15, lines.size() * REPLY_LINE_HEIGHT + 4));
            active = option.enabled();
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            boolean highlighted = active && (isHoveredOrFocused() || selectedReply == number - 1);
            int textY = getY() + 3;
            int color;
            if (!active) {
                color = DialogueChrome.REPLY_LOCKED;
            } else if (highlighted) {
                color = DialogueChrome.GOLD;
            } else {
                color = MenuThemes.INSTANCE.theme().hudText();
            }
            if (highlighted) {
                DialogueChrome.choiceMarker(graphics, getX() + 3, textY + 2, 255);
            }
            graphics.drawString(font, number + ".", getX() + 8, textY, color, true);
            for (FormattedCharSequence line : lines) {
                graphics.drawString(font, line, getX() + 24, textY, color, true);
                textY += REPLY_LINE_HEIGHT;
            }
        }
    }
}
