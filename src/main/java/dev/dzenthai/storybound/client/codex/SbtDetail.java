package dev.dzenthai.storybound.client.codex;

import dev.dzenthai.storybound.client.document.DocumentTexts;
import dev.dzenthai.storybound.client.menu.ListDetailView;
import dev.dzenthai.storybound.client.menu.JournalSkin;
import dev.dzenthai.storybound.client.menu.MenuThemes;
import dev.dzenthai.storybound.module.document.sbt.SbtLayout;
import dev.dzenthai.storybound.module.document.sbt.SbtParser;
import java.util.List;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;

/** Puts a long {@code .sbt} text into a tab's detail pane: the same markup as documents, laid out to the pane's width. */
final class SbtDetail {
    private static final int LINE_HEIGHT = 10;
    private static final int MISSING_COLOR = 0xFFAA0000;

    private SbtDetail() {}

    static void append(ListDetailView.Detail detail, ResourceLocation text, int color) {
        String source = DocumentTexts.load(text).orElse(null);
        if (source == null) {
            detail.text(Component.translatable("storybound.document.missing_text", text.toString()), MISSING_COLOR);
            return;
        }
        var blocks = SbtParser.parse(source, key -> Language.getInstance().getOrDefault(key));
        boolean book = MenuThemes.INSTANCE.journalBook();
        Style style = book ? Style.EMPTY.withFont(JournalSkin.FONT) : Style.EMPTY;
        List<SbtLayout.Page> pages = SbtLayout.paginate(blocks, style, detail.width(), Integer.MAX_VALUE,
                book ? 13 : LINE_HEIGHT, detail.font()::split);
        for (SbtLayout.Page page : pages) {
            for (SbtLayout.Line line : page.lines()) {
                switch (line) {
                    case SbtLayout.TextLine textLine -> detail.line(textLine.text(), color, textLine.height());
                    case SbtLayout.ImageLine image -> detail.block(image.height() + 2, (graphics, x, y, width, mouseX, mouseY, partialTick) ->
                            graphics.blit(image.texture(), x, y, 0, 0, image.width(), image.height(), image.width(), image.height()));
                }
            }
        }
    }
}
