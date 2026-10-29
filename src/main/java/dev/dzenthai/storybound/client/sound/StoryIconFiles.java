package dev.dzenthai.storybound.client.sound;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jetbrains.annotations.Nullable;

/**
 * What the story folder's {@code icons/} holds (October 30, 2026): {@code icons/<namespace>/<name>.png} is the item
 * icon {@code <namespace>:<name>}, one right in {@code icons/} is {@code storybound:<name>}, as with sounds. A document
 * shows it in the inventory through its {@code custom_model_data}: each icon has a number of its own, made from its
 * id, so it is the same on every machine and in every file ({@link #number}). Pure, for {@link StoryAssets} and tests.
 */
public final class StoryIconFiles {
    private static final Pattern NAME = Pattern.compile("[a-z0-9_.-]+");
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    private StoryIconFiles() {}

    /** {@code bad_name}, {@code not_png} (left out), {@code not_square} (left out), {@code not_16} (shown, but larger than the game's icons). */
    public record Problem(String file, String kind) {}

    public record Icon(String namespace, String name, Path file, int number) {
        public String id() {
            return namespace + ":" + name;
        }
    }

    public record Scan(List<Icon> icons, List<Problem> problems) {
        public static final Scan EMPTY = new Scan(List.of(), List.of());

        public @Nullable Icon byNumber(int number) {
            return icons.stream().filter(icon -> icon.number() == number).findFirst().orElse(null);
        }

        public @Nullable Icon byId(String namespace, String name) {
            return icons.stream().filter(icon -> icon.namespace().equals(namespace) && icon.name().equals(name)).findFirst().orElse(null);
        }
    }

    /** The icon's {@code custom_model_data}: from 1 000 000 up, apart from the small numbers resource packs use by hand. */
    public static int number(String id) {
        return 1_000_000 + Math.floorMod(id.hashCode(), 9_000_000);
    }

    public static Scan scan(Path folder) {
        if (!Files.isDirectory(folder)) {
            return Scan.EMPTY;
        }
        List<Icon> icons = new ArrayList<>();
        List<Problem> problems = new ArrayList<>();
        Map<Integer, String> numbers = new HashMap<>();
        try (Stream<Path> files = Files.walk(folder, 2)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                String relative = folder.relativize(file).toString().replace('\\', '/');
                if (!relative.toLowerCase(Locale.ROOT).endsWith(".png")) {
                    continue;
                }
                int slash = relative.indexOf('/');
                String namespace = slash < 0 ? StorySoundFiles.DEFAULT_NAMESPACE : relative.substring(0, slash);
                String name = relative.substring(slash + 1, relative.length() - ".png".length());
                if (!NAME.matcher(namespace).matches() || !NAME.matcher(name).matches() || !relative.endsWith(".png")) {
                    problems.add(new Problem(relative, "bad_name"));
                    continue;
                }
                int[] size = pngSize(file);
                if (size == null) {
                    problems.add(new Problem(relative, "not_png"));
                    continue;
                }
                if (size[0] != size[1]) {
                    problems.add(new Problem(relative, "not_square"));
                    continue;
                }
                if (size[0] != 16) {
                    problems.add(new Problem(relative, "not_16"));
                }
                String id = namespace + ":" + name;
                String clash = numbers.putIfAbsent(number(id), id);
                if (clash != null) {
                    problems.add(new Problem(relative, "same_number"));
                    continue;
                }
                icons.add(new Icon(namespace, name, file, number(id)));
            }
        } catch (IOException e) {
            problems.add(new Problem("", "unreadable"));
        }
        return new Scan(List.copyOf(icons), List.copyOf(problems));
    }

    /** A PNG's width and height, from its header; null when it is not a PNG. */
    static int @Nullable [] pngSize(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return pngSize(in.readNBytes(24));
        } catch (IOException e) {
            return null;
        }
    }

    static int @Nullable [] pngSize(byte[] head) {
        if (head.length < 24) {
            return null;
        }
        for (int i = 0; i < PNG.length; i++) {
            if (head[i] != PNG[i]) {
                return null;
            }
        }
        return new int[]{readInt(head, 16), readInt(head, 20)};
    }

    private static int readInt(byte[] bytes, int at) {
        return (bytes[at] & 0xFF) << 24 | (bytes[at + 1] & 0xFF) << 16 | (bytes[at + 2] & 0xFF) << 8 | bytes[at + 3] & 0xFF;
    }

    /** An icon's item model: a flat item of its texture. */
    public static String iconModel(Icon icon) {
        JsonObject textures = new JsonObject();
        textures.addProperty("layer0", icon.namespace() + ":item/story_icon/" + icon.name());
        JsonObject model = new JsonObject();
        model.addProperty("parent", "minecraft:item/generated");
        model.add("textures", textures);
        return model.toString();
    }

    /**
     * The document item's model with the icons added: the mod's own (a model per kind of document), then each icon by
     * its number, smallest first. An override matches from its number up and the last match wins, so in this order the
     * item shows exactly the icon of its number.
     */
    public static String documentModel(List<Icon> icons) {
        JsonObject model = new JsonObject();
        model.addProperty("parent", "minecraft:item/generated");
        JsonObject textures = new JsonObject();
        textures.addProperty("layer0", "storybound:item/document_letter");
        model.add("textures", textures);
        JsonArray overrides = new JsonArray();
        // As assets/storybound/models/item/document.json has them.
        String[] kinds = {"book", "scroll", "note", "letter", "contract"};
        for (int i = 0; i < kinds.length; i++) {
            overrides.add(override("storybound:kind", (i + 1) / 10.0, "storybound:item/document_" + kinds[i]));
        }
        icons.stream().sorted(java.util.Comparator.comparingInt(Icon::number))
                .forEach(icon -> overrides.add(override("custom_model_data", icon.number(), icon.namespace() + ":item/story_icon/" + icon.name())));
        model.add("overrides", overrides);
        return model.toString();
    }

    private static JsonObject override(String predicate, double value, String model) {
        JsonObject predicates = new JsonObject();
        predicates.addProperty(predicate, value);
        JsonObject override = new JsonObject();
        override.add("predicate", predicates);
        override.addProperty("model", model);
        return override;
    }
}
