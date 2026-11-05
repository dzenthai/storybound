package dev.dzenthai.storybound.client.document;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.UiPalette;
import dev.dzenthai.storybound.client.menu.BookButton;
import dev.dzenthai.storybound.client.menu.BookGuiGraphics;
import dev.dzenthai.storybound.client.menu.BookViewport;
import dev.dzenthai.storybound.client.menu.JournalSkin;
import dev.dzenthai.storybound.module.document.DocumentDefinition;
import dev.dzenthai.storybound.module.document.DocumentKind;
import dev.dzenthai.storybound.module.document.DocumentRegistries;
import dev.dzenthai.storybound.module.document.sbt.SbtBlock;
import dev.dzenthai.storybound.module.document.sbt.SbtLayout;
import dev.dzenthai.storybound.module.document.sbt.SbtParser;
import dev.dzenthai.storybound.network.TakeLecternBookPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.Util;
import net.minecraft.util.Mth;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * Reads a document: a two-page spread with page turning for books, one scrolling column for
 * scrolls, notes and letters. The sheet is scaled down when the window is too small.
 */
public class DocumentScreen extends Screen {
    private static final ResourceLocation READING_BOOK = Storybound.id("textures/gui/document/book_leather.png");
    private static final ResourceLocation READING_SCROLL = Storybound.id("textures/gui/document/scroll.png");
    private static final ResourceLocation READING_LETTER = Storybound.id("textures/gui/document/parchment.png");
    private static final ResourceLocation READING_NOTE = Storybound.id("textures/gui/document/note.png");
    private static final ResourceLocation READING_CONTRACT = Storybound.id("textures/gui/document/contract.png");
    private static final int BOOK_WIDTH = 384;
    private static final int BOOK_HEIGHT = 256;
    private static final int SCROLL_WIDTH = 256;
    private static final int SCROLL_HEIGHT = 384;
    private static final int LETTER_WIDTH = 320;
    private static final int LETTER_HEIGHT = 384;
    private static final int NOTE_WIDTH = 256;
    private static final int NOTE_HEIGHT = 320;
    private static final int CONTRACT_WIDTH = 288;
    private static final int CONTRACT_HEIGHT = 384;
    private static final ResourceLocation PAGE_FORWARD = ResourceLocation.withDefaultNamespace("widget/page_forward");
    private static final ResourceLocation PAGE_FORWARD_HIGHLIGHTED = ResourceLocation.withDefaultNamespace("widget/page_forward_highlighted");
    private static final ResourceLocation PAGE_BACKWARD = ResourceLocation.withDefaultNamespace("widget/page_backward");
    private static final ResourceLocation PAGE_BACKWARD_HIGHLIGHTED = ResourceLocation.withDefaultNamespace("widget/page_backward_highlighted");
    private static final int ARROW_WIDTH = 23;
    private static final int ARROW_HEIGHT = 13;
    private static final int SCROLL_LINES = 3;
    /** Between the last line of a page and its page number and arrows. */
    private static final int FOOTER_GAP = 2;
    /** The buttons under a book, in its pixels: "Done", and "Take Book" on a lectern, as vanilla's lectern has. */
    private static final int BUTTON_WIDTH = 72;
    private static final int BUTTON_HEIGHT = 16;
    private static final int BUTTON_GAP = 8;
    private static final int BUTTON_TOP = 6;
    private static final int BUTTON_ROOM = BUTTON_TOP + BUTTON_HEIGHT + 4;

    private final ResourceLocation documentId;
    private final @Nullable Screen parent;
    /** The text to show instead of the document's own: the editor's preview of a text not saved yet. */
    private final @Nullable String source;
    private @Nullable DocumentDefinition definition;
    private DocumentStyle style = DocumentStyle.FALLBACK;
    private List<SbtLayout.Page> pages = List.of();
    private boolean book;
    private boolean readingBook;
    private boolean readingScroll;
    private boolean readingLetter;
    private boolean readingNote;
    private boolean readingContract;
    private List<FormattedCharSequence> scrollTitle = List.of();
    private int scrollTitleScale = 1;
    private int scrollHeaderHeight;
    private int spread;
    /** Where the text is scrolled to; {@link #shownScroll} follows it smoothly. */
    private int scroll;
    private double shownScroll;
    private long lastFrame;
    /** The scroll arrows' fade, 0 to 1. */
    private float upAlpha;
    private float downAlpha;
    private float scale = 1;
    /**
     * A unit of the leather book's text in its pixels, so it reads at the Adventure Menu's size ({@link BookViewport#bookText};
     * November 5, 2026; before, a unit was a pixel of the book and its text 1.5 to 2 times the menu's); 1 for the rest.
     */
    private float textScale = 1;
    private float left;
    private float top;
    private boolean soundPlayed;
    /** The lectern the book lies on, when it was opened from one; null from the hand, the Journal or the editor. */
    private @Nullable BlockPos lectern;
    private final List<BookButton> buttons = new ArrayList<>();

