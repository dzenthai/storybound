package dev.dzenthai.storybound.client.document;

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
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * Reads a document: a two-page spread with page turning for books, one scrolling column for
 * scrolls, notes and letters. The sheet is scaled down when the window is too small.
 */
public class DocumentScreen extends Screen {
    private static final ResourceLocation PAGE_FORWARD = ResourceLocation.withDefaultNamespace("widget/page_forward");
    private static final ResourceLocation PAGE_FORWARD_HIGHLIGHTED = ResourceLocation.withDefaultNamespace("widget/page_forward_highlighted");
    private static final ResourceLocation PAGE_BACKWARD = ResourceLocation.withDefaultNamespace("widget/page_backward");
    private static final ResourceLocation PAGE_BACKWARD_HIGHLIGHTED = ResourceLocation.withDefaultNamespace("widget/page_backward_highlighted");
    private static final int ARROW_WIDTH = 23;
    private static final int ARROW_HEIGHT = 13;
    private static final int SCROLL_LINES = 3;

    private final ResourceLocation documentId;
    private final @Nullable Screen parent;
    private @Nullable DocumentDefinition definition;
    private DocumentStyle style = DocumentStyle.FALLBACK;
    private List<SbtLayout.Page> pages = List.of();
    private boolean book;
    private int spread;
    private int scroll;
    private float scale = 1;
    private float left;
    private float top;
    private boolean soundPlayed;

    /** @param parent shown again on close, e.g. the Journal; null returns to the game */
    public DocumentScreen(ResourceLocation documentId, @Nullable Screen parent) {
        super(Component.empty());
        this.documentId = documentId;
        this.parent = parent;
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
        layout();
        scale = Math.min(1f, Math.min((width - 16f) / style.width(), (height - 16f) / style.height()));
        left = (width - style.width() * scale) / 2f;
        top = (height - style.height() * scale) / 2f;
        if (!soundPlayed) {
            playSound(style.openSound());
            soundPlayed = true;
        }
    }

    private void layout() {
        Component title = definition.title().asComponent();
        String source = DocumentTexts.load(definition.contentId(documentId))
                .orElseGet(() -> "{color:#aa0000}" + Component.translatable("storybound.document.missing_text", definition.contentId(documentId).toString()).getString() + "{/}");
        List<SbtBlock> blocks = new ArrayList<>();
        if (style.showTitle()) {
            blocks.add(new SbtBlock.Paragraph(List.of(new SbtBlock.Span(title.getString(), Style.EMPTY.withBold(true)))));
            blocks.add(new SbtBlock.Paragraph(List.of()));
        }
        blocks.addAll(SbtParser.parse(source, key -> Language.getInstance().getOrDefault(key)));
        Style base = Style.EMPTY.withFont(style.font());
        int pageWidth = (book ? style.width() / 2 : style.width()) - style.padding().left() - style.padding().right();
        int pageHeight = book ? textHeight() : Integer.MAX_VALUE;
        pages = SbtLayout.paginate(blocks, base, pageWidth, pageHeight, style.lineHeight(), font::split);
    }

    private int textHeight() {
        return style.height() - style.padding().top() - style.padding().bottom();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        if (definition == null) {
            graphics.drawCenteredString(font, Component.translatable("storybound.document.unknown", documentId.toString()), width / 2, height / 2, 0xFF5555);
            return;
        }
        if (!book) {
            renderScrolling(graphics);
            return;
        }
        float sheetX = (mouseX - left) / scale;
        float sheetY = (mouseY - top) / scale;
        graphics.pose().pushPose();
        graphics.pose().translate(left, top, 0);
        graphics.pose().scale(scale, scale, 1);
        drawSheet(graphics);
        int half = style.width() / 2;
        drawPage(graphics, spread * 2, style.padding().left(), style.padding().top());
        drawPage(graphics, spread * 2 + 1, half + style.padding().left(), style.padding().top());
        drawPageNumber(graphics, spread * 2, half / 2);
        drawPageNumber(graphics, spread * 2 + 1, half + half / 2);
        if (spread > 0) {
            boolean hover = overBackward(sheetX, sheetY);
            graphics.blitSprite(hover ? PAGE_BACKWARD_HIGHLIGHTED : PAGE_BACKWARD, arrowMargin(), arrowY(), ARROW_WIDTH, ARROW_HEIGHT);
        }
        if (hasNextSpread()) {
            boolean hover = overForward(sheetX, sheetY);
            graphics.blitSprite(hover ? PAGE_FORWARD_HIGHLIGHTED : PAGE_FORWARD, style.width() - arrowMargin() - ARROW_WIDTH, arrowY(), ARROW_WIDTH, ARROW_HEIGHT);
        }
        graphics.pose().popPose();
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
            int x = style.width() - Math.max(4, padding.right() / 2);
            graphics.pose().pushPose();
            graphics.pose().translate(left, top, 0);
            graphics.pose().scale(scale, scale, 1);
            graphics.fill(x, thumbY, x + 2, thumbY + thumbHeight, 0x80000000 | (style.color() & 0xFFFFFF));
            graphics.pose().popPose();
        }
    }

    private void drawSheet(GuiGraphics graphics) {
        graphics.blit(style.texture(), 0, 0, style.width(), style.height(), 0, 0,
                style.textureWidthOrSize(), style.textureHeightOrSize(), style.textureWidthOrSize(), style.textureHeightOrSize());
    }

    private void drawPage(GuiGraphics graphics, int index, int x, int y) {
        if (index < pages.size()) {
            drawLines(graphics, pages.get(index).lines(), x, y);
        }
    }

    private void drawLines(GuiGraphics graphics, List<SbtLayout.Line> lines, int x, int y) {
        int lineY = y;
        for (SbtLayout.Line line : lines) {
            switch (line) {
                case SbtLayout.TextLine text -> graphics.drawString(font, text.text(), x, lineY + (text.height() - font.lineHeight) / 2 + 1, style.color(), false);
                case SbtLayout.ImageLine image -> graphics.blit(image.texture(), x, lineY, 0, 0, image.width(), image.height(), image.width(), image.height());
            }
            lineY += line.height();
        }
    }

    private void drawPageNumber(GuiGraphics graphics, int index, int centerX) {
        if (index >= pages.size()) {
            return;
        }
        Component number = Component.translatable("storybound.document.page", index + 1, pages.size());
        graphics.drawString(font, number, centerX - font.width(number) / 2, arrowY() + 3, style.color(), false);
    }

    private int maxScroll(SbtLayout.Page page) {
        return Math.max(0, page.height() - textHeight());
    }

    private boolean hasNextSpread() {
        return spread * 2 + 2 < pages.size();
    }

    private int arrowY() {
        return style.height() - ARROW_HEIGHT - Math.max(2, (style.padding().bottom() - ARROW_HEIGHT) / 2);
    }

    private int arrowMargin() {
        return Math.max(4, style.padding().left() / 2);
    }

    private boolean overBackward(float x, float y) {
        return x >= arrowMargin() && x < arrowMargin() + ARROW_WIDTH && y >= arrowY() && y < arrowY() + ARROW_HEIGHT;
    }

    private boolean overForward(float x, float y) {
        int arrowX = style.width() - arrowMargin() - ARROW_WIDTH;
        return x >= arrowX && x < arrowX + ARROW_WIDTH && y >= arrowY() && y < arrowY() + ARROW_HEIGHT;
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
