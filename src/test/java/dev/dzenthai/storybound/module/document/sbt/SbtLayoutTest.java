package dev.dzenthai.storybound.module.document.sbt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import org.junit.jupiter.api.Test;

class SbtLayoutTest {
    /** One character is one pixel wide: a line holds {@code width} characters. */
    private static final SbtLayout.LineWrapper WRAPPER = (text, width) -> {
        String plain = text.getString();
        List<FormattedCharSequence> lines = new ArrayList<>();
        for (int i = 0; i < plain.length(); i += width) {
            lines.add(FormattedCharSequence.forward(plain.substring(i, Math.min(plain.length(), i + width)), Style.EMPTY));
        }
        return lines;
    };

    private static List<SbtLayout.Page> layout(String source, int width, int pageHeight) {
        return SbtLayout.paginate(SbtParser.parse(source, key -> key), Style.EMPTY, width, pageHeight, 10, WRAPPER);
    }

    @Test
    void wrapsAndSplitsPagesByHeight() {
        // 25 characters at width 10 -> 3 lines; pages hold 2 lines.
        List<SbtLayout.Page> pages = layout("abcdefghijklmnopqrstuvwxy", 10, 20);

        assertEquals(2, pages.size());
        assertEquals(2, pages.get(0).lines().size());
        assertEquals(1, pages.get(1).lines().size());
    }

    @Test
    void pageBreakStartsNewPageButNotAnEmptyOne() {
        List<SbtLayout.Page> pages = layout("one\n---page---\n---page---\ntwo", 10, 100);

        assertEquals(2, pages.size());
    }

    @Test
    void endlessPageForScrolls() {
        List<SbtLayout.Page> pages = layout("a\nb\nc\nd\ne", 10, Integer.MAX_VALUE);

        assertEquals(1, pages.size());
        assertEquals(50, pages.getFirst().height());
    }

    @Test
    void imagesShrinkToFitAndBlankLinesKeepHeight() {
        List<SbtLayout.Page> pages = layout("{img:a:b.png 200x100}\n\nx", 50, 1000);
        SbtLayout.ImageLine image = assertInstanceOf(SbtLayout.ImageLine.class, pages.getFirst().lines().getFirst());

        assertEquals(50, image.width());
        assertEquals(25, image.height());
        assertEquals(25 + 10 + 10, pages.getFirst().height());
    }

    @Test
    void emptyTextStillHasOnePage() {
        assertEquals(1, layout("", 10, 100).size());
    }
}
