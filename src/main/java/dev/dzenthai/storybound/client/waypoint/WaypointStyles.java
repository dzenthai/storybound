package dev.dzenthai.storybound.client.waypoint;

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

/** Marker styles from {@code assets/<ns>/storybound/waypoint_styles/}; reloaded with F3+T. */
public final class WaypointStyles extends SimpleJsonResourceReloadListener {
    public static final WaypointStyles INSTANCE = new WaypointStyles();

    private static final ResourceLocation DEFAULT = Storybound.id("default");
    private volatile Map<ResourceLocation, WaypointStyle> styles = Map.of();

    private WaypointStyles() {
        super(new GsonBuilder().create(), "storybound/waypoint_styles");
    }

    /**
     * The look of every marker: {@code storybound:default} as resource packs give it, else the built-in one. One look
     * for all (October 29, 2026: waypoints no longer pick a style of their own; resource packs can restyle them all).
     */
    public WaypointStyle get() {
        return styles.getOrDefault(DEFAULT, WaypointStyle.FALLBACK);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<ResourceLocation, WaypointStyle> loaded = new HashMap<>();
        files.forEach((id, json) -> WaypointStyle.CODEC.parse(JsonOps.INSTANCE, json)
                .resultOrPartial(error -> Storybound.LOGGER.error("Storybound: invalid waypoint style {}: {}", id, error))
                .ifPresent(style -> loaded.put(id, style)));
        styles = Map.copyOf(loaded);
    }
}
