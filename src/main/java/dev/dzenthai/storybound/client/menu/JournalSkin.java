package dev.dzenthai.storybound.client.menu;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.UiPalette;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** The selected field-journal concept, with artwork separate from live text and controls. */
public final class JournalSkin {
    public static final ResourceLocation FONT = ResourceLocation.withDefaultNamespace("default");
    public static final ResourceLocation HEADING_FONT = FONT;
    /**
     * The open book (October 30, 2026: the 384×256 pixel-art book, drawn a whole number of window pixels per its pixel;
     * before, a 1536×1024 picture scaled down by any factor).
     */
    private static final ResourceLocation BOOK = Storybound.id("textures/gui/journal/adventure_book.png");
    /** The book texture's size in pixels. */
    public static final int BOOK_WIDTH = 384;
    public static final int BOOK_HEIGHT = 256;
    private JournalSkin() {}

    public static Component text(Component text) {
        return MenuThemes.INSTANCE.journalBook() ? text.copy().withStyle(style -> style.withFont(FONT)) : text;
    }

    public static Component heading(Component text) {
        return MenuThemes.INSTANCE.journalBook()
                ? text.copy().withStyle(style -> style.withFont(HEADING_FONT).withBold(true)) : text;
    }

    public static void book(GuiGraphics graphics, JournalLayout layout) {
        RenderSystem.enableBlend();
        graphics.blit(BOOK, layout.x(), layout.y(), layout.width(), layout.height(), 0, 0, BOOK_WIDTH, BOOK_HEIGHT, BOOK_WIDTH, BOOK_HEIGHT);
    }

    public static void title(GuiGraphics graphics, Font font, Component title, int x, int y, int width, float scale, int color) {
        Component styled = title.copy().withStyle(style -> style.withFont(FONT).withBold(true));
        int fitted = font.width(styled) * 2 <= width ? 2 : 1;
        graphics.pose().pushPose();
        graphics.pose().translate(x + (width - font.width(styled) * fitted) / 2, y + (18 - 7 * fitted) / 2, 0);
        graphics.pose().scale(fitted, fitted, 1);
        graphics.drawString(font, styled, 0, 0, color, false);
        graphics.pose().popPose();
        titleRule(graphics, x, y + 29, width, MenuThemes.INSTANCE.theme().divider());
    }

    /**
     * The menu's title over the left page, with its rule and diamond under it in the book's pixels, the diamond on the
     * page's middle, under the frame's small square (October 30, 2026; before, in the canvas's units, a unit off it).
     */
    public static void title(GuiGraphics graphics, Font font, Component title, JournalLayout layout, int color) {
        Component styled = title.copy().withStyle(style -> style.withFont(FONT).withBold(true));
        int x = layout.content().x();
        int y = layout.titleY();
        int width = layout.indexWidth();
        int fitted = font.width(styled) * 2 <= width ? 2 : 1;
        graphics.pose().pushPose();
        graphics.pose().translate(x + (width - font.width(styled) * fitted) / 2, y + (18 - 7 * fitted) / 2, 0);
        graphics.pose().scale(fitted, fitted, 1);
        graphics.drawString(font, styled, 0, 0, color, false);
        graphics.pose().popPose();
        pageRule(graphics, layout, JournalLayout.LEFT_FROM, JournalLayout.LEFT_TO, y + 29, MenuThemes.INSTANCE.theme().divider(), true);
    }

