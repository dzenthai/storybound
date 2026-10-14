package dev.dzenthai.storybound.client.menu;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;

/**
 * The content of one tab inside the menu panel. Lives as long as the opened menu, so the selection
 * survives switching tabs and returning from a document; {@link #init()} runs again on every resize.
 */
public abstract class MenuView {
    protected AdventureMenuScreen screen;
    protected Minecraft minecraft;
    protected Font font;
    protected int left;
    protected int top;
    protected int width;
    protected int height;

    final void init(AdventureMenuScreen screen, int left, int top, int width, int height) {
        this.screen = screen;
        this.minecraft = Minecraft.getInstance();
        this.font = minecraft.font;
        this.left = left;
        this.top = top;
        this.width = width;
        this.height = height;
        init();
    }

    /** Add widgets here with {@link #addWidget}; they are removed when the tab changes. */
    protected void init() {}

    public abstract void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick);

    public void tick() {}

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return false;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return false;
    }

    protected MenuTheme theme() {
        return MenuThemes.INSTANCE.theme();
    }

    protected <T extends GuiEventListener & Renderable & NarratableEntry> T addWidget(T widget) {
        return screen.addViewWidget(widget);
    }
}
