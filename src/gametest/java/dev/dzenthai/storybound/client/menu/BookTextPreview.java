package dev.dzenthai.storybound.client.menu;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Compare vanilla bold and book coverage at identical horizontal sampling positions. */
public final class BookTextPreview extends Screen {
    public BookTextPreview() {
        super(Component.literal("Book typography check"));
    }

    @Override
    public void render(GuiGraphics source, int mouseX, int mouseY, float partialTick) {
        source.fill(0, 0, width, height, 0xFF353E43);
        var window = minecraft.getWindow();
        var viewport = BookViewport.fit(window.getWidth(), window.getHeight(), window.getGuiScale());
        var graphics = new BookGuiGraphics(minecraft, source, viewport);
        JournalSkin.book(graphics, JournalLayout.fit(BookViewport.WIDTH, BookViewport.HEIGHT));
        source.pose().pushPose();
        source.pose().translate(viewport.x(), viewport.y(), 0);
        source.pose().scale((float) viewport.scale(), (float) viewport.scale(), 1);
        source.drawString(font, "Vanilla / corrected pairs", 90, 78, 0xFF725136, false);
        graphics.drawString(font, "Same X, same font and size", 343, 78, 0xFF725136, false);
        String[] samples = {"Horizon Zombie", "Our Daily Bread",
                net.minecraft.client.resources.language.I18n.get("storybound.preview.typography"), "Horizon Zombie"};
        for (int row = 0; row < samples.length; row++) {
            int y = 100 + row * 53;
            var sample = JournalSkin.heading(Component.literal(samples[row]));
            source.pose().pushPose();
            source.pose().translate(343 + row * 0.25f, y, 0);
            source.pose().scale(2, 2, 1);
            source.drawString(font, sample, 0, 0, 0xFF4F341E, false);
            source.pose().popPose();
            graphics.pose().pushPose();
            graphics.pose().translate(343 + row * 0.25f, y + 22, 0);
            graphics.pose().scale(2, 2, 1);
            graphics.drawString(font, sample, 0, 0, 0xFF4F341E, false);
            graphics.pose().popPose();
        }
        source.pose().popPose();
        int right = (int) viewport.toBookX(width);
        int bottom = (int) viewport.toBookY(height);
        new ArchiveChrome.ArchiveButton(right - 88, bottom - 26, 80, 18,
                Component.literal("Dev Mode"), button -> {}).render(graphics, -1, -1, partialTick);
    }
}
