package dev.dzenthai.storybound.client.menu;

import dev.dzenthai.storybound.client.StoryboundClient;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** The Adventure Menu: a row of tabs above one panel that shows the selected tab's view. */
public class AdventureMenuScreen extends Screen {
    private static final int MAX_WIDTH = 420;
    private static final int MAX_HEIGHT = 240;
    private static final int TAB_HEIGHT = 18;
    private static final int TAB_PADDING = 8;
    private static final int PANEL_PADDING = 8;

    private final Map<ResourceLocation, MenuView> views = new HashMap<>();
    private @Nullable ResourceLocation requested;
    private List<MenuTab> tabs = List.of();
    private @Nullable MenuTab current;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;

    /** @param tab the tab to show first; null or unavailable falls back to the first tab */
    public AdventureMenuScreen(@Nullable ResourceLocation tab) {
        super(Component.translatable("storybound.menu.title"));
        this.requested = tab;
    }

    @Override
    protected void init() {
        tabs = AdventureMenu.availableTabs();
        panelWidth = Math.min(MAX_WIDTH, width - 20);
        panelHeight = Math.min(MAX_HEIGHT, height - 20 - TAB_HEIGHT);
        panelX = (width - panelWidth) / 2;
        panelY = (height - panelHeight + TAB_HEIGHT) / 2;
        current = pickTab();
        if (current != null) {
            AdventureMenu.remember(current.id());
            view(current).init(this, panelX + PANEL_PADDING, panelY + PANEL_PADDING,
                    panelWidth - 2 * PANEL_PADDING, panelHeight - 2 * PANEL_PADDING);
        }
    }

    private @Nullable MenuTab pickTab() {
        ResourceLocation wanted = requested != null ? requested : current != null ? current.id() : null;
        requested = null;
        for (MenuTab tab : tabs) {
            if (tab.id().equals(wanted)) {
                return tab;
            }
        }
        return tabs.isEmpty() ? null : tabs.getFirst();
    }

    private MenuView view(MenuTab tab) {
        return views.computeIfAbsent(tab.id(), id -> tab.view().get());
    }

    public void showTab(ResourceLocation tab) {
        requested = tab;
        rebuildWidgets();
    }

    <T extends GuiEventListener & Renderable & NarratableEntry> T addViewWidget(T widget) {
        return addRenderableWidget(widget);
    }

    @Override
    public void tick() {
        // A module may have been switched off by the server while the menu was open.
        if (!AdventureMenu.availableTabs().equals(tabs)) {
            rebuildWidgets();
            return;
        }
        if (current != null) {
            view(current).tick();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        MenuTheme theme = MenuThemes.INSTANCE.theme();
        // Background, then tabs and panel, then the view and its widgets on top.
        renderBackground(graphics, mouseX, mouseY, partialTick);
        renderTabs(graphics, theme, mouseX, mouseY, false);
        graphics.blitSprite(theme.panel(), panelX, panelY, panelWidth, panelHeight);
        renderTabs(graphics, theme, mouseX, mouseY, true);
        if (current == null) {
            Component empty = Component.translatable("storybound.menu.empty");
            graphics.drawString(font, empty, panelX + (panelWidth - font.width(empty)) / 2, panelY + panelHeight / 2 - 4, theme.ink(), false);
        } else {
            view(current).render(graphics, mouseX, mouseY, partialTick);
        }
        for (Renderable renderable : renderables) {
            renderable.render(graphics, mouseX, mouseY, partialTick);
        }
    }

    /** Unselected tabs go behind the panel, the selected one in front so it joins the panel. */
    private void renderTabs(GuiGraphics graphics, MenuTheme theme, int mouseX, int mouseY, boolean selectedPass) {
        int x = panelX + 6;
        for (MenuTab tab : tabs) {
            int tabWidth = tabWidth(tab);
            boolean selected = tab == current;
            if (selected == selectedPass) {
                int y = panelY - TAB_HEIGHT + (selected ? 2 : 4);
                boolean hovered = !selected && isOverTab(mouseX, mouseY, x, tabWidth);
                graphics.blitSprite(selected ? theme.tabSelected() : theme.tab(), x, y, tabWidth, TAB_HEIGHT + (selected ? 0 : -2));
                int color = selected ? theme.title() : hovered ? theme.ink() : theme.muted();
                graphics.drawString(font, tab.title(), x + TAB_PADDING, y + 5, color, false);
                if (!selected && tab.unseen().getAsBoolean()) {
                    graphics.drawString(font, "•", x + tabWidth - 7, y + 2, theme.unseen(), false);
                }
            }
            x += tabWidth + 2;
        }
    }

    private int tabWidth(MenuTab tab) {
        return font.width(tab.title()) + 2 * TAB_PADDING;
    }

    private boolean isOverTab(double mouseX, double mouseY, int x, int tabWidth) {
        return mouseX >= x && mouseX < x + tabWidth && mouseY >= panelY - TAB_HEIGHT && mouseY < panelY;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int x = panelX + 6;
        for (MenuTab tab : tabs) {
            int tabWidth = tabWidth(tab);
            if (isOverTab(mouseX, mouseY, x, tabWidth)) {
                if (tab != current) {
                    showTab(tab.id());
                }
                return true;
            }
            x += tabWidth + 2;
        }
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        return current != null && view(current).mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return current != null && view(current).mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (StoryboundClient.OPEN_MENU.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
