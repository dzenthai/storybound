package dev.dzenthai.storybound.client.menu;

import dev.dzenthai.storybound.client.StoryboundClient;
import dev.dzenthai.storybound.client.dev.DevModeScreen;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.util.FormattedCharSequence;
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
    private final Map<ResourceLocation, RibbonMotion> ribbons = new HashMap<>();
    private final Map<ResourceLocation, RibbonMotion> ribbonJoins = new HashMap<>();
    private @Nullable ResourceLocation requested;
    private List<MenuTab> tabs = List.of();
    private @Nullable MenuTab current;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int tabHeight;
    private int panelPadding;
    private int headingHeight;
    private @Nullable JournalLayout bookLayout;
    private int themeRevision;
    private @Nullable BookViewport viewport;
    private @Nullable List<FormattedCharSequence> bookTooltip;
    private @Nullable ClientTooltipPositioner bookTooltipPositioner;

    /** @param tab the tab to show first; null or unavailable falls back to the first tab */
    public AdventureMenuScreen(@Nullable ResourceLocation tab) {
        super(Component.translatable("storybound.menu.title"));
        this.requested = tab;
    }

    @Override
    protected void init() {
        var window = minecraft.getWindow();
        viewport = MenuThemes.INSTANCE.journalBook()
                ? BookViewport.wholePixels(window.getWidth(), window.getHeight(), window.getGuiScale(), JournalSkin.BOOK_WIDTH,
                        JournalLayout.fit(BookViewport.WIDTH, BookViewport.HEIGHT)) : null;
        width = viewport == null ? window.getGuiScaledWidth() : BookViewport.WIDTH;
        height = viewport == null ? window.getGuiScaledHeight() : BookViewport.HEIGHT;
        bookTooltip = null;
        bookTooltipPositioner = null;
        tabs = AdventureMenu.availableTabs();
        boolean archive = MenuThemes.INSTANCE.archiveChrome();
        tabHeight = archive ? 32 : TAB_HEIGHT;
        panelPadding = archive ? 16 : PANEL_PADDING;
        headingHeight = archive && height >= 240 ? 28 : 0;
        panelWidth = Math.min(archive ? 520 : MAX_WIDTH, width - 20);
        panelHeight = Math.min(archive ? 300 : MAX_HEIGHT, height - 20 - tabHeight);
        panelX = (width - panelWidth) / 2;
        panelY = (height - panelHeight + tabHeight) / 2;
        bookLayout = MenuThemes.INSTANCE.journalBook() ? JournalLayout.fit(width, height) : null;
        themeRevision = MenuThemes.INSTANCE.revision();
        current = pickTab();
        if (bookLayout != null) {
            for (int i = 0; i < tabs.size(); i++) {
                MenuTab tab = tabs.get(i);
                addRenderableWidget(new JournalSkin.Bookmark(tab, tab == current, ribbons.computeIfAbsent(tab.id(), id -> new RibbonMotion()),
                        ribbonJoins.computeIfAbsent(tab.id(), id -> new RibbonMotion()),
                        bookLayout.bookmark(i, tabs.size()), button -> {
                            if (tab != current) showTab(tab.id());
                        }));
            }
        }
        if (current != null) {
            AdventureMenu.remember(current.id());
            if (bookLayout != null) {
                JournalLayout.Rect content = bookLayout.content();
                view(current).init(this, content.x(), content.y(), content.width(), content.height());
            } else {
                view(current).init(this, panelX + panelPadding, panelY + panelPadding + headingHeight,
                        panelWidth - 2 * panelPadding, panelHeight - 2 * panelPadding - headingHeight);
            }
        }
        if (minecraft != null && minecraft.player != null && minecraft.player.hasPermissions(2)) {
            int right = viewport == null ? width : (int) Math.floor(viewport.toBookX(window.getGuiScaledWidth()));
            int bottom = viewport == null ? height : (int) Math.floor(viewport.toBookY(window.getGuiScaledHeight()));
            addRenderableWidget(new ArchiveChrome.ArchiveButton(right - 88, bottom - 26, 80, 18,
                    Component.literal("⚙ Dev Mode"), b -> minecraft.setScreen(new DevModeScreen(this))));
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

    public @Nullable JournalLayout bookLayout() {
        return bookLayout;
    }

    public @Nullable BookViewport bookViewport() { return viewport; }

    @Override
    public void setTooltipForNextRenderPass(List<FormattedCharSequence> lines, ClientTooltipPositioner positioner, boolean override) {
        if (viewport == null) {
            super.setTooltipForNextRenderPass(lines, positioner, override);
        } else if (bookTooltip == null || override) {
            bookTooltip = lines;
            bookTooltipPositioner = positioner;
        }
    }

    <T extends GuiEventListener & Renderable & NarratableEntry> T addViewWidget(T widget) {
        return addRenderableWidget(widget);
    }

    @Override
    public void tick() {
        // A module may have been switched off by the server while the menu was open.
        if (!AdventureMenu.availableTabs().equals(tabs) || themeRevision != MenuThemes.INSTANCE.revision()) {
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
        int canvasWidth = width;
        int canvasHeight = height;
        width = minecraft.getWindow().getGuiScaledWidth();
        height = minecraft.getWindow().getGuiScaledHeight();
        renderBackground(graphics, mouseX, mouseY, partialTick);
        if (bookLayout != null) graphics.fill(0, 0, width, height, 0x450E1713);
        width = canvasWidth;
        height = canvasHeight;
        if (bookLayout != null) {
            graphics.flush();
            graphics = new BookGuiGraphics(minecraft, graphics, viewport);
            mouseX = (int) viewport.toBookX(mouseX);
            mouseY = (int) viewport.toBookY(mouseY);
            JournalSkin.book(graphics, bookLayout);
            for (Renderable renderable : renderables) {
                if (renderable instanceof JournalSkin.Bookmark) renderable.render(graphics, mouseX, mouseY, partialTick);
            }
            // Pages occlude the ribbon roots, while the ribbons cover the binding.
            graphics.enableScissor(bookLayout.x(), bookLayout.y(), bookLayout.pageEdge(), bookLayout.y() + bookLayout.height());
            JournalSkin.book(graphics, bookLayout);
            graphics.disableScissor();
            if (current != null) {
                for (var renderable : renderables) {
                    if (renderable instanceof JournalSkin.Bookmark bookmark) {
                        bookmark.renderPageJoin(graphics, mouseX, mouseY, partialTick);
                    }
                }
                JournalSkin.title(graphics, font, current.title().copy().withStyle(net.minecraft.ChatFormatting.BOLD),
                        bookLayout.content().x(), bookLayout.titleY(), bookLayout.indexWidth(),
                        bookLayout.compact() ? 1.2f : 1.7f, theme.title());
                view(current).render(graphics, mouseX, mouseY, partialTick);
            } else {
                Component empty = JournalSkin.text(Component.translatable("storybound.menu.empty"));
                int y = bookLayout.content().y();
                for (var line : font.split(empty, bookLayout.content().right() - bookLayout.detailLeft())) {
                    graphics.drawString(font, line, bookLayout.detailLeft(), y, theme.ink(), false);
                    y += 13;
                }
            }
            for (Renderable renderable : renderables) {
                if (!(renderable instanceof JournalSkin.Bookmark)) renderable.render(graphics, mouseX, mouseY, partialTick);
            }
            if (bookTooltip != null) {
                graphics.renderTooltip(font, bookTooltip, bookTooltipPositioner, mouseX, mouseY);
                bookTooltip = null;
            }
            graphics.flush();
            return;
        }
        if (MenuThemes.INSTANCE.archiveChrome()) {
            graphics.fill(0, 0, width, height, 0x380A090D);
        }
        renderTabs(graphics, theme, mouseX, mouseY, false);
        if (MenuThemes.INSTANCE.archiveChrome()) {
            ArchiveChrome.panel(graphics, panelX, panelY, panelWidth, panelHeight, theme);
            if (headingHeight > 0) {
                graphics.drawString(font, "STORYBOUND", panelX + panelPadding, panelY + 15, theme.accent(), false);
                if (current != null) {
                    graphics.drawString(font, current.title(), panelX + panelWidth - panelPadding - font.width(current.title()),
                            panelY + 15, theme.muted(), false);
                }
                graphics.fill(panelX + panelPadding, panelY + 32, panelX + panelWidth - panelPadding, panelY + 33, theme.divider());
            }
        } else {
            graphics.blitSprite(theme.panel(), panelX, panelY, panelWidth, panelHeight);
        }
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
                int y = panelY - tabHeight + (selected ? 2 : 4);
                boolean hovered = !selected && isOverTab(mouseX, mouseY, x, tabWidth);
                if (MenuThemes.INSTANCE.archiveChrome()) {
                    ArchiveChrome.tab(graphics, x, y, tabWidth, tabHeight + (selected ? 0 : -2), selected, hovered, theme);
                } else {
                    graphics.blitSprite(selected ? theme.tabSelected() : theme.tab(), x, y, tabWidth, tabHeight + (selected ? 0 : -2));
                }
                int color = selected ? theme.title() : hovered ? theme.ink() : theme.muted();
                if (MenuThemes.INSTANCE.archiveChrome() && MenuThemes.INSTANCE.parchment()) {
                    color = selected || hovered ? 0xFFF1E4CB : 0xFFD3BE9E;
                }
                if (MenuThemes.INSTANCE.archiveChrome()) {
                    boolean icon = tabWidth >= 64;
                    if (icon) {
                        ArchiveChrome.icon(graphics, tab.id(), x + 9, y + 9, theme);
                    }
                    int textX = x + (icon ? 28 : TAB_PADDING);
                    graphics.drawString(font, font.plainSubstrByWidth(tab.title().getString(), tabWidth - (icon ? 36 : 16)),
                            textX, y + 12, color, false);
                } else {
                    graphics.drawString(font, tab.title(), x + TAB_PADDING, y + 5, color, false);
                }
                if (!selected && tab.unseen().getAsBoolean()) {
                    graphics.drawString(font, "•", x + tabWidth - 7, y + 2, theme.unseen(), false);
                }
            }
            x += tabWidth + 2;
        }
    }

    private int tabWidth(MenuTab tab) {
        if (MenuThemes.INSTANCE.archiveChrome()) {
            return Math.max(1, (panelWidth - 12 - Math.max(0, tabs.size() - 1) * 2) / Math.max(1, tabs.size()));
        }
        return font.width(tab.title()) + 2 * TAB_PADDING;
    }

    private boolean isOverTab(double mouseX, double mouseY, int x, int tabWidth) {
        return mouseX >= x && mouseX < x + tabWidth && mouseY >= panelY - tabHeight && mouseY < panelY;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (bookLayout != null) {
            mouseX = viewport.toBookX(mouseX);
            mouseY = viewport.toBookY(mouseY);
            return super.mouseClicked(mouseX, mouseY, button)
                    || current != null && view(current).mouseClicked(mouseX, mouseY, button);
        }
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
        if (viewport != null) {
            mouseX = viewport.toBookX(mouseX);
            mouseY = viewport.toBookY(mouseY);
        }
        return current != null && view(current).mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (viewport != null) {
            mouseX = viewport.toBookX(mouseX);
            mouseY = viewport.toBookY(mouseY);
            dragX /= viewport.scale();
            dragY /= viewport.scale();
        }
        return current != null && view(current).mouseDragged(mouseX, mouseY, button, dragX, dragY)
                || super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (viewport != null) {
            mouseX = viewport.toBookX(mouseX);
            mouseY = viewport.toBookY(mouseY);
        }
        boolean handled = current != null && view(current).mouseReleased(mouseX, mouseY, button);
        return super.mouseReleased(mouseX, mouseY, button) || handled;
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        super.mouseMoved(viewport == null ? mouseX : viewport.toBookX(mouseX),
                viewport == null ? mouseY : viewport.toBookY(mouseY));
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
