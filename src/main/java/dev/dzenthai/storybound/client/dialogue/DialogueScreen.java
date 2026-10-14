package dev.dzenthai.storybound.client.dialogue;

import dev.dzenthai.storybound.client.menu.MenuTheme;
import dev.dzenthai.storybound.client.menu.MenuThemes;
import dev.dzenthai.storybound.module.dialogue.ThinkTime;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.lwjgl.glfw.GLFW;

/**
 * The visual-novel screen: the speaker's portrait and name with the line in a panel at the bottom,
 * options above it. Does not pause the game. Click, Space or Enter print the line at once, then move on;
 * 1–9 pick an option; ESC leaves a closable dialogue.
 */
public final class DialogueScreen extends Screen {
    private static final int MARGIN = 8;
    private static final int PANEL_HEIGHT = 86;
    private static final int PADDING = 9;
    private static final int PORTRAIT = 64;
    private static final int LINE_HEIGHT = 11;
    private static final int OPTION_HEIGHT = 20;
    private static final int OPTION_GAP = 3;
    private static final int MAX_OPTION_WIDTH = 320;

    private final List<Button> optionButtons = new ArrayList<>();
    private int builtForStep = -1;

    public DialogueScreen() {
        super(Component.translatable("storybound.dialogue.title"));
    }

    @Override
    protected void init() {
        optionButtons.clear();
        ClientDialogue.Choices choices = ClientDialogue.choices();
        builtForStep = choices != null ? choices.step() : -1;
        if (choices == null) {
            return;
        }
        int width = Math.min(MAX_OPTION_WIDTH, this.width - 2 * MARGIN);
        int x = (this.width - width) / 2;
        int bottom = panelTop() - 6;
        int count = choices.options().size();
        for (int i = 0; i < count; i++) {
            ClientDialogue.Option option = choices.options().get(i);
            int index = i;
            int y = bottom - (count - i) * (OPTION_HEIGHT + OPTION_GAP);
            Component label = Component.literal((i + 1) + ". ").append(option.text());
            Button button = Button.builder(label, pressed -> ClientDialogue.choose(index)).bounds(x, y, width, OPTION_HEIGHT).build();
            button.active = option.enabled();
            if (option.hint() != null) {
                button.setTooltip(Tooltip.create(option.hint()));
            }
            optionButtons.add(addRenderableWidget(button));
        }
    }

    @Override
    public void tick() {
        ClientDialogue.Choices choices = ClientDialogue.choices();
        int step = choices != null ? choices.step() : -1;
        if (step != builtForStep) {
            rebuildWidgets();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        MenuTheme theme = MenuThemes.INSTANCE.theme();
        ClientDialogue.Line line = ClientDialogue.line();
        boolean revealed = line == null || line.isRevealed();
        // Options appear once the line before them is fully printed.
        optionButtons.forEach(button -> button.visible = revealed);
        super.render(graphics, mouseX, mouseY, partialTick);

        int top = panelTop();
        int left = MARGIN;
        int right = width - MARGIN;
        if (line != null) {
            graphics.blitSprite(theme.panel(), left, top, right - left, PANEL_HEIGHT);
            int textX = left + PADDING;
            if (renderPortrait(graphics, line, left + PADDING, top + (PANEL_HEIGHT - PORTRAIT) / 2)) {
                textX += PORTRAIT + PADDING;
            }
            int textWidth = right - PADDING - textX;
            int y = top + PADDING;
            if (!line.name().getString().isEmpty()) {
                graphics.drawString(font, line.name(), textX, y, theme.title(), false);
                y += LINE_HEIGHT + 2;
            }
            List<FormattedCharSequence> lines = font.split(line.text(), textWidth);
            if (line.isThinking()) {
                graphics.drawString(font, ThinkTime.dots(Util.getMillis()), textX, y, theme.muted(), false);
            } else {
                DialogueText.draw(graphics, font, lines, textX, y, LINE_HEIGHT, theme.ink(), false, line.visibleChars());
            }
            if (revealed && ClientDialogue.choices() == null && (Util.getMillis() / 400) % 2 == 0) {
                graphics.drawString(font, "▼", right - PADDING - 6, top + PANEL_HEIGHT - PADDING - 8, theme.accent(), false);
            }
        }
        ClientDialogue.Choices choices = ClientDialogue.choices();
        if (choices != null && choices.timeoutTicks() > 0 && revealed && !optionButtons.isEmpty()) {
            Button first = optionButtons.getFirst();
            int barWidth = Math.round(first.getWidth() * choices.timeLeft());
            int barY = first.getY() - 5;
            graphics.fill(first.getX(), barY, first.getX() + first.getWidth(), barY + 2, 0x60000000);
            graphics.fill(first.getX(), barY, first.getX() + barWidth, barY + 2, theme.accent());
        }
    }

    // The world stays visible: only the bottom is darkened, no blur.
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fillGradient(0, height / 2, width, height, 0x00000000, 0x90000000);
    }

    /** @return false when there is nothing to show (no entity in view and no texture) */
    private boolean renderPortrait(GuiGraphics graphics, ClientDialogue.Line line, int x, int y) {
        ResourceLocation texture = line.speaker().portrait().orElse(null);
        if (texture != null) {
            graphics.blit(texture, x, y, 0, 0, PORTRAIT, PORTRAIT, PORTRAIT, PORTRAIT);
            return true;
        }
        Entity entity = minecraft != null && minecraft.level != null && line.speaker().entityId() >= 0
                ? minecraft.level.getEntity(line.speaker().entityId()) : null;
        if (!(entity instanceof LivingEntity living)) {
            return false;
        }
        graphics.fill(x, y, x + PORTRAIT, y + PORTRAIT, 0x40000000);
        // A bust: about 1.1 entity-scaled blocks around the eyes fill the frame.
        float scale = living.getScale();
        float yOffset = (living.getEyeHeight() - living.getBbHeight() / 2f) / scale - 0.15f;
        InventoryScreen.renderEntityInInventoryFollowsAngle(graphics, x, y, x + PORTRAIT, y + PORTRAIT,
                Math.round(PORTRAIT / 1.1f), yOffset, 0.35f, 0f, living);
        return true;
    }

    private int panelTop() {
        return height - MARGIN - PANEL_HEIGHT;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            proceed();
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            ClientDialogue.leave();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_SPACE || keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            proceed();
            return true;
        }
        if (keyCode >= GLFW.GLFW_KEY_1 && keyCode <= GLFW.GLFW_KEY_9 && optionButtons.stream().anyMatch(b -> b.visible)) {
            ClientDialogue.choose(keyCode - GLFW.GLFW_KEY_1);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** First press prints the whole line, the next one moves on. */
    private void proceed() {
        ClientDialogue.Line line = ClientDialogue.line();
        if (line != null && !line.isRevealed()) {
            line.revealAll();
        } else {
            ClientDialogue.advance();
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