    /** @param parent shown again on close, e.g. the Journal; null returns to the game */
    public DocumentScreen(ResourceLocation documentId, @Nullable Screen parent) {
        this(documentId, parent, null);
    }

    /** @param source the text to lay out instead of the document's own (a preview); null for the document's */
    public DocumentScreen(ResourceLocation documentId, @Nullable Screen parent, @Nullable String source) {
        super(Component.empty());
        this.documentId = documentId;
        this.parent = parent;
        this.source = source;
    }

    /** Read from a lectern: under the book, "Take Book" besides "Done" (November 4, 2026). */
    public DocumentScreen fromLectern(@Nullable BlockPos pos) {
        lectern = pos;
        return this;
    }

    @Override
    protected void init() {
        definition = DocumentRegistries.DOCUMENTS.clientStore().getOrNull(documentId);
        if (definition == null) {
            pages = List.of();
            return;
        }
        style = DocumentStyles.INSTANCE.get(definition.styleId());
        book = definition.kind() == DocumentKind.BOOK;
        readingBook = book && style.texture().equals(READING_BOOK)
                && style.width() == BOOK_WIDTH && style.height() == BOOK_HEIGHT;
        readingScroll = definition.kind() == DocumentKind.SCROLL && style.texture().equals(READING_SCROLL)
                && style.width() == SCROLL_WIDTH && style.height() == SCROLL_HEIGHT;
        readingLetter = definition.kind() == DocumentKind.LETTER && style.texture().equals(READING_LETTER)
                && style.width() == LETTER_WIDTH && style.height() == LETTER_HEIGHT;
        readingNote = definition.kind() == DocumentKind.NOTE && style.texture().equals(READING_NOTE)
                && style.width() == NOTE_WIDTH && style.height() == NOTE_HEIGHT;
        readingContract = definition.kind() == DocumentKind.CONTRACT && style.texture().equals(READING_CONTRACT)
                && style.width() == CONTRACT_WIDTH && style.height() == CONTRACT_HEIGHT;
        // A book keeps room under it for its buttons.
        int room = book ? BUTTON_ROOM : 0;
        scale = Math.min(1f, Math.min((width - 16f) / style.width(), (height - 16f) / (style.height() + room)));
        left = (width - style.width() * scale) / 2f;
        top = (height - (style.height() + room) * scale) / 2f;
        textScale = 1;
        if (printedSurface()) {
            var window = minecraft.getWindow();
            BookViewport viewport = BookViewport.document(window.getWidth(), window.getHeight(), window.getGuiScale(), style.width(), style.height());
            scale = (float) viewport.scale();
            left = (float) viewport.toGuiX((BookViewport.WIDTH - style.width()) / 2);
            top = (float) viewport.toGuiY((BookViewport.HEIGHT - style.height()) / 2);
            if (readingBook) {
                textScale = (float) BookViewport.bookText(window.getWidth(), window.getHeight(), style.width(), style.height());
            }
        }
        // The pages are laid out in the text's units, so after the scale (the window may have been resized).
        layout();
        spread = Math.min(spread, Math.max(0, (pages.size() - 1) / 2));
        if (!soundPlayed) {
            playSound(style.openSound());
            soundPlayed = true;
        }
        layoutButtons();
    }

