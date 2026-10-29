package dev.dzenthai.storybound.client.document;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import dev.dzenthai.storybound.Storybound;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

/** Document styles from resource packs ({@code assets/<ns>/storybound/styles/}); reloaded with F3+T. */
public final class DocumentStyles extends SimpleJsonResourceReloadListener {
    public static final DocumentStyles INSTANCE = new DocumentStyles();
    private static final ResourceLocation DEFAULT_STYLE = Storybound.id("parchment");

    private volatile Map<ResourceLocation, DocumentStyle> styles = Map.of();

    private DocumentStyles() {
        super(new GsonBuilder().create(), "storybound/styles");
    }

    /** Whether a resource pack defines the style (a missing one falls back to the parchment). */
    /** Every style the resource packs give, for the editor's list. */
    public java.util.Set<ResourceLocation> ids() {
        return styles.keySet();
    }

    public boolean has(ResourceLocation id) {
        return styles.containsKey(id);
    }

    /** The named style, else the default parchment, else a built-in fallback. */
    public DocumentStyle get(ResourceLocation id) {
        DocumentStyle style = styles.get(id);
        if (style != null) {
            return style;
        }
        return styles.getOrDefault(DEFAULT_STYLE, DocumentStyle.FALLBACK);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<ResourceLocation, DocumentStyle> loaded = new HashMap<>();
        files.forEach((id, json) -> DocumentStyle.CODEC.parse(JsonOps.INSTANCE, json)
                .ifSuccess(style -> loaded.put(id, style))
                .ifError(error -> Storybound.LOGGER.error("Storybound: invalid document style {}: {}", id, error.message())));
        styles = Map.copyOf(loaded);
    }
}
