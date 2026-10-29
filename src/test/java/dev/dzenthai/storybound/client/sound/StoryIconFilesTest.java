package dev.dzenthai.storybound.client.sound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The story's icons folder (October 30, 2026): what a document's item can show, and how it finds it. */
class StoryIconFilesTest {
    @TempDir
    Path folder;

    /** The start of a PNG: its signature, then the IHDR chunk with the width and height. */
    private static byte[] png(int width, int height) {
        ByteBuffer head = ByteBuffer.allocate(24);
        head.put(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13, 'I', 'H', 'D', 'R'});
        head.putInt(width).putInt(height);
        return head.array();
    }

    private void file(String path, byte[] bytes) throws IOException {
        Path file = folder.resolve(path);
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }

    @Test
    void squarePngsAreIconsOfTheirFolder() throws IOException {
        file("my_story/red_scroll.png", png(16, 16));
        file("my_story/big.png", png(32, 32));
        file("my_story/wide.png", png(32, 16));
        file("my_story/fake.png", "GIF89a, renamed".getBytes());
        file("seal.png", png(16, 16));

        StoryIconFiles.Scan scan = StoryIconFiles.scan(folder);
        assertEquals(List.of("my_story:big", "my_story:red_scroll", "storybound:seal"), scan.icons().stream().map(StoryIconFiles.Icon::id).sorted().toList(),
                "right in icons/ is storybound's; a larger square one still shows");
        assertTrue(scan.problems().contains(new StoryIconFiles.Problem("my_story/big.png", "not_16")));
        assertTrue(scan.problems().contains(new StoryIconFiles.Problem("my_story/wide.png", "not_square")));
        assertTrue(scan.problems().contains(new StoryIconFiles.Problem("my_story/fake.png", "not_png")));
    }

    @Test
    void anIconsNumberIsTheSameEverywhereAndTheItemShowsExactlyIt() throws IOException {
        int number = StoryIconFiles.number("my_story:red_scroll");
        assertEquals(number, StoryIconFiles.number("my_story:red_scroll"), "made from the id, not from the order of files");
        assertTrue(number >= 1_000_000 && number < 10_000_000 && number < 1 << 24, "exact as a float, as model predicates read it");

        file("my_story/a.png", png(16, 16));
        file("my_story/b.png", png(16, 16));
        file("my_story/c.png", png(16, 16));
        JsonArray overrides = JsonParser.parseString(StoryIconFiles.documentModel(StoryIconFiles.scan(folder).icons())).getAsJsonObject().getAsJsonArray("overrides");
        assertEquals(8, overrides.size(), "the five kinds, then the three icons");
        double previous = 0;
        for (int i = 5; i < overrides.size(); i++) {
            double value = overrides.get(i).getAsJsonObject().getAsJsonObject("predicate").get("custom_model_data").getAsDouble();
            assertTrue(value > previous, "smallest first: an override matches from its number up and the last match wins");
            previous = value;
        }
        assertEquals("storybound:item/document_book", overrides.get(0).getAsJsonObject().get("model").getAsString(), "the kinds keep their icons");
    }
}