    /**
     * Under a book, in its pixels: "Done", and "Take Book" before it when it lies on a lectern and the player may take
     * it (not in adventure mode, as vanilla's lectern; the server checks again).
     */
    private void layoutButtons() {
        buttons.clear();
        if (!book) {
            return;
        }
        boolean take = lectern != null && minecraft.player != null && minecraft.player.mayBuild();
        int count = take ? 2 : 1;
        int x = (style.width() - count * BUTTON_WIDTH - (count - 1) * BUTTON_GAP) / 2;
        int y = style.height() + BUTTON_TOP;
        if (take) {
            buttons.add(new BookButton(x, y, BUTTON_WIDTH, BUTTON_HEIGHT, Component.translatable("lectern.take_book"), button -> takeBook())
                    .withLabelScale(textScale));
            x += BUTTON_WIDTH + BUTTON_GAP;
        }
        buttons.add(new BookButton(x, y, BUTTON_WIDTH, BUTTON_HEIGHT, CommonComponents.GUI_DONE, button -> onClose())
                .withLabelScale(textScale));
    }

    private void takeBook() {
        if (lectern != null) {
            PacketDistributor.sendToServer(new TakeLecternBookPayload(lectern, documentId));
        }
        onClose();
    }

    private void layout() {
        Component title = definition.title().asComponent();
        scrollTitle = List.of();
        scrollHeaderHeight = 0;
        if ((readingScroll || readingContract) && style.showTitle()) {
            Component heading = title.copy().withStyle(text -> text.withFont(style.font()).withBold(true).withColor(UiPalette.INK));
            scrollTitleScale = font.width(heading) * 2 <= pageWidth() ? 2 : 1;
            scrollTitle = font.split(heading, pageWidth() / scrollTitleScale);
            scrollHeaderHeight = scrollTitle.size() * 11 * scrollTitleScale + 18;
            // An unusually long heading must not consume the scrolling viewport.
            if (scrollHeaderHeight > 100) {
                scrollTitle = List.of();
                scrollHeaderHeight = 0;
            }
        }
        String source = this.source != null ? this.source : DocumentTexts.load(definition.contentId(documentId))
                .orElseGet(() -> "{color:#aa0000}" + Component.translatable("storybound.document.missing_text", definition.contentId(documentId).toString()).getString() + "{/}");
        List<SbtBlock> blocks = new ArrayList<>();
        if (style.showTitle() && !hasTitlePage() && scrollTitle.isEmpty()) {
            blocks.add(new SbtBlock.Paragraph(List.of(new SbtBlock.Span(title.getString(), Style.EMPTY.withBold(true)))));
            blocks.add(new SbtBlock.Paragraph(List.of()));
        }
        blocks.addAll(SbtParser.parse(source, key -> Language.getInstance().getOrDefault(key)));
        definition.contract().ifPresent(contract -> {
            if (contract.conditions().isPresent() || contract.reward().isPresent() || contract.hint().isPresent()) {
                blocks.add(new SbtBlock.Paragraph(List.of()));
                contract.conditions().ifPresent(text -> blocks.add(new SbtBlock.Paragraph(List.of(
                        new SbtBlock.Span(text.asComponent().getString(), Style.EMPTY.withBold(true))))));
                contract.reward().ifPresent(text -> blocks.add(new SbtBlock.Paragraph(List.of(
                        new SbtBlock.Span(text.asComponent().getString(), Style.EMPTY)))));
                contract.hint().ifPresent(text -> blocks.add(new SbtBlock.Paragraph(List.of(
                        new SbtBlock.Span(text.asComponent().getString(), Style.EMPTY.withItalic(true))))));
            }
        });
        Style base = Style.EMPTY.withFont(style.font());
        int pageHeight = book ? textUnits(textHeight()) : Integer.MAX_VALUE;
        pages = SbtLayout.paginate(blocks, base, textUnits(pageWidth()), pageHeight, style.lineHeight(), font::split);
        if (hasTitlePage()) {
            List<SbtLayout.Page> withTitle = new ArrayList<>(pages.size() + 1);
            withTitle.add(new SbtLayout.Page(List.of()));
            withTitle.addAll(pages);
            pages = List.copyOf(withTitle);
        }
    }

    private boolean hasTitlePage() {
        return readingBook && style.showTitle();
    }

    private boolean printedSurface() {
        return readingBook || printedSheet();
    }

    private boolean printedSheet() {
        return readingScroll || readingLetter || readingNote || readingContract;
    }

    /** The text column's width: one page of a book, the whole sheet otherwise. */
    private int pageWidth() {
        return (book ? style.width() / 2 : style.width()) - style.padding().left() - style.padding().right();
    }

