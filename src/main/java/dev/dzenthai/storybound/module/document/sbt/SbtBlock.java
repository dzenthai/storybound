package dev.dzenthai.storybound.module.document.sbt;

import java.util.List;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;

/** One line of a parsed {@code .sbt} text. */
public sealed interface SbtBlock {
    /** A line of text; an empty span list is a blank line. */
    record Paragraph(List<Span> spans) implements SbtBlock {}

    /** {@code {img:ns:path WxH}} on its own line. */
    record Image(ResourceLocation texture, int width, int height) implements SbtBlock {}

    /** {@code ---page---} on its own line. */
    record PageBreak() implements SbtBlock {}

    /** A run of text with the markup style applied on top of the document style. */
    record Span(String text, Style style) {}
}
