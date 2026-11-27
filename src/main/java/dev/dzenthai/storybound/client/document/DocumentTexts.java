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

    /**
     * Whether a text that is not there is named in red: for whoever may open the dev mode (permission 2), who can write
     * it; a player sees nothing in its place.
     */
    public static boolean showsMissing() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player != null && minecraft.player.hasPermissions(2);
    }

    public static Optional<String> load(ResourceLocation content) {
        String language = Minecraft.getInstance().getLanguageManager().getSelected();
        Optional<String> text = ClientConfigTexts.document(content, language).or(() -> read(content, language));
        text = text.or(() -> Optional.ofNullable(ClientConfigTexts.texts().sourceDocuments().get(content)));
        if (text.isPresent() || language.equals(FALLBACK_LANGUAGE)) {
            return text;
        }
        return ClientConfigTexts.document(content, FALLBACK_LANGUAGE).or(() -> read(content, FALLBACK_LANGUAGE));
    }

    /** The text in exactly this language (the story folder's, else a resource pack's), without falling back: for the editor. */
    public static Optional<String> load(ResourceLocation content, String language) {
        return ClientConfigTexts.document(content, language).or(() -> read(content, language));
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
