package dev.dzenthai.storybound.module.document.sbt;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;

/**
 * Parses the {@code .sbt} long-text markup:
 * <ul>
 *     <li>{@code **bold**}, {@code *italic*} — toggles</li>
 *     <li>{@code {color:#7a1f1f}…{/}}, {@code {font:mypack:runes}…{/}} — {@code {/}} closes the latest one</li>
 *     <li>{@code {key:some.translation.key}} — inserts a translated string</li>
 *     <li>{@code {img:mypack:textures/doc/seal.png 32x32}} — an image, alone on its line</li>
 *     <li>{@code ---page---} — a page break, alone on its line</li>
 *     <li>{@code \*}, {@code \{}, {@code \\} — literal characters</li>
 * </ul>
 * Styles carry over line breaks until closed. Unknown or malformed tags are kept as plain text.
 */
public final class SbtParser {
    private static final Pattern IMAGE = Pattern.compile("^\\{img:([a-z0-9_.-]+:[a-z0-9_./-]+)(?:\\s+(\\d+)x(\\d+))?}$");
    private static final int DEFAULT_IMAGE_SIZE = 32;
    private static final String PAGE_BREAK = "---page---";

    private final UnaryOperator<String> translator;
    private final Deque<Style> pushed = new ArrayDeque<>();
    private boolean bold;
    private boolean italic;

    private SbtParser(UnaryOperator<String> translator) {
        this.translator = translator;
        pushed.push(Style.EMPTY);
    }

    /** @param translator resolves {@code {key:…}}; the client passes the current language */
    public static List<SbtBlock> parse(String source, UnaryOperator<String> translator) {
        return new SbtParser(translator).parseAll(source);
    }

    private List<SbtBlock> parseAll(String source) {
        List<SbtBlock> blocks = new ArrayList<>();
        for (String line : source.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.equals(PAGE_BREAK)) {
                blocks.add(new SbtBlock.PageBreak());
                continue;
            }
            Matcher image = IMAGE.matcher(trimmed);
            if (image.matches()) {
                int width = image.group(2) != null ? Integer.parseInt(image.group(2)) : DEFAULT_IMAGE_SIZE;
                int height = image.group(3) != null ? Integer.parseInt(image.group(3)) : DEFAULT_IMAGE_SIZE;
                blocks.add(new SbtBlock.Image(ResourceLocation.parse(image.group(1)), width, height));
                continue;
            }
            blocks.add(new SbtBlock.Paragraph(parseLine(line)));
        }
        // A trailing newline in the file is not an extra blank line.
        if (!blocks.isEmpty() && blocks.getLast() instanceof SbtBlock.Paragraph paragraph && paragraph.spans().isEmpty()) {
            blocks.removeLast();
        }
        return blocks;
    }

    private List<SbtBlock.Span> parseLine(String line) {
        List<SbtBlock.Span> spans = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == '\\' && i + 1 < line.length()) {
                text.append(line.charAt(i + 1));
                i += 2;
            } else if (c == '*' && i + 1 < line.length() && line.charAt(i + 1) == '*') {
                flush(spans, text);
                bold = !bold;
                i += 2;
            } else if (c == '*') {
                flush(spans, text);
                italic = !italic;
                i++;
            } else if (c == '{') {
                int end = line.indexOf('}', i);
                if (end < 0 || !applyTag(line.substring(i + 1, end), spans, text)) {
                    text.append(c);
                    i++;
                } else {
                    i = end + 1;
                }
            } else {
                text.append(c);
                i++;
            }
        }
        flush(spans, text);
        return spans;
    }

    /** @return false if the tag is unknown or malformed, so the caller keeps it as text */
    private boolean applyTag(String tag, List<SbtBlock.Span> spans, StringBuilder text) {
        if (tag.equals("/")) {
            flush(spans, text);
            if (pushed.size() > 1) {
                pushed.pop();
            }
            return true;
        }
        int colon = tag.indexOf(':');
        if (colon < 0) {
            return false;
        }
        String name = tag.substring(0, colon);
        String value = tag.substring(colon + 1);
        switch (name) {
            case "color" -> {
                TextColor color = TextColor.parseColor(value).result().orElse(null);
                if (color == null) {
                    return false;
                }
                flush(spans, text);
                pushed.push(pushed.peek().withColor(color));
                return true;
            }
            case "font" -> {
                ResourceLocation font = ResourceLocation.tryParse(value);
                if (font == null) {
                    return false;
                }
                flush(spans, text);
                pushed.push(pushed.peek().withFont(font));
                return true;
            }
            case "key" -> {
                text.append(translator.apply(value));
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private void flush(List<SbtBlock.Span> spans, StringBuilder text) {
        if (!text.isEmpty()) {
            spans.add(new SbtBlock.Span(text.toString(), pushed.peek().withBold(bold ? true : null).withItalic(italic ? true : null)));
            text.setLength(0);
        }
    }
}