    /**
     * A rule of a page in the book's pixels, one pixel thick as the frames' lines, from {@code from} up to {@code to}
     * and at {@code y} GUI pixels snapped to them; with {@code diamond}, the title's diamond in its middle, which is
     * the page's middle for a column's rule (an even column: the middle falls between two pixels, as the square's).
     */
    public static void pageRule(GuiGraphics graphics, JournalLayout layout, int from, int to, int y, int ink, boolean diamond) {
        double px = layout.bookPixel();
        int row = (int) Math.round((y - layout.y()) / px);
        graphics.pose().pushPose();
        graphics.pose().translate(layout.x(), layout.y(), 0);
        graphics.pose().scale((float) px, (float) px, 1);
        if (!diamond) {
            graphics.fill(from, row, to, row + 1, ink);
        } else {
            int middle = (from + to) / 2;
            graphics.fill(from, row, middle - 6, row + 1, ink);
            graphics.fill(middle + 6, row, to, row + 1, ink);
            for (int dy = -2; dy <= 2; dy++) {
                int half = 3 - Math.abs(dy);
                graphics.fill(middle - half, row + dy, middle + half, row + dy + 1, ink);
            }
            graphics.fill(middle - 1, row, middle + 1, row + 1, UiPalette.opaque(UiPalette.PAPER));
        }
        graphics.pose().popPose();
    }

    /** A rule given in GUI pixels (a column of a view), drawn as {@link #pageRule}, on the book's pixels. */
    public static void bookRule(GuiGraphics graphics, JournalLayout layout, int x, int y, int width, int ink) {
        double px = layout.bookPixel();
        int from = (int) Math.round((x - layout.x()) / px);
        int to = (int) Math.round((x + width - layout.x()) / px);
        pageRule(graphics, layout, from, to, y, ink, false);
    }

    public static void titleRule(GuiGraphics graphics, int x, int ruleY, int width, int ink) {
        int center = x + width / 2;
        rule(graphics, x, ruleY, center - 9 - x, ink);
        rule(graphics, center + 10, ruleY, x + width - center - 10, ink);
        for (int dy = -3; dy <= 3; dy++) {
            int half = 3 - Math.abs(dy);
            graphics.fill(center - half, ruleY + dy, center + half + 1, ruleY + dy + 1, ink);
        }
        graphics.fill(center, ruleY, center + 1, ruleY + 1, UiPalette.opaque(UiPalette.PAPER));
        graphics.fill(center - 7, ruleY, center - 5, ruleY + 1, ink);
        graphics.fill(center + 6, ruleY, center + 8, ruleY + 1, ink);
    }

    public static void rule(GuiGraphics graphics, int x, int y, int width, int color) {
        graphics.fill(x, y, x + width, y + 2, color);
    }

    /** A small pixel star, independent of the active font's symbol coverage. */
    public static void star(GuiGraphics graphics, int x, int y, int color) {
        graphics.fill(x + 3, y, x + 4, y + 7, color);
        graphics.fill(x, y + 3, x + 7, y + 4, color);
        graphics.fill(x + 2, y + 2, x + 5, y + 5, color);
    }

    /** The larger Claim glint keeps the pending reward readable beside its title. */
    public static void rewardMark(GuiGraphics graphics, int x, int y) {
        graphics.pose().pushPose();
        graphics.pose().translate(x + 5, y + 4, 0);
        rewardSpark(graphics, 4, 255);
        graphics.pose().popPose();
    }

    public static void rewardSpark(GuiGraphics graphics, int radius, int alpha) {
        for (int sy = -radius; sy <= radius; sy++) {
            for (int sx = -radius; sx <= radius; sx++) {
                if (Math.abs(sx) + Math.abs(sy) > radius && sx != 0 && sy != 0) continue;
                int ink = Math.abs(sx) + Math.abs(sy) <= radius - 2 ? UiPalette.GOLD_LIGHT : UiPalette.BROWN_LIGHT;
                graphics.fill(sx, sy, sx + 1, sy + 1, (alpha << 24) | ink);
            }
        }
    }

    /** Original pixel orb drawn inside the same 16-pixel cell as item rewards. */
    public static void experienceOrb(GuiGraphics graphics, int x, int y) {
        graphics.fill(x + 5, y + 1, x + 11, y + 15, 0xFF365333);
        graphics.fill(x + 2, y + 4, x + 14, y + 12, 0xFF365333);
        graphics.fill(x + 4, y + 3, x + 12, y + 13, 0xFF71964A);
        graphics.fill(x + 3, y + 5, x + 13, y + 11, 0xFF71964A);
        graphics.fill(x + 5, y + 4, x + 10, y + 10, 0xFFAACD73);
        graphics.fill(x + 6, y + 4, x + 8, y + 7, 0xFFE0EBB4);
        graphics.fill(x + 9, y + 11, x + 11, y + 13, 0xFF52743D);
    }

