package dev.dzenthai.storybound.client.dialogue;

import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.FormattedCharSequence;

/** Draws wrapped dialogue text with the typewriter cut-off. */
final class DialogueText {
    private DialogueText() {}

    /** @return the y below the last line */
    static int draw(GuiGraphics graphics, Font font, List<FormattedCharSequence> lines, int x, int y, int lineHeight, int color, boolean shadow, int visibleChars) {
        int left = visibleChars;
        for (FormattedCharSequence line : lines) {
            if (left <= 0) {
                break;
            }
            graphics.drawString(font, limit(line, left), x, y, color, shadow);
            left -= length(line);
            y += lineHeight;
        }
        return y;
    }

    static FormattedCharSequence limit(FormattedCharSequence line, int chars) {
        return sink -> {
            int[] left = {chars};
            return line.accept((index, style, codePoint) -> left[0]-- > 0 && sink.accept(index, style, codePoint));
        };
    }

    static int length(FormattedCharSequence line) {
        int[] count = {0};
        line.accept((index, style, codePoint) -> {
            count[0]++;
            return true;
        });
        return count[0];
    }
}
