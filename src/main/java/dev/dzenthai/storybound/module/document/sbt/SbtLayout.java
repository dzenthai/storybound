package dev.dzenthai.storybound.module.document.sbt;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;

/**
 * Wraps parsed blocks to a width and splits them into pages. Measuring is passed in, so this runs
 * without a client font in tests.
 */
public final class SbtLayout {
    private SbtLayout() {}

    /** Splits styled text into lines no wider than {@code width}; the client uses {@code Font#split}. */
    @FunctionalInterface
    public interface LineWrapper {
        List<FormattedCharSequence> wrap(FormattedText text, int width);
    }

    public sealed interface Line {
        int height();
    }

    public record TextLine(FormattedCharSequence text, int height) implements Line {}

    public record ImageLine(ResourceLocation texture, int width, int height) implements Line {}

    public record Page(List<Line> lines) {
        public int height() {
            return lines.stream().mapToInt(Line::height).sum();
        }
    }

    /**
     * @param base       the document style (font, color) under the markup styles
     * @param pageHeight {@link Integer#MAX_VALUE} for one endless page (scrolls, notes)
     */
    public static List<Page> paginate(List<SbtBlock> blocks, Style base, int width, int pageHeight, int lineHeight, LineWrapper wrapper) {
        List<Page> pages = new ArrayList<>();
        List<Line> current = new ArrayList<>();
        int used = 0;
        for (SbtBlock block : blocks) {
            List<Line> lines = switch (block) {
                case SbtBlock.PageBreak pageBreak -> null;
                case SbtBlock.Image image -> List.of(fitImage(image, width, pageHeight));
                case SbtBlock.Paragraph paragraph -> wrapParagraph(paragraph, base, width, lineHeight, wrapper);
            };
            if (lines == null) {
                if (!current.isEmpty()) {
                    pages.add(new Page(current));
                    current = new ArrayList<>();
                    used = 0;
                }
                continue;
            }
            for (Line line : lines) {
                if (used + line.height() > pageHeight && !current.isEmpty()) {
                    pages.add(new Page(current));
                    current = new ArrayList<>();
                    used = 0;
                }
                current.add(line);
                used += line.height();
            }
        }
        if (!current.isEmpty() || pages.isEmpty()) {
            pages.add(new Page(current));
        }
        return pages;
    }

    private static List<Line> wrapParagraph(SbtBlock.Paragraph paragraph, Style base, int width, int lineHeight, LineWrapper wrapper) {
        if (paragraph.spans().isEmpty()) {
            return List.of(new TextLine(FormattedCharSequence.EMPTY, lineHeight));
        }
        List<FormattedText> parts = new ArrayList<>();
        for (SbtBlock.Span span : paragraph.spans()) {
            parts.add(FormattedText.of(span.text(), span.style().applyTo(base)));
        }
        List<FormattedCharSequence> wrapped = wrapper.wrap(FormattedText.composite(parts), width);
        if (wrapped.isEmpty()) {
            return List.of(new TextLine(FormattedCharSequence.EMPTY, lineHeight));
        }
        return wrapped.stream().<Line>map(text -> new TextLine(text, lineHeight)).toList();
    }

    /** Shrinks an image to the page, keeping its proportions. */
    private static ImageLine fitImage(SbtBlock.Image image, int width, int pageHeight) {
        double scale = Math.min(1, Math.min((double) width / image.width(), (double) pageHeight / image.height()));
        return new ImageLine(image.texture(), (int) (image.width() * scale), (int) (image.height() * scale));
    }
}
