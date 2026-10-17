package dev.dzenthai.storybound.client.document;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.text.ClientConfigTexts;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

/**
 * Long texts: the story folder's {@code texts/<lang>/...sbt} (sent by the server) or a resource pack's
 * {@code assets/<ns>/storybound/texts/<lang>/<path>.sbt}; the current language first (story folder, then resource
 * packs), then {@code en_us} the same way.
 */
public final class DocumentTexts {
    private static final String FALLBACK_LANGUAGE = "en_us";

    private DocumentTexts() {}

    public static Optional<String> load(ResourceLocation content) {
        String language = Minecraft.getInstance().getLanguageManager().getSelected();
        Optional<String> text = ClientConfigTexts.document(content, language).or(() -> read(content, language));
        if (text.isPresent() || language.equals(FALLBACK_LANGUAGE)) {
            return text;
        }
        return ClientConfigTexts.document(content, FALLBACK_LANGUAGE).or(() -> read(content, FALLBACK_LANGUAGE));
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
