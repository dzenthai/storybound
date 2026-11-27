package dev.dzenthai.storybound.client.sound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class StoryImageFilesTest {
    @Test
    void scansValidPngsAndReportsProblems(@TempDir Path dir) throws IOException {
        Path namespaced = dir.resolve("mypack/places/ancient_city.png");
        Files.createDirectories(namespaced.getParent());
        Files.write(namespaced, fakePngHeader(384, 216));

        Path rootImage = dir.resolve("banner.png");
        Files.write(rootImage, fakePngHeader(256, 144));

        Path badName = dir.resolve("mypack/Bad Name.png");
        Files.write(badName, fakePngHeader(64, 64));

        Path notPng = dir.resolve("mypack/fake.png");
        Files.writeString(notPng, "not a png at all");

        Path tooLarge = dir.resolve("mypack/huge.png");
        Files.write(tooLarge, fakePngHeader(4096, 2048));

        StoryImageFiles.Scan scan = StoryImageFiles.scan(dir);
        assertEquals(2, scan.images().size());

        StoryImageFiles.Image ancient = scan.byId("mypack:places/ancient_city");
        assertNotNull(ancient);
        assertEquals(384, ancient.width());
        assertEquals(216, ancient.height());
        assertEquals("textures/story_image/places/ancient_city.png", ancient.texturePath());

        StoryImageFiles.Image root = scan.byId("storybound:banner");
        assertNotNull(root);
        assertEquals(256, root.width());
        assertEquals(144, root.height());

        assertTrue(scan.problems().stream().anyMatch(p -> p.kind().equals("bad_name")));
        assertTrue(scan.problems().stream().anyMatch(p -> p.kind().equals("not_png")));
        assertTrue(scan.problems().stream().anyMatch(p -> p.kind().equals("too_large")));
    }

    @Test
    void resolvesShortIdsAndExplicitTexturePaths() {
        assertEquals(
                ResourceLocation.parse("mypack:textures/story_image/ruins.png"),
                StoryImageFiles.resolveTexture(ResourceLocation.parse("mypack:ruins")));
        assertEquals(
                ResourceLocation.parse("mypack:textures/gui/codex/custom.png"),
                StoryImageFiles.resolveTexture(ResourceLocation.parse("mypack:textures/gui/codex/custom.png")));
    }

    private static byte[] fakePngHeader(int width, int height) {
        ByteBuffer buf = ByteBuffer.allocate(24);
        buf.put(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        buf.putInt(13);
        buf.put(new byte[]{'I', 'H', 'D', 'R'});
        buf.putInt(width);
        buf.putInt(height);
        return buf.array();
    }
}