    public static final class Bookmark extends Button {
        private final MenuTab tab;
        private final boolean selected;
        private final RibbonMotion motion;
        private final RibbonMotion joinMotion;
        private final float pixelScale;
        private final int clothWidth;
        private final int clothHeight;
        private float renderedOrigin;
        private float renderedJoin;

        public Bookmark(MenuTab tab, boolean selected, RibbonMotion motion, RibbonMotion joinMotion, JournalLayout.Rect bounds,
                        float pixelScale, OnPress onPress) {
            super(bounds.x(), bounds.y(), bounds.width(), bounds.height(), tab.title(), onPress, DEFAULT_NARRATION);
            this.tab = tab;
            this.selected = selected;
            this.motion = motion;
            this.joinMotion = joinMotion;
            this.pixelScale = pixelScale;
            this.clothWidth = Math.round(bounds.width() / pixelScale);
            this.clothHeight = Math.round(bounds.height() / pixelScale);
            setTooltip(Tooltip.create(text(tab.title())));
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int x = getX();
            int y = getY();
            long now = net.minecraft.Util.getMillis();
            float emphasis = motion.sample(selected || isHoveredOrFocused() ? 1 : 0, now);
            renderedJoin = joinMotion.sample(selected ? 1 : 0, now);
            float reveal = 3 * emphasis;
            int index = tab.id().equals(AdventureMenu.QUESTS) ? 0 : tab.id().equals(AdventureMenu.CODEX) ? 2
                    : tab.id().equals(AdventureMenu.BOSSES) ? 3 : tab.id().equals(AdventureMenu.MAP) ? 4 : 1;
            // Slide the unchanged ribbon from behind the paper without stretching the emblem.
            RenderSystem.enableBlend();
            graphics.pose().pushPose();
            renderedOrigin = x + (-6 + reveal) * pixelScale;
            graphics.pose().translate(renderedOrigin, y, 0);
            graphics.pose().scale(pixelScale, pixelScale, 1);
            BookmarkPixels.draw(graphics, index, clothWidth, clothHeight, renderedJoin);
            graphics.pose().popPose();
        }

        public void renderPageJoin(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (renderedJoin < 0.001f) return;
            int index = tab.id().equals(AdventureMenu.QUESTS) ? 0 : tab.id().equals(AdventureMenu.CODEX) ? 2
                    : tab.id().equals(AdventureMenu.BOSSES) ? 3 : tab.id().equals(AdventureMenu.MAP) ? 4 : 1;
            // Only the two dark fore-edge texels open; the light page and its ornament stay exposed.
            float joinLeft = getX() + (7 - 9 * renderedJoin) * pixelScale;
            float joinRight = getX() + 7 * pixelScale;
            // The page overlay must stop before the moving emblem, including its transparent pixels.
            int materialRight = Math.min((int) Math.ceil((joinRight - renderedOrigin) / pixelScale),
                    BookmarkPixels.iconLeft(clothWidth, clothHeight) - 1);
            int materialLeft = (int) Math.floor((joinLeft - renderedOrigin) / pixelScale);
            if (materialLeft >= materialRight) return;
            graphics.enableScissor((int) Math.floor(joinLeft), getY(), (int) Math.ceil(joinRight), getY() + height);
            graphics.pose().pushPose();
            graphics.pose().translate(renderedOrigin, getY(), 0);
            graphics.pose().scale(pixelScale, pixelScale, 1);
            BookmarkPixels.drawPageJoin(graphics, index,
                    materialLeft, 0, materialRight, clothHeight);
            graphics.pose().popPose();
            graphics.disableScissor();
        }
    }
}
