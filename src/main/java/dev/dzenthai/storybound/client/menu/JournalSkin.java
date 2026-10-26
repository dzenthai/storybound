package dev.dzenthai.storybound.client.menu;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.dzenthai.storybound.Storybound;
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
    private static final ResourceLocation BOOK = Storybound.id("textures/gui/journal/vanilla_book.png");
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
        graphics.blit(BOOK, layout.x(), layout.y(), layout.width(), layout.height(), 0, 0, 1536, 1024, 1536, 1024);
    }

    public static void title(GuiGraphics graphics, Font font, Component title, int x, int y, int width, float scale, int color) {
        Component styled = title.copy().withStyle(style -> style.withFont(FONT).withBold(true));
        int fitted = font.width(styled) * 2 <= width ? 2 : 1;
        graphics.pose().pushPose();
        graphics.pose().translate(x + (width - font.width(styled) * fitted) / 2, y + (18 - 7 * fitted) / 2, 0);
        graphics.pose().scale(fitted, fitted, 1);
        graphics.drawString(font, styled, 0, 0, color, false);
        graphics.pose().popPose();
        int ruleY = y + 29;
        int center = x + width / 2;
        int ink = MenuThemes.INSTANCE.theme().divider();
        rule(graphics, x, ruleY, center - 9 - x, ink);
        rule(graphics, center + 10, ruleY, x + width - center - 10, ink);
        for (int dy = -3; dy <= 3; dy++) {
            int half = 3 - Math.abs(dy);
            graphics.fill(center - half, ruleY + dy, center + half + 1, ruleY + dy + 1, ink);
        }
        graphics.fill(center, ruleY, center + 1, ruleY + 1, 0xFFEBD4AA);
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
                int ink = Math.abs(sx) + Math.abs(sy) <= radius - 2 ? 0xFFF0BE : 0xB89156;
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
        private float renderedOrigin;
        private float renderedJoin;

        public Bookmark(MenuTab tab, boolean selected, RibbonMotion motion, RibbonMotion joinMotion, JournalLayout.Rect bounds, OnPress onPress) {
            super(bounds.x(), bounds.y(), bounds.width(), bounds.height(), tab.title(), onPress, DEFAULT_NARRATION);
            this.tab = tab;
            this.selected = selected;
            this.motion = motion;
            this.joinMotion = joinMotion;
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
            renderedOrigin = x - 6 + reveal;
            graphics.pose().translate(renderedOrigin, y, 0);
            BookmarkPixels.draw(graphics, index, width, height, renderedJoin);
            graphics.pose().popPose();
        }

        public void renderPageJoin(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (renderedJoin < 0.001f) return;
            int index = tab.id().equals(AdventureMenu.QUESTS) ? 0 : tab.id().equals(AdventureMenu.CODEX) ? 2
                    : tab.id().equals(AdventureMenu.BOSSES) ? 3 : tab.id().equals(AdventureMenu.MAP) ? 4 : 1;
            int joinLeft = getX() + 7 - Math.round(16 * renderedJoin);
            // The page overlay must stop before the moving emblem, including its transparent pixels.
            int materialRight = Math.min((int) Math.ceil(getX() + 7 - renderedOrigin),
                    BookmarkPixels.iconLeft(width, height) - 1);
            int materialLeft = (int) Math.floor(joinLeft - renderedOrigin);
            if (materialLeft >= materialRight) return;
            graphics.enableScissor(joinLeft, getY(), getX() + 7, getY() + height);
            graphics.pose().pushPose();
            graphics.pose().translate(renderedOrigin, getY(), 0);
            BookmarkPixels.drawPageJoin(graphics, index,
                    materialLeft, 0, materialRight, height);
            graphics.pose().popPose();
            graphics.disableScissor();
        }
    }
}
