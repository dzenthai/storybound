package dev.dzenthai.storybound.client.dialogue;

import dev.dzenthai.storybound.module.dialogue.ThinkTime;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.lwjgl.glfw.GLFW;

/** A paper conversation overlay. Session state, replies and camera remain owned by ClientDialogue. */
public final class DialogueScreen extends Screen {
    private static final int MARGIN = 18;
    private static final int PADDING = 12;
    private static final int PORTRAIT = 44;
    private static final int LINE_HEIGHT = 12;
    private static final int OPTION_GAP = 3;
    private final List<ReplyButton> optionButtons = new ArrayList<>();
    private final long animationStart = Util.getMillis();
    private ClientDialogue.Line builtLine;
    private ClientDialogue.Choices builtChoices;
    private List<FormattedCharSequence> textLines = List.of();
    private List<FormattedCharSequence> nameLines = List.of();
    private double guiScale = 1;
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
        var window = minecraft.getWindow();
        // Integer physical text pixels remain stable when the HUD GUI scale changes.
        int physicalScale = Math.max(1, (int) Math.round(window.getHeight() / 500.0));
        guiScale = physicalScale / window.getGuiScale();
        width = (int) Math.floor(window.getGuiScaledWidth() / guiScale);
        height = (int) Math.floor(window.getGuiScaledHeight() / guiScale);
        builtLine = ClientDialogue.line();
        builtChoices = ClientDialogue.choices();
        textScroll = 0;
        manualTextScroll = false;
        choicesScroll = 0;
        selectedReply = -1;
        optionButtons.clear();
        panelWidth = Math.min(640, (width - MARGIN * 2) * 70 / 100);
        panelX = (width - panelWidth) / 2;
        portrait = builtLine != null && hasPortrait(builtLine);
        textX = panelX + PADDING + (portrait ? PORTRAIT + 12 : 0);
        textWidth = Math.max(40, panelX + panelWidth - PADDING - 8 - textX);
        nameLines = builtLine == null ? List.of() : font.split(builtLine.name().copy().withStyle(style -> style.withBold(true)), textWidth);
        if (builtLine == null || builtLine.name().getString().isEmpty()) nameLines = List.of();
        textLines = builtLine == null ? List.of() : font.split(builtLine.text(), textWidth);
        int heading = nameLines.isEmpty() ? 0 : nameLines.size() * LINE_HEIGHT + 10;
        int desiredHeight = PADDING * 2 + Math.max(portrait ? PORTRAIT : 0,
                heading + Math.max(2, textLines.size()) * LINE_HEIGHT) + 22;
        panelHeight = Math.min(Math.max(94, desiredHeight), Math.max(94, height / 3));
        panelY = height - MARGIN - panelHeight;
        textY = panelY + PADDING + heading;
        int footerTop = panelY + panelHeight - 22;
        int bodyBottom = footerTop - 6;
        textHeight = Math.max(LINE_HEIGHT, ((bodyBottom - textY) / LINE_HEIGHT) * LINE_HEIGHT);
        choicesWidth = Math.min(250, panelWidth);
        choicesX = panelX + panelWidth - choicesWidth;
        choicesBottom = builtLine == null ? height - MARGIN : panelY - 6;
        choicesTop = choicesBottom;
        choicesHeight = 0;
        if (builtChoices != null) {
            for (int i = 0; i < builtChoices.options().size(); i++) {
                ClientDialogue.Option option = builtChoices.options().get(i);
                ReplyButton button = new ReplyButton(i, option);
                optionButtons.add(addRenderableWidget(button));
                choicesHeight += button.getHeight() + OPTION_GAP;
            }
            choicesHeight = Math.max(0, choicesHeight - OPTION_GAP);
            int viewportHeight = Math.min(140, Math.max(60, height * 30 / 100));
            choicesTop = Math.max(MARGIN + 6, choicesBottom - Math.min(choicesHeight, viewportHeight));
            positionChoices();
        }
    }

    private boolean hasPortrait(ClientDialogue.Line line) {
        if (line.speaker().portrait().isPresent()) return true;
        return minecraft.level != null && line.speaker().entityId() >= 0
                && minecraft.level.getEntity(line.speaker().entityId()) instanceof LivingEntity;
    }

    private void positionChoices() {
        int y = choicesTop - choicesScroll;
        boolean revealed = builtLine == null || builtLine.isRevealed();
        for (ReplyButton button : optionButtons) {
            button.setY(y);
            button.visible = revealed && y < choicesBottom && y + button.getHeight() > choicesTop;
            y += button.getHeight() + OPTION_GAP;
        }
    }

    @Override
    public void tick() {
        if (ClientDialogue.line() != builtLine || ClientDialogue.choices() != builtChoices) rebuildWidgets();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (ClientDialogue.line() != builtLine || ClientDialogue.choices() != builtChoices) rebuildWidgets();
        int localX = (int) Math.floor(mouseX / guiScale);
        int localY = (int) Math.floor(mouseY / guiScale);
        graphics.flush();
        graphics = new DialogueGraphics(minecraft, graphics, guiScale, width, height);
        renderBackground(graphics, localX, localY, partialTick);
        if (builtLine != null) {
            DialogueChrome.nebula(graphics, panelX, panelY, panelWidth, panelHeight, Util.getMillis() - animationStart);
            DialogueChrome.paper(graphics, panelX, panelY, panelWidth, panelHeight, false);
            renderLine(graphics);
            renderFooter(graphics);
        }
        positionChoices();
        if (!optionButtons.isEmpty() && (builtLine == null || builtLine.isRevealed())) {
            clip(graphics, choicesX - 1, choicesTop, choicesX + choicesWidth + 1, choicesBottom);
            for (ReplyButton button : optionButtons) {
                button.render(graphics, localX, localY >= choicesTop && localY < choicesBottom ? localY : -1, partialTick);
            }
            graphics.disableScissor();
            DialogueChrome.scrollbar(graphics, choicesX + choicesWidth + 3, choicesTop, choicesBottom - choicesTop, choicesHeight, choicesScroll);
            if (builtChoices.timeoutTicks() > 0) {
                graphics.fill(choicesX + 8, choicesTop - 4, choicesX + choicesWidth - 8, choicesTop - 3, 0x5079533F);
                graphics.fill(choicesX + 8, choicesTop - 4,
                        choicesX + 8 + Math.round((choicesWidth - 16) * builtChoices.timeLeft()), choicesTop - 3, DialogueChrome.RULE);
            }
            for (ReplyButton button : optionButtons) {
                boolean hovered = localY >= choicesTop && localY < choicesBottom
                        && localX >= button.getX() && localX < button.getX() + button.getWidth()
                        && localY >= button.getY() && localY < button.getY() + button.getHeight();
                if (button.visible && button.hint != null && (hovered || button.isFocused())) {
                    graphics.renderTooltip(font, font.split(button.hint, Math.min(240, width - 30)),
                            hovered ? localX : button.getX() + 20, hovered ? localY : Math.min(button.getY(), choicesBottom - 8));
                }
            }
        }
        graphics.flush();
    }

    private void renderLine(GuiGraphics graphics) {
        if (portrait) renderPortrait(graphics, builtLine, panelX + PADDING, panelY + PADDING);
        int nameY = panelY + PADDING;
        for (FormattedCharSequence name : nameLines) {
            graphics.drawString(font, name, textX, nameY, DialogueChrome.INK, false);
            nameY += LINE_HEIGHT;
        }
        if (!nameLines.isEmpty()) DialogueChrome.rule(graphics, textX, textY - 7, textWidth);
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
        clip(graphics, textX, textY - 1, textX + textWidth, textY + textHeight);
        if (builtLine.isThinking()) {
            graphics.drawString(font, ThinkTime.dots(Util.getMillis()), textX, textY, DialogueChrome.MUTED, false);
        } else {
            DialogueText.draw(graphics, font, textLines, textX, textY - textScroll, LINE_HEIGHT, DialogueChrome.INK, false, visible);
        }
        graphics.disableScissor();
        DialogueChrome.scrollbar(graphics, textX + textWidth + 3, textY, textHeight, textLines.size() * LINE_HEIGHT, textScroll);
    }

    private void renderFooter(GuiGraphics graphics) {
        int y = panelY + panelHeight - 22;
        graphics.fill(panelX + PADDING, y, panelX + panelWidth - PADDING, y + 1, DialogueChrome.RULE);
        Component action;
        if (builtChoices != null) action = Component.translatable("storybound.dialogue.controls.choose", Math.min(9, optionButtons.size()));
        else if (builtLine != null && !builtLine.isRevealed()) action = Component.translatable("storybound.dialogue.controls.reveal");
        else if (builtLine != null && builtLine.timed()) action = Component.translatable("storybound.dialogue.controls.wait");
        else action = Component.translatable("storybound.dialogue.controls.continue");
        Component hint = action.copy();
        if (textLines.size() * LINE_HEIGHT > textHeight || choicesHeight > choicesBottom - choicesTop)
            hint = hint.copy().append(Component.translatable("storybound.dialogue.controls.scroll"));
        if (ClientDialogue.closable()) hint = hint.copy().append(Component.translatable("storybound.dialogue.controls.leave"));
        var lines = font.split(hint, panelWidth - PADDING * 2);
        if (!lines.isEmpty()) graphics.drawString(font, lines.getFirst(), panelX + (panelWidth - font.width(lines.getFirst())) / 2,
                y + 7, DialogueChrome.MUTED, false);
    }

    private void clip(GuiGraphics graphics, int left, int top, int right, int bottom) {
        graphics.enableScissor(left, top, right, bottom);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fillGradient(0, height / 2, width, height, 0x00000000, 0x70000000);
    }

    private void renderPortrait(GuiGraphics graphics, ClientDialogue.Line line, int x, int y) {
        DialogueChrome.portrait(graphics, x, y, PORTRAIT);
        ResourceLocation texture = line.speaker().portrait().orElse(null);
        if (texture != null) {
            graphics.blit(texture, x + 2, y + 2, PORTRAIT - 4, PORTRAIT - 4, 0, 0, 64, 64, 64, 64);
            return;
        }
        Entity entity = minecraft.level != null ? minecraft.level.getEntity(line.speaker().entityId()) : null;
        if (entity instanceof LivingEntity living) {
            float scale = living.getScale();
            float yOffset = (living.getEyeHeight() - living.getBbHeight() / 2f) / scale - 0.15f;
            InventoryScreen.renderEntityInInventoryFollowsAngle(graphics, x + 2, y + 2, x + PORTRAIT - 2, y + PORTRAIT - 2,
                    Math.round((PORTRAIT - 4) / 1.1f), yOffset, 0.35f, 0f, living);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        double x = mouseX / guiScale;
        double y = mouseY / guiScale;
        if (y >= choicesTop && y < choicesBottom && super.mouseClicked(x, y, button)) return true;
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            proceed();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        return super.mouseReleased(x / guiScale, y / guiScale, button);
    }

    @Override
    public void mouseMoved(double x, double y) {
        super.mouseMoved(x / guiScale, y / guiScale);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        double localX = x / guiScale;
        double localY = y / guiScale;
        if (!optionButtons.isEmpty() && localX >= choicesX && localX < choicesX + choicesWidth + 6
                && localY >= choicesTop && localY < choicesBottom) {
            choicesScroll = Math.clamp(choicesScroll - (int) (vertical * LINE_HEIGHT * 2), 0, Math.max(0, choicesHeight - (choicesBottom - choicesTop)));
            positionChoices();
        } else if (localX >= textX && localX < textX + textWidth + 6 && localY >= textY && localY < textY + textHeight) {
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
            ClientDialogue.leave();
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
        if (selectedReply >= 0 || builtChoices == null || index < 0 || index >= builtChoices.options().size()
                || !builtChoices.options().get(index).enabled()) return;
        selectedReply = index;
        ClientDialogue.choose(index);
    }

    @Override
    public boolean shouldCloseOnEsc() { return false; }

    @Override
    public boolean isPauseScreen() { return false; }

    /** Portrait helpers, tooltips and text share the same transformed clip space. */
    private static final class DialogueGraphics extends GuiGraphics {
        private final double scale;
        private final int width;
        private final int height;

        DialogueGraphics(Minecraft minecraft, GuiGraphics source, double scale, int width, int height) {
            super(minecraft, source.bufferSource());
            this.scale = scale;
            this.width = width;
            this.height = height;
            pose().scale((float) scale, (float) scale, 1);
        }

        @Override public int guiWidth() { return width; }
        @Override public int guiHeight() { return height; }

        @Override
        public void enableScissor(int left, int top, int right, int bottom) {
            super.enableScissor((int) Math.floor(left * scale), (int) Math.floor(top * scale),
                    (int) Math.ceil(right * scale), (int) Math.ceil(bottom * scale));
        }

        @Override
        public boolean containsPointInScissor(int x, int y) {
            return super.containsPointInScissor((int) (x * scale), (int) (y * scale));
        }
    }

    private final class ReplyButton extends Button {
        private final List<FormattedCharSequence> lines;
        private final Component hint;
        private final int number;

        ReplyButton(int index, ClientDialogue.Option option) {
            super(choicesX, 0, choicesWidth, 24, Component.literal((index + 1) + ". ").append(option.text()),
                    pressed -> chooseReply(index), DEFAULT_NARRATION);
            number = index + 1;
            hint = option.hint();
            lines = font.split(option.text(), choicesWidth - 57);
            setHeight(Math.max(24, lines.size() * LINE_HEIGHT + 12));
            active = option.enabled();
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            boolean selected = selectedReply == number - 1;
            DialogueChrome.paper(graphics, getX(), getY(), getWidth(), getHeight(), true);
            if (!active) graphics.fill(getX() + 4, getY() + 3, getX() + getWidth() - 4, getY() + getHeight() - 3, 0x25A39984);
            else if (isHoveredOrFocused() || selected) graphics.fill(getX() + 4, getY() + 3,
                    getX() + getWidth() - 4, getY() + getHeight() - 3, 0x30D99527);
            int textY = getY() + (getHeight() - lines.size() * LINE_HEIGHT) / 2 + 2;
            int color = active ? DialogueChrome.INK : DialogueChrome.MUTED;
            if (textY >= choicesTop && textY + font.lineHeight <= choicesBottom)
                graphics.drawString(font, number + ".", getX() + 12, textY, color, false);
            for (FormattedCharSequence line : lines) {
                if (textY >= choicesTop && textY + font.lineHeight <= choicesBottom)
                    graphics.drawString(font, line, getX() + 29, textY, color, false);
                textY += LINE_HEIGHT;
            }
            if (getY() >= choicesTop && getY() + getHeight() <= choicesBottom) {
                if (!active) DialogueChrome.lock(graphics, getX() + getWidth() - 20, getY() + (getHeight() - 10) / 2);
                else if (isHoveredOrFocused() || selected) DialogueChrome.arrow(graphics, getX() + getWidth() - 17,
                        getY() + (getHeight() - 7) / 2, false, color);
            }
        }
    }
}