    private int textHeight() {
        int available = style.height() - textTop() - style.padding().bottom();
        return printedSheet() ? available / style.lineHeight() * style.lineHeight() : available;
    }

    /** A length in the sheet's pixels as whole units of the text ({@link #textScale}). */
    private int textUnits(int pixels) {
        return (int) Math.floor(pixels / textScale + 1e-4);
    }

    private int textTop() {
        return style.padding().top() + scrollHeaderHeight;
    }

    /**
     * A book's margins are mirrored, like a printed book's: {@code left} is the outer margin and {@code right} the
     * one at the spine, so the right page starts {@code right} after the middle and ends {@code left} before the edge.
     */
    private int leftPageX() {
        return style.padding().left();
    }

    private int rightPageX() {
        return style.width() / 2 + style.padding().right();
    }

    /** The page numbers and arrows sit right under the text, inside the paper, not on the sheet's frame. */
    private int footerY() {
        return style.height() - style.padding().bottom() + FOOTER_GAP;
    }

    private int backwardX() {
        return leftPageX();
    }

    private int forwardX() {
        return rightPageX() + pageWidth() - ARROW_WIDTH;
    }

    /** Minecraft's blur and darkening, then the world a step dimmer, as behind the Adventure Menu. */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        JournalSkin.dimWorld(graphics, width, height);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        if (definition == null) {
            graphics.drawCenteredString(font, Component.translatable("storybound.document.unknown", documentId.toString()), width / 2, height / 2, 0xFF5555);
            return;
        }
        if (printedSheet()) {
            renderPrintedSheet(graphics, mouseX, mouseY);
            return;
        }
        if (!book) {
            renderScrolling(graphics, mouseX, mouseY);
            return;
        }
        float sheetX = (mouseX - left) / scale;
        float sheetY = (mouseY - top) / scale;
        if (readingBook) {
            GuiGraphics sheet = new BookGuiGraphics(minecraft, graphics, new BookViewport(scale, left, top));
            drawBook(sheet, sheetX, sheetY);
            sheet.flush();
            return;
        }
        graphics.pose().pushPose();
        graphics.pose().translate(left, top, 0);
        graphics.pose().scale(scale, scale, 1);
        drawBook(graphics, sheetX, sheetY);
        graphics.pose().popPose();
    }

    private void drawBook(GuiGraphics graphics, float sheetX, float sheetY) {
        drawSheet(graphics);
        drawPage(graphics, spread * 2, leftPageX(), style.padding().top());
        drawPage(graphics, spread * 2 + 1, rightPageX(), style.padding().top());
        drawPageNumber(graphics, spread * 2, leftPageX() + pageWidth() / 2);
        drawPageNumber(graphics, spread * 2 + 1, rightPageX() + pageWidth() / 2);
        if (spread > 0) {
            boolean hover = overBackward(sheetX, sheetY);
            drawPageArrow(graphics, backwardX(), footerY(), false, hover);
        }
        if (hasNextSpread()) {
            boolean hover = overForward(sheetX, sheetY);
            drawPageArrow(graphics, forwardX(), footerY(), true, hover);
        }
        for (BookButton button : buttons) {
            button.render(graphics, (int) Math.floor(sheetX), (int) Math.floor(sheetY), 0);
        }
    }

    private void drawPageArrow(GuiGraphics graphics, int x, int y, boolean forward, boolean hover) {
        if (!readingBook) {
            ResourceLocation sprite = forward
                    ? (hover ? PAGE_FORWARD_HIGHLIGHTED : PAGE_FORWARD)
                    : (hover ? PAGE_BACKWARD_HIGHLIGHTED : PAGE_BACKWARD);
            graphics.blitSprite(sprite, x, y, ARROW_WIDTH, ARROW_HEIGHT);
            return;
        }
        // The scroll arrows' page-turning kind, in the middle of the arrow's place (November 4, 2026; before, a chevron).
        ScrollArrows.drawSide(graphics, x + (ARROW_WIDTH - ScrollArrows.SIDE_WIDTH) / 2, y + (ARROW_HEIGHT - ScrollArrows.SIDE_HEIGHT) / 2,
                1, forward, hover);
    }

    private void renderScrolling(GuiGraphics graphics, int mouseX, int mouseY) {
        SbtLayout.Page page = pages.isEmpty() ? new SbtLayout.Page(List.of()) : pages.getFirst();
        scroll = Math.max(0, Math.min(scroll, maxScroll(page)));
        float frame = ease();
        graphics.pose().pushPose();
        graphics.pose().translate(left, top, 0);
        graphics.pose().scale(scale, scale, 1);
        drawSheet(graphics);
        graphics.pose().popPose();

        // Scissor works in screen coordinates and ignores the pose, so it is set outside of it.
        DocumentStyle.Padding padding = style.padding();
        graphics.enableScissor((int) (left + padding.left() * scale), (int) (top + viewTop(page) * scale),
                (int) Math.ceil(left + (style.width() - padding.right()) * scale), (int) Math.ceil(top + (viewTop(page) + viewHeight(page)) * scale));
        graphics.pose().pushPose();
        graphics.pose().translate(left, top, 0);
        graphics.pose().scale(scale, scale, 1);
        drawScrolledLines(graphics, page.lines(), padding.left(), viewTop(page));
        graphics.pose().popPose();
        graphics.disableScissor();

        graphics.pose().pushPose();
        graphics.pose().translate(left, top, 0);
        graphics.pose().scale(scale, scale, 1);
        drawArrows(graphics, page, frame, (mouseX - left) / scale, (mouseY - top) / scale);
        graphics.pose().popPose();
    }

    /**
     * Moves {@link #shownScroll} towards {@link #scroll}, quickly at first and softly at the end, so the wheel, the keys
     * and the arrows scroll smoothly (October 31, 2026; before, in jumps of three lines); returns the frame's length.
     */
    private float ease() {
        long now = Util.getMillis();
        float frame = lastFrame == 0 ? 0 : Math.min(100, now - lastFrame);
        lastFrame = now;
        shownScroll += (scroll - shownScroll) * (1 - Math.exp(-frame / 70.0));
        if (Math.abs(scroll - shownScroll) < 0.05) {
            shownScroll = scroll;
        }
        return frame;
    }

    /** The lines where they are scrolled to now, by parts of a pixel while moving. */
    private void drawScrolledLines(GuiGraphics graphics, List<SbtLayout.Line> lines, int x, int textTop) {
        int whole = (int) Math.floor(shownScroll);
        graphics.pose().pushPose();
        graphics.pose().translate(0, -(shownScroll - whole), 0);
        drawLines(graphics, lines, x, textTop - whole);
        graphics.pose().popPose();
    }

    /**
     * Where the scrolled text shows: its whole room. The arrows lie over its edges, on bands that fade the lines out
     * there (October 31, 2026; before, the same day, room was kept for them, which moved the first line down).
     */
    private int viewTop(SbtLayout.Page page) {
        return textTop();
    }

    private int viewHeight(SbtLayout.Page page) {
        return textHeight();
    }

    /** The middle of the text column, where the arrows stand. */
    private int arrowsX() {
        return style.padding().left() + pageWidth() / 2;
    }

    private int downArrowTop() {
        return textTop() + textHeight() - ScrollArrows.HEIGHT;
    }

    /** The arrows, each while there is text its way, faded in and out; {@code mouseX}, {@code mouseY} in the sheet's pixels. */
    private void drawArrows(GuiGraphics graphics, SbtLayout.Page page, float frame, double mouseX, double mouseY) {
        float step = frame / 150f;
        upAlpha = Mth.clamp(upAlpha + (scroll > 0 ? step : -step), 0, 1);
        downAlpha = Mth.clamp(downAlpha + (scroll < maxScroll(page) ? step : -step), 0, 1);
        int textLeft = style.padding().left();
        ScrollArrows.veil(graphics, textLeft, textLeft + pageWidth(), textTop(), 1, true, style.paper(), upAlpha);
        ScrollArrows.veil(graphics, textLeft, textLeft + pageWidth(), textTop() + textHeight(), 1, false, style.paper(), downAlpha);
        ScrollArrows.draw(graphics, arrowsX(), textTop(), 1, true, upAlpha, ScrollArrows.contains(arrowsX(), textTop(), 1, mouseX, mouseY));
        ScrollArrows.draw(graphics, arrowsX(), downArrowTop(), 1, false, downAlpha, ScrollArrows.contains(arrowsX(), downArrowTop(), 1, mouseX, mouseY));
    }

    private void renderPrintedSheet(GuiGraphics graphics, int mouseX, int mouseY) {
        SbtLayout.Page page = pages.isEmpty() ? new SbtLayout.Page(List.of()) : pages.getFirst();
        scroll = Math.max(0, Math.min(scroll, maxScroll(page)));
        float frame = ease();
        GuiGraphics sheet = new BookGuiGraphics(minecraft, graphics, new BookViewport(scale, left, top));
        drawSheet(sheet);
        int x = style.padding().left();
        if (!scrollTitle.isEmpty()) {
            sheet.enableScissor(x, style.padding().top(), x + pageWidth(), textTop());
            sheet.pose().pushPose();
            sheet.pose().translate(x, style.padding().top(), 0);
            sheet.pose().scale(scrollTitleScale, scrollTitleScale, 1);
            for (int line = 0; line < scrollTitle.size(); line++) {
                FormattedCharSequence text = scrollTitle.get(line);
                sheet.drawString(font, text, (pageWidth() / scrollTitleScale - font.width(text)) / 2,
                        line * 11, UiPalette.INK, false);
            }
            sheet.pose().popPose();
            JournalSkin.titleRule(sheet, x, textTop() - 12, pageWidth(), UiPalette.opaque(UiPalette.TAN));
            sheet.disableScissor();
        }
        sheet.enableScissor(x, viewTop(page), x + pageWidth(), viewTop(page) + viewHeight(page));
        drawScrolledLines(sheet, page.lines(), x, viewTop(page));
        sheet.disableScissor();
        drawArrows(sheet, page, frame, (mouseX - left) / scale, (mouseY - top) / scale);
        sheet.flush();
    }

    private void drawSheet(GuiGraphics graphics) {
        if (printedSurface()) {
            BookTextureRendering.draw(graphics, style);
            return;
        }
        graphics.blit(style.texture(), 0, 0, style.width(), style.height(), 0, 0,
                style.textureWidthOrSize(), style.textureHeightOrSize(), style.textureWidthOrSize(), style.textureHeightOrSize());
    }

    private void drawPage(GuiGraphics graphics, int index, int x, int y) {
        if (index < pages.size()) {
            // The scissor is in the sheet's pixels, so it is set before the text's scale.
            if (readingBook) graphics.enableScissor(x, y, x + pageWidth(), y + textHeight());
            graphics.pose().pushPose();
            graphics.pose().translate(x, y, 0);
            graphics.pose().scale(textScale, textScale, 1);
            if (index == 0 && hasTitlePage()) {
                drawTitlePage(graphics);
            } else {
                drawLines(graphics, pages.get(index).lines(), 0, 0);
            }
            graphics.pose().popPose();
            if (readingBook) graphics.disableScissor();
        }
    }

    /** The book's title in the middle of its first page, in the text's units from the page's top left. */
    private void drawTitlePage(GuiGraphics graphics) {
        Component title = definition.title().asComponent().copy()
                .withStyle(text -> text.withFont(style.font()).withBold(true).withColor(UiPalette.INK));
        int width = textUnits(pageWidth());
        int height = textUnits(textHeight());
        int titleScale = 2;
        List<FormattedCharSequence> lines = font.split(title, width / titleScale);
        if (lines.size() * 11 * titleScale > height - 24) {
            titleScale = 1;
            lines = font.split(title, width);
        }
        int lineStep = 11;
        int visibleLines = Math.min(lines.size(), (height - 24) / (lineStep * titleScale));
        int titleHeight = (visibleLines - 1) * lineStep * titleScale + 7 * titleScale;
        graphics.pose().pushPose();
        graphics.pose().translate(0, (height - titleHeight) / 2, 0);
        graphics.pose().scale(titleScale, titleScale, 1);
        for (int line = 0; line < visibleLines; line++) {
            FormattedCharSequence text = lines.get(line);
            graphics.drawString(font, text, (width / titleScale - font.width(text)) / 2,
                    line * lineStep, UiPalette.INK, false);
        }
        graphics.pose().popPose();
    }

    private void drawLines(GuiGraphics graphics, List<SbtLayout.Line> lines, int x, int y) {
        int lineY = y;
        for (SbtLayout.Line line : lines) {
            switch (line) {
                case SbtLayout.TextLine text -> {
                    FormattedCharSequence styled = printedSurface()
                            ? sink -> text.text().accept((index, span, codePoint) -> sink.accept(index,
                                    span.isBold() && span.getColor() == null ? span.withColor(UiPalette.DARK_BROWN) : span, codePoint))
                            : text.text();
                    graphics.drawString(font, styled, x, lineY + (text.height() - font.lineHeight) / 2 + 1, style.color(), false);
                }
                case SbtLayout.ImageLine image -> graphics.blit(image.texture(), x, lineY, 0, 0, image.width(), image.height(), image.width(), image.height());
            }
            lineY += line.height();
        }
    }

    private void drawPageNumber(GuiGraphics graphics, int index, int centerX) {
        if (index >= pages.size() || (index == 0 && hasTitlePage())) {
            return;
        }
        Component number = Component.translatable("storybound.document.page", index + 1, pages.size());
        graphics.pose().pushPose();
        graphics.pose().translate(centerX, footerY() + 3, 0);
        graphics.pose().scale(textScale, textScale, 1);
        graphics.drawString(font, number, -font.width(number) / 2, 0, style.color(), false);
        graphics.pose().popPose();
    }

    private int maxScroll(SbtLayout.Page page) {
        return Math.max(0, page.height() - viewHeight(page));
    }

    private boolean hasNextSpread() {
        return spread * 2 + 2 < pages.size();
    }

    private boolean overBackward(float x, float y) {
        return x >= backwardX() && x < backwardX() + ARROW_WIDTH && y >= footerY() && y < footerY() + ARROW_HEIGHT;
    }

    private boolean overForward(float x, float y) {
        return x >= forwardX() && x < forwardX() + ARROW_WIDTH && y >= footerY() && y < footerY() + ARROW_HEIGHT;
    }

    private void turn(int delta) {
        int target = spread + delta;
        if (target >= 0 && target * 2 < pages.size() && target != spread) {
            spread = target;
            playSound(style.turnSound());
        }
    }

    private void playSound(Optional<ResourceLocation> sound) {
        sound.ifPresent(id -> minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvent.createVariableRangeEvent(id), 1f)));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!book && definition != null && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            double x = (mouseX - left) / scale;
            double y = (mouseY - top) / scale;
            SbtLayout.Page page = pages.isEmpty() ? new SbtLayout.Page(List.of()) : pages.getFirst();
            boolean up = scroll > 0 && ScrollArrows.contains(arrowsX(), textTop(), 1, x, y);
            boolean down = scroll < maxScroll(page) && ScrollArrows.contains(arrowsX(), downArrowTop(), 1, x, y);
            if (up || down) {
                scrollBy((down ? 1 : -1) * style.lineHeight() * SCROLL_LINES);
                return true;
            }
        }
        if (book && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            float x = (float) ((mouseX - left) / scale);
            float y = (float) ((mouseY - top) / scale);
            if (spread > 0 && overBackward(x, y)) {
                turn(-1);
                return true;
            }
            if (hasNextSpread() && overForward(x, y)) {
                turn(1);
                return true;
            }
            for (BookButton widget : List.copyOf(buttons)) {
                if (widget.mouseClicked(x, y, button)) {
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (book) {
            turn(scrollY > 0 ? -1 : 1);
        } else {
            scrollBy(-(int) (scrollY * style.lineHeight() * SCROLL_LINES));
        }
        return true;
    }

    /** Scrolls the text, kept within it, with the soft click when it moved. */
    private void scrollBy(int delta) {
        SbtLayout.Page page = pages.isEmpty() ? new SbtLayout.Page(List.of()) : pages.getFirst();
        int before = scroll;
        scroll = Math.max(0, Math.min(scroll + delta, maxScroll(page)));
        if (scroll != before) {
            ScrollArrows.click();
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        switch (keyCode) {
            case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_PAGE_UP -> {
                if (book) {
                    turn(-1);
                } else {
                    scrollBy(-textHeight());
                }
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_PAGE_DOWN -> {
                if (book) {
                    turn(1);
                } else {
                    scrollBy(textHeight());
                }
                return true;
            }
            case GLFW.GLFW_KEY_UP -> {
                scrollBy(-style.lineHeight());
                return true;
            }
            case GLFW.GLFW_KEY_DOWN -> {
                scrollBy(style.lineHeight());
                return true;
            }
            default -> {
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
