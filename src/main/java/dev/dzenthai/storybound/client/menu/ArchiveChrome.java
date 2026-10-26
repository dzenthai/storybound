package dev.dzenthai.storybound.client.menu;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** Pixel-aligned archive materials, drawn at GUI resolution without stretching texture grain. */
public final class ArchiveChrome {
    private static final int EDGE = 0xFF101114;
    private static final int COVER = 0xFF29282B;
    private static final int PAPER = 0xFF222225;

    private ArchiveChrome() {}

    private static int material(int archive, int parchment) {
        return MenuThemes.INSTANCE.parchment() ? parchment : archive;
    }

    public static void panel(GuiGraphics graphics, int x, int y, int width, int height, MenuTheme theme) {
        graphics.fill(x + 4, y + 7, x + width + 4, y + height + 7, 0x55000000);
        graphics.fill(x + 2, y + height, x + width - 1, y + height + 3, material(0xFF383034, 0xFF483025));
        graphics.fill(x + 3, y + height + 1, x + width - 2, y + height + 2, 0xFF756555);
        graphics.fill(x + 1, y, x + width - 1, y + height, material(EDGE, 0xFF2C201B));
        graphics.fill(x, y + 1, x + width, y + height - 1, material(EDGE, 0xFF2C201B));
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 2, material(COVER, 0xFF594034));
        graphics.fill(x + 8, y + 8, x + width - 8, y + height - 8, material(PAPER, 0xFFDFD1B2));
        graphics.fill(x + 8, y + 8, x + width - 8, y + 10, material(0xFF17181B, 0xFFAB9573));
        graphics.fill(x + 8, y + 10, x + 10, y + height - 8, material(0xFF19191C, 0xFFC1AC88));
        graphics.fill(x + 10, y + height - 9, x + width - 8, y + height - 8, material(0xFF484044, 0xFFF0E4CC));
        graphics.fill(x + 2, y + 2, x + width - 2, y + 3, theme.divider());
        graphics.fill(x + 2, y + 3, x + 3, y + height - 3, material(0xFF484044, 0xFF80604A));
        graphics.fill(x + width - 3, y + 3, x + width - 2, y + height - 3, material(0xFF18181B, 0xFF33231D));
        graphics.fill(x + 3, y + height - 3, x + width - 3, y + height - 2, material(0xFF151518, 0xFF30231C));

