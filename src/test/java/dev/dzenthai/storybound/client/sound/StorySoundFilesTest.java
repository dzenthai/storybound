package dev.dzenthai.storybound.client.sound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The story's sounds folder (October 30, 2026): what plays, what is left out and why. */
class StorySoundFilesTest {
    @TempDir
    Path folder;

    /** The start of an Ogg Vorbis file: an Ogg page, then the identification header with the channel count. */
    private static byte[] vorbis(int channels) {
        byte[] head = new byte[64];
        byte[] page = {'O', 'g', 'g', 'S'};
        System.arraycopy(page, 0, head, 0, 4);
        byte[] id = {1, 'v', 'o', 'r', 'b', 'i', 's', 0, 0, 0, 0, (byte) channels};
        System.arraycopy(id, 0, head, 28, id.length);
        return head;
    }

    private void file(String path, byte[] bytes) throws IOException {
        Path file = folder.resolve(path);
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }

    @Test
    void oggVorbisFilesAreSoundsOfTheirFolder() throws IOException {
        file("demo/hello.ogg", vorbis(1));
        file("demo/elder/greeting.ogg", vorbis(1));
        file("demo/rain.ogg", vorbis(2));
        file("demo/song.ogg", "ID3 an mp3 renamed".getBytes());
        file("demo/bell.wav", "RIFF".getBytes());
        file("demo/Thunder.ogg", vorbis(1));
        file("loose.ogg", vorbis(1));

        StorySoundFiles.Scan scan = StorySoundFiles.scan(folder);
        assertEquals(List.of("demo:elder/greeting", "demo:hello", "demo:rain", "storybound:loose"), scan.sounds().stream().map(StorySoundFiles.Sound::id).sorted().toList(),
                "a subfolder goes into the name; stereo still plays; right in sounds/ is storybound's");
        assertEquals("stereo", scan.problemOf("demo:rain").kind(), "but it does not fade with distance");
        assertEquals("not_vorbis", scan.problemOf("demo:song").kind(), "renamed, it is still an mp3");
        assertTrue(scan.problems().contains(new StorySoundFiles.Problem("demo/bell.wav", "not_vorbis")), "a wav is said to need converting");
        assertTrue(scan.problems().contains(new StorySoundFiles.Problem("demo/Thunder.ogg", "bad_name")), "capitals");
        file("faaah.ogg", "ID3 an m4a renamed".getBytes());
        assertEquals("not_vorbis", StorySoundFiles.scan(folder).problemOf("storybound:faaah").kind(), "right in sounds/, and not a sound");
        assertEquals(StorySoundFiles.Scan.EMPTY, StorySoundFiles.scan(folder.resolve("missing")), "no folder, no sounds");
    }

    @Test
    void eachSoundIsAnEventOfItsName() throws IOException {
        file("demo/hello.ogg", vorbis(1));
        StorySoundFiles.Scan scan = StorySoundFiles.scan(folder);
        assertEquals("{\"hello\":{\"sounds\":[{\"name\":\"demo:hello\"}]}}", StorySoundFiles.soundsJson(scan.in("demo")));
        assertEquals(1, StorySoundFiles.vorbisChannels(vorbis(1)));
        assertEquals(-1, StorySoundFiles.vorbisChannels("OggS but opus".getBytes()), "an Ogg that is not Vorbis");
    }
}
