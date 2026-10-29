package dev.dzenthai.storybound.client.sound;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jetbrains.annotations.Nullable;

/**
 * What the story folder's {@code sounds/} holds: {@code sounds/<namespace>/<name>.ogg} is the sound
 * {@code <namespace>:<name>} (a subfolder goes into the name: {@code sounds/demo/elder/hello.ogg} is
 * {@code demo:elder/hello}); a file right in {@code sounds/} is {@code storybound:<name>}, as a story file without a
 * folder is. Only Ogg Vorbis plays in Minecraft; a file that is not, or is wrongly named, is left out
 * and said why. Pure, for {@link StoryAssets} and tests.
 */
public final class StorySoundFiles {
    /** Above this a sound is streamed, as Minecraft does with music: a long voice line does not load at once. */
    static final long STREAM_BYTES = 256 * 1024;
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
    static final String DEFAULT_NAMESPACE = "storybound";
    private static final Pattern NAME = Pattern.compile("[a-z0-9_./-]+");
    private static final byte[] VORBIS = {1, 'v', 'o', 'r', 'b', 'i', 's'};

    private StorySoundFiles() {}

    /**
     * A file's trouble, by its path in the folder ("demo/hello.ogg"): {@code not_vorbis} (left out),
     * {@code bad_name} (left out), {@code stereo} (played, but it does not fade with distance).
     */
    public record Problem(String file, String kind) {}

    /** One sound: its namespace, its name, the file, whether it is streamed. */
    public record Sound(String namespace, String name, Path file, boolean stream) {
        public String id() {
            return namespace + ":" + name;
        }
    }

    public record Scan(List<Sound> sounds, List<Problem> problems) {
        public static final Scan EMPTY = new Scan(List.of(), List.of());

        /** The namespace's sounds by name. */
        public Map<String, Sound> in(String namespace) {
            Map<String, Sound> found = new TreeMap<>();
            sounds.stream().filter(sound -> sound.namespace().equals(namespace)).forEach(sound -> found.put(sound.name(), sound));
            return found;
        }

        public @Nullable Problem problemOf(String id) {
            int colon = id.indexOf(':');
            String namespace = colon < 0 ? DEFAULT_NAMESPACE : id.substring(0, colon);
            String name = id.substring(colon + 1);
            // A file in its namespace's folder, or a default one right in sounds/.
            List<String> files = namespace.equals(DEFAULT_NAMESPACE) ? List.of(namespace + "/" + name, name) : List.of(namespace + "/" + name);
            return problems.stream().filter(problem -> files.stream().anyMatch(file -> problem.file().startsWith(file + "."))).findFirst().orElse(null);
        }
    }

    public static Scan scan(Path folder) {
        if (!Files.isDirectory(folder)) {
            return Scan.EMPTY;
        }
        List<Sound> sounds = new ArrayList<>();
        List<Problem> problems = new ArrayList<>();
        try (Stream<Path> files = Files.walk(folder)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                String relative = folder.relativize(file).toString().replace('\\', '/');
                int slash = relative.indexOf('/');
                if (!relative.endsWith(".ogg")) {
                    if (relative.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(mp3|wav|flac|m4a|opus)")) {
                        problems.add(new Problem(relative, "not_vorbis"));
                    }
                    continue;
                }
                // Right in sounds/: the default namespace (before, such a file was left out without a word).
                String namespace = slash < 0 ? DEFAULT_NAMESPACE : relative.substring(0, slash);
                String name = relative.substring(slash + 1, relative.length() - ".ogg".length());
                if (!NAMESPACE.matcher(namespace).matches() || !NAME.matcher(name).matches() || name.startsWith("/") || name.contains("//")) {
                    problems.add(new Problem(relative, "bad_name"));
                    continue;
                }
                int channels = vorbisChannels(file);
                if (channels < 1) {
                    problems.add(new Problem(relative, "not_vorbis"));
                    continue;
                }
                if (channels > 1) {
                    problems.add(new Problem(relative, "stereo"));
                }
                sounds.add(new Sound(namespace, name, file, Files.size(file) > STREAM_BYTES));
            }
        } catch (IOException e) {
            problems.add(new Problem("", "unreadable"));
        }
        return new Scan(List.copyOf(sounds), List.copyOf(problems));
    }

    /** The channels of an Ogg Vorbis file (1 mono, 2 stereo), from its first page; -1 when it is not one. */
    static int vorbisChannels(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return vorbisChannels(in.readNBytes(256));
        } catch (IOException e) {
            return -1;
        }
    }

    static int vorbisChannels(byte[] head) {
        if (head.length < 4 || head[0] != 'O' || head[1] != 'g' || head[2] != 'g' || head[3] != 'S') {
            return -1;
        }
        // The identification header: 0x01 "vorbis", a 4-byte version, then the channel count.
        for (int i = 4; i + VORBIS.length + 5 <= head.length; i++) {
            boolean match = true;
            for (int j = 0; j < VORBIS.length && match; j++) {
                match = head[i + j] == VORBIS[j];
            }
            if (match) {
                return head[i + VORBIS.length + 4] & 0xFF;
            }
        }
        return -1;
    }

    /** The {@code sounds.json} of a namespace: each sound an event of its own name, played from its file. */
    public static String soundsJson(Map<String, Sound> sounds) {
        JsonObject json = new JsonObject();
        sounds.forEach((name, sound) -> {
            JsonObject file = new JsonObject();
            file.addProperty("name", sound.id());
            if (sound.stream()) {
                file.addProperty("stream", true);
            }
            JsonArray list = new JsonArray();
            list.add(file);
            JsonObject event = new JsonObject();
            event.add("sounds", list);
            json.add(name, event);
        });
        return json.toString();
    }

    static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
