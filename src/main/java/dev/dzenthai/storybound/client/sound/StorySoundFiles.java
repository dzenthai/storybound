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
 * folder is. The id is made from the file's name: lowercase, a space or a sign other than {@code _ - .} becoming
 * {@code _} ({@code Ragnar Meets.ogg} is {@code ragnar_meets}); the file itself is served under that id. Only Ogg Vorbis
 * plays in Minecraft; a file that is not, a name in letters other than Latin, or a second file making the same id, is
 * left out and said why. Pure, for {@link StoryAssets} and tests.
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

        /** The file's own name, without {@code .ogg}, as the author wrote it. */
        public String label() {
            String file = this.file.getFileName().toString();
            return file.substring(0, file.length() - ".ogg".length());
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
            return problems.stream().filter(problem -> files.stream().anyMatch(file -> idPart(problem.file(), true).startsWith(file + "."))).findFirst().orElse(null);
        }
    }

    public static Scan scan(Path folder) {
        if (!Files.isDirectory(folder)) {
            return Scan.EMPTY;
        }
        List<Sound> sounds = new ArrayList<>();
        List<Problem> problems = new ArrayList<>();
        java.util.Set<String> ids = new java.util.HashSet<>();
        try (Stream<Path> files = Files.walk(folder)) {
            for (Path file : files.filter(Files::isRegularFile).sorted(java.util.Comparator.comparing(Path::toString)).toList()) {
                String relative = folder.relativize(file).toString().replace('\\', '/');
                int slash = relative.indexOf('/');
                if (!relative.toLowerCase(java.util.Locale.ROOT).endsWith(".ogg")) {
                    if (relative.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(mp3|wav|flac|m4a|opus)")) {
                        problems.add(new Problem(relative, "not_vorbis"));
                    }
                    continue;
                }
                // Right in sounds/: the default namespace.
                if (!relative.chars().allMatch(c -> c < 128)) {
                    // Letters other than Latin would all become _: two names would make one id.
                    problems.add(new Problem(relative, "bad_name"));
                    continue;
                }
                String namespace = slash < 0 ? DEFAULT_NAMESPACE : idPart(relative.substring(0, slash), false);
                String name = idPart(relative.substring(slash + 1, relative.length() - ".ogg".length()), true);
                if (!NAMESPACE.matcher(namespace).matches() || !NAME.matcher(name).matches() || name.startsWith("/") || name.contains("//")) {
                    problems.add(new Problem(relative, "bad_name"));
                    continue;
                }
                if (!ids.add(namespace + ":" + name)) {
                    // "Song.ogg" beside "song.ogg": the first (in the folder's order) keeps the id.
                    problems.add(new Problem(relative, "same_name"));
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

    /** A part of an id from a file's name: lowercase, every sign but {@code _ - .} (and {@code /} between folders) as {@code _}. */
    static String idPart(String raw, boolean folders) {
        StringBuilder out = new StringBuilder();
        for (char c : raw.toLowerCase(java.util.Locale.ROOT).toCharArray()) {
            boolean kept = c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_' || c == '-' || c == '.' || folders && c == '/';
            out.append(kept ? c : '_');
        }
        return out.toString();
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
            // In place of a sound of that name, not beside it: an interface sound of the mod is replaced by the story's file.
            event.addProperty("replace", true);
            event.add("sounds", list);
            json.add(name, event);
        });
        return json.toString();
    }

    static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