        // Fixed flecks stay on the binding, away from the reading surface.
        for (int offset = 17; offset < width - 15; offset += 17 + offset % 13) {
            graphics.fill(x + offset, y + 4, x + offset + 3, y + 5, material(0xFF514347, 0xFF886951));
            graphics.fill(x + offset + 4, y + height - 6, x + offset + 6, y + height - 5, material(0xFF454044, 0xFF725440));
        }
        for (int offset = 19; offset < height - 12; offset += 19 + offset % 11) {
            graphics.fill(x + 4, y + offset, x + 6, y + offset + 2, material(0xFF454044, 0xFF725440));
            graphics.fill(x + width - 6, y + offset + 1, x + width - 5, y + offset + 3, material(0xFF18181B, 0xFF33231D));
        }
        corner(graphics, x + 2, y + 2, 1, 1, theme);
        corner(graphics, x + width - 3, y + 2, -1, 1, theme);
        corner(graphics, x + 2, y + height - 3, 1, -1, theme);
        corner(graphics, x + width - 3, y + height - 3, -1, -1, theme);
    }

    private static void corner(GuiGraphics graphics, int x, int y, int dx, int dy, MenuTheme theme) {
        for (int i = 0; i < 12; i++) {
            pixel(graphics, x + i * dx, y, material(theme.accent(), 0xFFC2A16B));
            pixel(graphics, x, y + i * dy, material(theme.accent(), 0xFFC2A16B));
            if (i < 8) {
                pixel(graphics, x + i * dx, y + dy, theme.divider());
                pixel(graphics, x + dx, y + i * dy, theme.divider());
            }
        }
        pixel(graphics, x + dx, y + dy, material(theme.muted(), 0xFFE0C48E));
        for (int i = 2; i < 7; i++) {
            pixel(graphics, x + i * dx, y + (8 - i) * dy, theme.divider());
        }
        pixel(graphics, x + 3 * dx, y + 3 * dy, material(theme.accent(), 0xFFC2A16B));
        pixel(graphics, x + 4 * dx, y + 4 * dy, EDGE);
    }

    private static void pixel(GuiGraphics graphics, int x, int y, int color) {
        graphics.fill(x, y, x + 1, y + 1, color);
    }

    public static void tab(GuiGraphics graphics, int x, int y, int width, int height,
                           boolean selected, boolean hovered, MenuTheme theme) {
        graphics.fill(x + 2, y, x + width - 2, y + height, EDGE);
        graphics.fill(x, y + 2, x + width, y + height, EDGE);
        graphics.fill(x + 1, y + 2, x + width - 1, y + height,
                selected ? material(theme.selection(), 0xFF713C40) : hovered ? material(0xFF343136, 0xFF765747) : material(COVER, 0xFF594034));
        graphics.fill(x + 2, y + 1, x + width - 2, y + 2, selected ? material(theme.accent(), 0xFFC2A16B) : theme.divider());
        graphics.fill(x + 1, y + 3, x + 2, y + height - 1, 0x204E4140);
        graphics.fill(x + width - 2, y + 3, x + width - 1, y + height, 0x55000000);
        graphics.fill(x + 3, y + 3, x + width - 3, y + 4, 0x184E4140);
        for (int offset = 8; offset < width - 6; offset += 9 + offset % 7) {
            graphics.fill(x + offset, y + height - 5, x + offset + 2, y + height - 4, 0x154E4140);
        }
        if (selected) {
            graphics.fill(x + 3, y + 3, x + 6, y + 4, 0x307F6570);
            graphics.fill(x + width - 7, y + 2, x + width - 4, y + 3, 0x405F5350);
        }
    }

    /** Original twelve-pixel emblems share one palette and a top-left light source. */
    public static void icon(GuiGraphics graphics, ResourceLocation id, int x, int y, MenuTheme theme) {
        String[] pixels;
        if (id.equals(AdventureMenu.JOURNAL)) {
            pixels = new String[]{"............", ".hhh..hhh...", "hbbbhsbbbh..", "hbbbbsbbbs..", "hbhbbsbhbs..", "hbbbbsbbbs..", "hbhbbsbhbs..", "hbbbbsbbbs..", "hbbbbsbbbs..", ".hhbbsbbss..", "...hhshh....", "............"};
        } else if (id.equals(AdventureMenu.CODEX)) {
            pixels = new String[]{"............", "..hhhhhhh...", ".hbbbbbbbs..", ".hsbbbbbbs..", ".hshhhbbbs..", ".hsbbbbbbs..", ".hshhhbbbs..", ".hsbbbbbbs..", ".hsbbbbbbs..", ".hhhhhhhhs..", "..ssssssss..", "............"};
        } else if (id.equals(AdventureMenu.BOSSES)) {
            pixels = new String[]{"...hhhhh....", "..hbbbbbs...", ".hbbbbbbbs..", ".hbsbbsbbs..", ".hbsbbsbbs..", "..bbbsbbb...", "...bbbbb....", "...bsbsb....", "....sss.....", "..s.....s...", ".s.......s..", "............"};
        } else if (id.equals(AdventureMenu.MAP)) {
            pixels = new String[]{".....h......", "....hbs.....", "....hbs.....", "...hhbss....", ".hhhhbsssss.", "..hhbbssss..", "...hbbsss...", "....bbs.....", "....bbs.....", ".....s......", "............", "............"};
        } else {
            pixels = new String[]{"..hhhhhhh...", "..hbbbbbs...", "..hsbbsbs...", "..hbbbbbs...", "..hssssbs...", "..hbbbbbs...", "..hssssbs...", "..hbbbbbs...", "..hssssbs...", "..hbbbbbs...", "..hhhhsss...", "............"};
        }
        for (int row = 0; row < pixels.length; row++) {
            for (int col = 0; col < pixels[row].length(); col++) {
                char shade = pixels[row].charAt(col);
                if (shade != '.') {
                    pixel(graphics, x + col + 1, y + row + 1, 0x80000000);
                }
            }
        }
        for (int row = 0; row < pixels.length; row++) {
            for (int col = 0; col < pixels[row].length(); col++) {
                char shade = pixels[row].charAt(col);
                if (shade != '.') {
                    pixel(graphics, x + col, y + row, shade == 'h' ? material(theme.title(), 0xFFE8D7AF)
                            : shade == 'b' ? material(theme.accent(), 0xFFC2A16B) : theme.divider());
                }
            }
        }
    }

    /** Retains vanilla button input, narration and label clipping. Only the surface changes. */
    public static final class ArchiveButton extends Button {
        private boolean pin;
        private java.util.function.BooleanSupplier readyEffect = () -> false;
        private long readySince = -1;

        public ArchiveButton withReadyEffect(java.util.function.BooleanSupplier ready) {
            readyEffect = ready;
            return this;
        }

        public ArchiveButton withPin() {
            pin = true;
            return this;
        }

        public ArchiveButton(int x, int y, int width, int height, Component label, OnPress onPress) {
            super(x, y, width, height, label, onPress, DEFAULT_NARRATION);
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (MenuThemes.INSTANCE.journalBook()) {
                MenuTheme theme = MenuThemes.INSTANCE.theme();
                fillBookFace(graphics, 0xFFE5CDA3);
                if (active && isHoveredOrFocused()) {
                    fillBookFace(graphics, theme.hover());
                }
                int stamp = theme.divider();
                // Two-pixel stepped corners match the book's printed ornament scale.
                graphics.fill(getX() + 3, getY(), getX() + width - 3, getY() + 2, stamp);
                graphics.fill(getX() + 3, getY() + height - 2, getX() + width - 3, getY() + height, stamp);
                graphics.fill(getX(), getY() + 3, getX() + 2, getY() + height - 3, stamp);
                graphics.fill(getX() + width - 2, getY() + 3, getX() + width, getY() + height - 3, stamp);
                graphics.fill(getX() + 2, getY() + 2, getX() + 4, getY() + 4, stamp);
                graphics.fill(getX() + width - 4, getY() + 2, getX() + width - 2, getY() + 4, stamp);
                graphics.fill(getX() + 2, getY() + height - 4, getX() + 4, getY() + height - 2, stamp);
                graphics.fill(getX() + width - 4, getY() + height - 4, getX() + width - 2, getY() + height - 2, stamp);
                var font = Minecraft.getInstance().font;
                var label = JournalSkin.text(getMessage());
                int inset = pin ? 13 : 0;
                var clipped = font.substrByWidth(label, Math.max(1, width - 8 - inset));
                int labelX = getX() + (width - font.width(clipped) - inset) / 2;
                if (pin) {
                    int py = getY() + height / 2 - 4;
                    graphics.fill(labelX + 4, py, labelX + 8, py + 4, stamp);
                    graphics.fill(labelX + 2, py + 3, labelX + 6, py + 5, stamp);
                    graphics.fill(labelX + 1, py + 5, labelX + 3, py + 7, stamp);
                    graphics.fill(labelX, py + 7, labelX + 1, py + 8, stamp);
                }
                graphics.drawString(font, net.minecraft.locale.Language.getInstance().getVisualOrder(clipped),
                        labelX + inset, getY() + (height - 7) / 2,
                        active ? theme.ink() : theme.muted(), false);
                if (active && readyEffect.getAsBoolean()) {
                    long now = net.minecraft.Util.getMillis();
                    if (readySince < 0) readySince = now;
                    long elapsed = now - readySince;
                    for (int i = 0; i < 5; i++) {
                        long age = elapsed - i * 260L;
                        if (age < 0) continue;
                        float phase = (age % 1800) / 1250f;
                        if (phase >= 1) continue;
                        int alpha = (int) (225 * Math.min(1, phase / 0.16f) * Math.pow(1 - phase, 0.6));
                        float drift = (float) Math.sin(phase * Math.PI) * (i % 2 == 0 ? -4 : 4);
                        float px = getX() + width * (i + 1) / 6f + drift;
                        float py = getY() - 3 - phase * 13;
                        int radius = phase > 0.84f ? 1 : i == 2 ? 4 : i % 2 == 0 ? 2 : 3;
                        graphics.pose().pushPose();
                        graphics.pose().translate(px, py, 0);
                        JournalSkin.rewardSpark(graphics, radius, alpha);
                        graphics.pose().popPose();
                    }
                } else {
                    readySince = -1;
                }
                return;
            }
            if (!MenuThemes.INSTANCE.archiveChrome()) {
                super.renderWidget(graphics, mouseX, mouseY, partialTick);
                return;
            }
            MenuTheme theme = MenuThemes.INSTANCE.theme();
            int x = getX();
            int y = getY();
            boolean highlighted = active && isHoveredOrFocused();
            graphics.fill(x, y, x + width, y + height, EDGE);
            graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1,
                    highlighted ? theme.selection() : material(COVER, 0xFFD1BF9B));
            graphics.fill(x + 1, y + 1, x + width - 1, y + 2,
                    highlighted ? theme.accent() : theme.divider());
            graphics.fill(x + 1, y + 2, x + 2, y + height - 2, theme.divider());
            if (isFocused()) {
                graphics.renderOutline(x, y, width, height, theme.accent());
            }
            renderString(graphics, Minecraft.getInstance().font, active ? theme.ink() : theme.muted());
        }

        private void fillBookFace(GuiGraphics graphics, int color) {
            graphics.fill(getX() + 3, getY() + 1, getX() + width - 3, getY() + height - 1, color);
            graphics.fill(getX() + 1, getY() + 3, getX() + width - 1, getY() + height - 3, color);
        }
    }
}
