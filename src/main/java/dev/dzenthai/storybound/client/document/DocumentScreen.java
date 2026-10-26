package dev.dzenthai.storybound.client.document;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.menu.BookGuiGraphics;
import dev.dzenthai.storybound.client.menu.BookViewport;
import dev.dzenthai.storybound.client.menu.JournalSkin;
import dev.dzenthai.storybound.module.document.DocumentDefinition;
import dev.dzenthai.storybound.module.document.DocumentKind;
import dev.dzenthai.storybound.module.document.DocumentRegistries;
import dev.dzenthai.storybound.module.document.sbt.SbtBlock;
import dev.dzenthai.storybound.module.document.sbt.SbtLayout;
import dev.dzenthai.storybound.module.document.sbt.SbtParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.FormattedCharSequence;
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
    private static final int BOOK_WIDTH = 576;
    private static final int BOOK_HEIGHT = 384;
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
    private int scroll;
    private float scale = 1;
    private float left;
    private float top;
    private boolean soundPlayed;

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
        layout();
        spread = Math.min(spread, Math.max(0, (pages.size() - 1) / 2));
        scale = Math.min(1f, Math.min((width - 16f) / style.width(), (height - 16f) / style.height()));
        left = (width - style.width() * scale) / 2f;
        top = (height - style.height() * scale) / 2f;
        if (printedSurface()) {
            var window = minecraft.getWindow();
            BookViewport viewport = BookViewport.fit(window.getWidth(), window.getHeight(), window.getGuiScale());
            scale = (float) viewport.scale();
            left = (float) viewport.toGuiX((BookViewport.WIDTH - style.width()) / 2);
            top = (float) viewport.toGuiY((BookViewport.HEIGHT - style.height()) / 2);
        }
        if (!soundPlayed) {
            playSound(style.openSound());
            soundPlayed = true;
        }
    }

    private void layout() {
        Component title = definition.title().asComponent();
        scrollTitle = List.of();
        scrollHeaderHeight = 0;
        if ((readingScroll || readingContract) && style.showTitle()) {
            Component heading = title.copy().withStyle(text -> text.withFont(style.font()).withBold(true).withColor(0x493120));
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
        int pageHeight = book ? textHeight() : Integer.MAX_VALUE;
        pages = SbtLayout.paginate(blocks, base, pageWidth(), pageHeight, style.lineHeight(), font::split);
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

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        if (definition == null) {
            graphics.drawCenteredString(font, Component.translatable("storybound.document.unknown", documentId.toString()), width / 2, height / 2, 0xFF5555);
            return;
        }
        if (printedSheet()) {
            renderPrintedSheet(graphics);
            return;
        }
        if (!book) {
            renderScrolling(graphics);
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
    }

    private void drawPageArrow(GuiGraphics graphics, int x, int y, boolean forward, boolean hover) {
        if (!readingBook) {
            ResourceLocation sprite = forward
                    ? (hover ? PAGE_FORWARD_HIGHLIGHTED : PAGE_FORWARD)
                    : (hover ? PAGE_BACKWARD_HIGHLIGHTED : PAGE_BACKWARD);
            graphics.blitSprite(sprite, x, y, ARROW_WIDTH, ARROW_HEIGHT);
            return;
        }
        int color = hover ? 0xFF493120 : 0xFF876052;
        int center = x + ARROW_WIDTH / 2;
        graphics.fill(center - 6, y + 6, center + 7, y + 8, color);
        for (int step = 0; step < 3; step++) {
            int tip = forward ? center + 5 - step * 2 : center - 5 + step * 2;
            graphics.fill(tip, y + 6 - step * 2, tip + 2, y + 8 - step * 2, color);
            graphics.fill(tip, y + 6 + step * 2, tip + 2, y + 8 + step * 2, color);
        }
    }

    private void renderScrolling(GuiGraphics graphics) {
        SbtLayout.Page page = pages.isEmpty() ? new SbtLayout.Page(List.of()) : pages.getFirst();
        scroll = Math.max(0, Math.min(scroll, maxScroll(page)));
        graphics.pose().pushPose();
        graphics.pose().translate(left, top, 0);
        graphics.pose().scale(scale, scale, 1);
        drawSheet(graphics);
        graphics.pose().popPose();

        // Scissor works in screen coordinates and ignores the pose, so it is set outside of it.
        DocumentStyle.Padding padding = style.padding();
        graphics.enableScissor((int) (left + padding.left() * scale), (int) (top + padding.top() * scale),
                (int) Math.ceil(left + (style.width() - padding.right()) * scale), (int) Math.ceil(top + (style.height() - padding.bottom()) * scale));
        graphics.pose().pushPose();
        graphics.pose().translate(left, top, 0);
        graphics.pose().scale(scale, scale, 1);
        drawLines(graphics, page.lines(), padding.left(), padding.top() - scroll);
        graphics.pose().popPose();
        graphics.disableScissor();

        if (maxScroll(page) > 0) {
            int trackHeight = textHeight();
            int thumbHeight = Math.max(8, trackHeight * trackHeight / page.height());
            int thumbY = padding.top() + (trackHeight - thumbHeight) * scroll / maxScroll(page);
            // Just right of the text column: the sheet's edge may be a frame, a roller or a ribbon.
            int x = style.width() - padding.right() + 3;
            graphics.pose().pushPose();
            graphics.pose().translate(left, top, 0);
            graphics.pose().scale(scale, scale, 1);
            graphics.fill(x, thumbY, x + 2, thumbY + thumbHeight, 0x80000000 | (style.color() & 0xFFFFFF));
            graphics.pose().popPose();
        }
    }

    private void renderPrintedSheet(GuiGraphics graphics) {
        SbtLayout.Page page = pages.isEmpty() ? new SbtLayout.Page(List.of()) : pages.getFirst();
        scroll = Math.max(0, Math.min(scroll, maxScroll(page)));
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
                        line * 11, 0x493120, false);
            }
            sheet.pose().popPose();
            JournalSkin.titleRule(sheet, x, textTop() - 12, pageWidth(), 0xFFB17143);
            sheet.disableScissor();
        }
        sheet.enableScissor(x, textTop(), x + pageWidth(), textTop() + textHeight());
        drawLines(sheet, page.lines(), x, textTop() - scroll);
        sheet.disableScissor();
        if (maxScroll(page) > 0) {
            int trackX = style.width() - style.padding().right() + 5;
            int thumbHeight = Math.max(12, textHeight() * textHeight() / page.height());
            int thumbY = textTop() + (textHeight() - thumbHeight) * scroll / maxScroll(page);
            sheet.fill(trackX, textTop(), trackX + 2, textTop() + textHeight(), 0x30B17143);
            sheet.fill(trackX, thumbY, trackX + 2, thumbY + thumbHeight, 0xFFB17143);
        }
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
            if (readingBook) graphics.enableScissor(x, y, x + pageWidth(), y + textHeight());
            if (index == 0 && hasTitlePage()) {
                drawTitlePage(graphics, x, y);
            } else {
                drawLines(graphics, pages.get(index).lines(), x, y);
            }
            if (readingBook) graphics.disableScissor();
        }
    }

    private void drawTitlePage(GuiGraphics graphics, int x, int y) {
        Component title = definition.title().asComponent().copy()
                .withStyle(text -> text.withFont(style.font()).withBold(true).withColor(0x493120));
        int titleScale = 2;
        List<FormattedCharSequence> lines = font.split(title, pageWidth() / titleScale);
        if (lines.size() * 11 * titleScale > textHeight() - 24) {
            titleScale = 1;
            lines = font.split(title, pageWidth());
        }
        int lineStep = 11;
        int visibleLines = Math.min(lines.size(), (textHeight() - 24) / (lineStep * titleScale));
        int titleHeight = (visibleLines - 1) * lineStep * titleScale + 7 * titleScale;
        graphics.pose().pushPose();
        graphics.pose().translate(x, y + (textHeight() - titleHeight) / 2, 0);
        graphics.pose().scale(titleScale, titleScale, 1);
        for (int line = 0; line < visibleLines; line++) {
            FormattedCharSequence text = lines.get(line);
            graphics.drawString(font, text, (pageWidth() / titleScale - font.width(text)) / 2,
                    line * lineStep, 0x493120, false);
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
                                    span.isBold() && span.getColor() == null ? span.withColor(0x885433) : span, codePoint))
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
        graphics.drawString(font, number, centerX - font.width(number) / 2, footerY() + 3, style.color(), false);
    }

    private int maxScroll(SbtLayout.Page page) {
        return Math.max(0, page.height() - textHeight());
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
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (book) {
            turn(scrollY > 0 ? -1 : 1);
        } else {
            scroll -= (int) (scrollY * style.lineHeight() * SCROLL_LINES);
        }
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        switch (keyCode) {
            case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_PAGE_UP -> {
                if (book) {
                    turn(-1);
                } else {
                    scroll -= textHeight();
                }
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_PAGE_DOWN -> {
                if (book) {
                    turn(1);
                } else {
                    scroll += textHeight();
                }
                return true;
            }
            case GLFW.GLFW_KEY_UP -> {
                scroll -= style.lineHeight();
                return true;
            }
            case GLFW.GLFW_KEY_DOWN -> {
                scroll += style.lineHeight();
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
