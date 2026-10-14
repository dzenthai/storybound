package dev.dzenthai.storybound.module.document.sbt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class SbtParserTest {
    private static List<SbtBlock> parse(String source) {
        return SbtParser.parse(source, key -> "<" + key + ">");
    }

    private static List<SbtBlock.Span> spans(SbtBlock block) {
        return assertInstanceOf(SbtBlock.Paragraph.class, block).spans();
    }

    @Test
    void boldAndItalicToggle() {
        List<SbtBlock.Span> spans = spans(parse("a **b** *c* d").getFirst());

        assertEquals(List.of("a ", "b", " ", "c", " d"), spans.stream().map(SbtBlock.Span::text).toList());
        assertTrue(spans.get(1).style().isBold());
        assertTrue(spans.get(3).style().isItalic());
        assertTrue(!spans.get(4).style().isBold() && !spans.get(4).style().isItalic());
    }

    @Test
    void colorAndFontNestAndClose() {
        List<SbtBlock.Span> spans = spans(parse("{color:#7a1f1f}red {font:mypack:runes}runes{/} red{/} plain").getFirst());

        assertEquals(TextColor.fromRgb(0x7a1f1f), spans.get(0).style().getColor());
        assertEquals(ResourceLocation.parse("mypack:runes"), spans.get(1).style().getFont());
        assertEquals(TextColor.fromRgb(0x7a1f1f), spans.get(1).style().getColor());
        assertEquals(" red", spans.get(2).text());
        assertNull(spans.get(3).style().getColor());
    }

    @Test
    void stylesCarryOverLines() {
        List<SbtBlock> blocks = parse("**one\ntwo**\nthree");

        assertTrue(spans(blocks.get(1)).getFirst().style().isBold());
        assertTrue(!spans(blocks.get(2)).getFirst().style().isBold());
    }

    @Test
    void keysImagesAndPageBreaks() {
        List<SbtBlock> blocks = parse("Hello {key:mypack.name}!\r\n---page---\n{img:mypack:textures/doc/seal.png 40x20}\n{img:mypack:textures/doc/map.png}\n\nend\n");

        assertEquals("Hello <mypack.name>!", spans(blocks.get(0)).getFirst().text());
        assertInstanceOf(SbtBlock.PageBreak.class, blocks.get(1));
        assertEquals(new SbtBlock.Image(ResourceLocation.parse("mypack:textures/doc/seal.png"), 40, 20), blocks.get(2));
        assertEquals(new SbtBlock.Image(ResourceLocation.parse("mypack:textures/doc/map.png"), 32, 32), blocks.get(3));
        assertTrue(spans(blocks.get(4)).isEmpty(), "blank line kept");
        assertEquals("end", spans(blocks.get(5)).getFirst().text());
        assertEquals(6, blocks.size(), "trailing newline adds no line");
    }

    @Test
    void escapesAndBrokenTagsStayText() {
        assertEquals("*not italic* {not:tag} {unclosed \\", String.join("", spans(parse("\\*not italic\\* {not:tag} {unclosed \\\\").getFirst())
                .stream().map(SbtBlock.Span::text).toList()));
        assertEquals("{color:nonsense}x", spans(parse("{color:nonsense}x").getFirst()).getFirst().text());
        assertEquals("inline {img:a:b.png} stays", spans(parse("inline {img:a:b.png} stays").getFirst()).getFirst().text());
    }
}
