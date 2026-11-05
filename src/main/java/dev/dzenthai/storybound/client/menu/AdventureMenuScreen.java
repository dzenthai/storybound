package dev.dzenthai.storybound.client.menu;

import dev.dzenthai.storybound.client.StoryboundClient;
import dev.dzenthai.storybound.client.dev.DevModeScreen;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * The Adventure Menu: the book, its bookmarks the tabs, the selected tab's view on its pages (November 4, 2026; before,
 * a theme could also turn the book off for a panel with tabs drawn from sprites, which no theme used any more).
 */
public class AdventureMenuScreen extends Screen {
    private final Map<ResourceLocation, MenuView> views = new HashMap<>();
    private final Map<ResourceLocation, RibbonMotion> ribbons = new HashMap<>();
    private final Map<ResourceLocation, RibbonMotion> ribbonJoins = new HashMap<>();
    private @Nullable ResourceLocation requested;
    private List<MenuTab> tabs = List.of();
    private @Nullable MenuTab current;
    private JournalLayout bookLayout = JournalLayout.fit(BookViewport.MENU_WIDTH, BookViewport.MENU_HEIGHT);
    private int themeRevision;
    private BookViewport viewport = new BookViewport(1, 0, 0);
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
        viewport = BookViewport.wholePixels(window.getWidth(), window.getHeight(), window.getGuiScale(), JournalSkin.BOOK_WIDTH,
                JournalLayout.fit(BookViewport.MENU_WIDTH, BookViewport.MENU_HEIGHT));
        width = viewport.width();
        height = viewport.height();
        bookTooltip = null;
        bookTooltipPositioner = null;
        tabs = AdventureMenu.availableTabs();
        bookLayout = JournalLayout.fit(width, height);
        themeRevision = MenuThemes.INSTANCE.revision();
        current = pickTab();
        for (int i = 0; i < tabs.size(); i++) {
            MenuTab tab = tabs.get(i);
            addRenderableWidget(new JournalSkin.Bookmark(tab, tab == current, ribbons.computeIfAbsent(tab.id(), id -> new RibbonMotion()),
                    ribbonJoins.computeIfAbsent(tab.id(), id -> new RibbonMotion()),
                    bookLayout.bookmark(i, tabs.size()), bookLayout.bookmarkPixelScale(), button -> {
                        if (tab != current) showTab(tab.id());
                    }));
        }
        if (current != null) {
            AdventureMenu.remember(current.id());
            JournalLayout.Rect content = bookLayout.content();
            view(current).init(this, content.x(), content.y(), content.width(), content.height());
        }
        if (minecraft != null && minecraft.player != null && minecraft.player.hasPermissions(2)) {
            int right = (int) Math.floor(viewport.toBookX(window.getGuiScaledWidth()));
            int bottom = (int) Math.floor(viewport.toBookY(window.getGuiScaledHeight()));
            addRenderableWidget(new BookButton(right - 88, bottom - 26, 80, 18,
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

    public JournalLayout bookLayout() {
        return bookLayout;
    }

    public BookViewport bookViewport() { return viewport; }

    @Override
    public void setTooltipForNextRenderPass(List<FormattedCharSequence> lines, ClientTooltipPositioner positioner, boolean override) {
        if (bookTooltip == null || override) {
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
        // The world dimmed, then the book, its bookmarks, the pages over their roots, the view and its widgets on top.
        int canvasWidth = width;
        int canvasHeight = height;
        width = minecraft.getWindow().getGuiScaledWidth();
        height = minecraft.getWindow().getGuiScaledHeight();
        renderBackground(graphics, mouseX, mouseY, partialTick);
        JournalSkin.dimWorld(graphics, width, height);
        width = canvasWidth;
        height = canvasHeight;
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
            JournalSkin.title(graphics, font, current.title().copy().withStyle(net.minecraft.ChatFormatting.BOLD), bookLayout, theme.title());
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
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        mouseX = viewport.toBookX(mouseX);
        mouseY = viewport.toBookY(mouseY);
        return super.mouseClicked(mouseX, mouseY, button)
                || current != null && view(current).mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        mouseX = viewport.toBookX(mouseX);
        mouseY = viewport.toBookY(mouseY);
        return current != null && view(current).mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        mouseX = viewport.toBookX(mouseX);
        mouseY = viewport.toBookY(mouseY);
        dragX /= viewport.scale();
        dragY /= viewport.scale();
        return current != null && view(current).mouseDragged(mouseX, mouseY, button, dragX, dragY)
                || super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        mouseX = viewport.toBookX(mouseX);
        mouseY = viewport.toBookY(mouseY);
        boolean handled = current != null && view(current).mouseReleased(mouseX, mouseY, button);
        return super.mouseReleased(mouseX, mouseY, button) || handled;
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        super.mouseMoved(viewport.toBookX(mouseX), viewport.toBookY(mouseY));
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
