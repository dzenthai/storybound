package dev.dzenthai.storybound.client.document;

import dev.dzenthai.storybound.Storybound;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

/** Long texts: {@code assets/<ns>/storybound/texts/<lang>/<path>.sbt}, current language first, then {@code en_us}. */
public final class DocumentTexts {
    private static final String FALLBACK_LANGUAGE = "en_us";

    private DocumentTexts() {}

    public static Optional<String> load(ResourceLocation content) {
        String language = Minecraft.getInstance().getLanguageManager().getSelected();
        Optional<String> text = read(content, language);
        return text.isPresent() || language.equals(FALLBACK_LANGUAGE) ? text : read(content, FALLBACK_LANGUAGE);
    }

    private static Optional<String> read(ResourceLocation content, String language) {
        ResourceLocation file = ResourceLocation.fromNamespaceAndPath(content.getNamespace(),
                "storybound/texts/" + language + "/" + content.getPath() + ".sbt");
        Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(file);
        if (resource.isEmpty()) {
            return Optional.empty();
        }
        try (InputStream stream = resource.get().open()) {
            return Optional.of(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            Storybound.LOGGER.error("Storybound: could not read {}", file, e);
            return Optional.empty();
        }
    }
}
