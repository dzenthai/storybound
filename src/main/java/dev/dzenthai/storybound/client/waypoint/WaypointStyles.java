package dev.dzenthai.storybound.client.waypoint;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.module.waypoint.WaypointDefinition;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

/** Marker styles from {@code assets/<ns>/storybound/waypoint_styles/}; reloaded with F3+T. */
public final class WaypointStyles extends SimpleJsonResourceReloadListener {
    public static final WaypointStyles INSTANCE = new WaypointStyles();

    private volatile Map<ResourceLocation, WaypointStyle> styles = Map.of();
    /** Unknown styles already reported, so the log gets one line per style, not one per frame. */
    private final Set<ResourceLocation> reported = new HashSet<>();

    private WaypointStyles() {
        super(new GsonBuilder().create(), "storybound/waypoint_styles");
    }

    /** The style, else {@code storybound:default}, else the built-in one. */
    public WaypointStyle get(ResourceLocation id) {
        WaypointStyle style = styles.get(id);
        if (style != null) {
            return style;
        }
        if (reported.add(id)) {
            Storybound.LOGGER.warn("Storybound: unknown waypoint style {}; using the default", id);
        }
        return styles.getOrDefault(WaypointDefinition.DEFAULT_STYLE, WaypointStyle.FALLBACK);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<ResourceLocation, WaypointStyle> loaded = new HashMap<>();
        files.forEach((id, json) -> WaypointStyle.CODEC.parse(JsonOps.INSTANCE, json)
                .resultOrPartial(error -> Storybound.LOGGER.error("Storybound: invalid waypoint style {}: {}", id, error))
                .ifPresent(style -> loaded.put(id, style)));
        styles = Map.copyOf(loaded);
        reported.clear();
    }
}
