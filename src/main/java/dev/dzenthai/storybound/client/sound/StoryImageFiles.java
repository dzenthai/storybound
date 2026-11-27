package dev.dzenthai.storybound.client.sound;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * What the story folder's {@code images/} holds: {@code images/<namespace>/<path>.png} becomes the story image
 * {@code <namespace>:<path>}, exposed as texture {@code <namespace>:textures/story_image/<path>.png} through
 * {@link StoryAssets}. Pure file scanner for {@link StoryAssets} and tests.
 */
public final class StoryImageFiles {
    public static final String TEXTURE_PREFIX = "textures/story_image/";
    public static final int MAX_DIMENSION = 2048;
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern PATH = Pattern.compile("[a-z0-9_./-]+");

    private StoryImageFiles() {}

    /** {@code bad_name}, {@code not_png}, {@code too_large}, {@code unreadable}. */
    public record Problem(String file, String kind) {}

    public record Image(String namespace, String name, Path file, int width, int height) {
        public String id() {
            return namespace + ":" + name;
        }

        public String texturePath() {
            return TEXTURE_PREFIX + name + ".png";
        }

        public ResourceLocation textureLocation() {
            return ResourceLocation.fromNamespaceAndPath(namespace, texturePath());
        }
    }

    public record Scan(List<Image> images, List<Problem> problems) {
        public static final Scan EMPTY = new Scan(List.of(), List.of());

        public @Nullable Image byId(String id) {
            return images.stream().filter(image -> image.id().equals(id)).findFirst().orElse(null);
        }
    }

    /**
     * Resolves an image reference to a Minecraft texture location: an explicit {@code .png} path is used as-is,
     * while a short story image id ({@code mypack:ancient_city}) maps into {@value #TEXTURE_PREFIX}.
     */
    public static ResourceLocation resolveTexture(ResourceLocation imageId) {
        if (imageId.getPath().endsWith(".png")) {
            return imageId;
        }
        return ResourceLocation.fromNamespaceAndPath(imageId.getNamespace(), TEXTURE_PREFIX + imageId.getPath() + ".png");
    }

    public static Scan scan(Path folder) {
        if (!Files.isDirectory(folder)) {
            return Scan.EMPTY;
        }
        List<Image> images = new ArrayList<>();
        List<Problem> problems = new ArrayList<>();
        try (Stream<Path> files = Files.walk(folder, 4)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                String relative = folder.relativize(file).toString().replace('\\', '/');
                if (!relative.toLowerCase(Locale.ROOT).endsWith(".png")) {
                    continue;
                }
                int slash = relative.indexOf('/');
                String namespace = slash < 0 ? StorySoundFiles.DEFAULT_NAMESPACE : relative.substring(0, slash);
                String name = relative.substring(slash + 1, relative.length() - ".png".length());
                if (!NAMESPACE.matcher(namespace).matches() || !PATH.matcher(name).matches()
                        || name.startsWith("/") || name.endsWith("/") || name.contains("//") || name.contains("..")
                        || !relative.endsWith(".png")) {
                    problems.add(new Problem(relative, "bad_name"));
                    continue;
                }
                int[] size = pngSize(file);
                if (size == null || size[0] <= 0 || size[1] <= 0) {
                    problems.add(new Problem(relative, "not_png"));
                    continue;
                }
                if (size[0] > MAX_DIMENSION || size[1] > MAX_DIMENSION) {
                    problems.add(new Problem(relative, "too_large"));
                    continue;
                }
                images.add(new Image(namespace, name, file, size[0], size[1]));
            }
        } catch (IOException e) {
            problems.add(new Problem("", "unreadable"));
        }
        return new Scan(List.copyOf(images), List.copyOf(problems));
    }

    /** Reads width and height from a PNG file header, or null if not a valid PNG. */
    public static int @Nullable [] pngSize(Path file) {
        return StoryIconFiles.pngSize(file);
    }

    /** Reads width and height from the first 24 bytes of a PNG stream, or null if not a valid PNG. */
    public static int @Nullable [] pngSize(InputStream stream) {
        try {
            return StoryIconFiles.pngSize(stream.readNBytes(24));
        } catch (IOException e) {
            return null;
        }
    }
}

