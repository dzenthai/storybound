package dev.dzenthai.storybound.client.waypoint;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.data.ColorCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.StringRepresentable;

/**
 * How waypoint markers are drawn in the world, from {@code assets/<ns>/storybound/waypoint_styles/<path>.json}.
 * One look for every marker ({@code storybound:default}), which resource packs restyle. Sprites are GUI sprites drawn
 * light-on-transparent and tinted with the waypoint's colour (October 29, 2026: the tracked quest's markers too; before,
 * they all took one {@code tracked_color}).
 *
 * @param size        the icon's size in GUI pixels, before the player's scale setting
 * @param fadeStart   farther than this (blocks), the marker starts fading
 * @param maxDistance farther than this it is hidden, unless its quest is tracked; 0 for no limit
 * @param hideWithin  closer than this the marker fades out: you have arrived
 */
public record WaypointStyle(ResourceLocation icon, ResourceLocation areaIcon, ResourceLocation arrow,
                            int size, int textColor, int distanceColor,
                            NameMode name, boolean distance, float fadeStart, float maxDistance, float hideWithin, EdgeMode edge) {
    public static final WaypointStyle FALLBACK = new WaypointStyle(
            Storybound.id("waypoint/marker"), Storybound.id("waypoint/area"), Storybound.id("waypoint/arrow"),
            11, 0xFFF0E6D2, 0xFFC9B48A, NameMode.TRACKED, true, 96f, 768f, 4f, EdgeMode.TRACKED);

    public static final Codec<WaypointStyle> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ResourceLocation.CODEC.optionalFieldOf("icon", FALLBACK.icon).forGetter(WaypointStyle::icon),
            ResourceLocation.CODEC.optionalFieldOf("area_icon", FALLBACK.areaIcon).forGetter(WaypointStyle::areaIcon),
            ResourceLocation.CODEC.optionalFieldOf("arrow", FALLBACK.arrow).forGetter(WaypointStyle::arrow),
            Codec.intRange(4, 64).optionalFieldOf("size", FALLBACK.size).forGetter(WaypointStyle::size),
            ColorCodec.CODEC.optionalFieldOf("text_color", FALLBACK.textColor).forGetter(WaypointStyle::textColor),
            ColorCodec.CODEC.optionalFieldOf("distance_color", FALLBACK.distanceColor).forGetter(WaypointStyle::distanceColor),
            NameMode.CODEC.optionalFieldOf("name", FALLBACK.name).forGetter(WaypointStyle::name),
            Codec.BOOL.optionalFieldOf("distance", FALLBACK.distance).forGetter(WaypointStyle::distance),
            Codec.floatRange(0f, 100_000f).optionalFieldOf("fade_start", FALLBACK.fadeStart).forGetter(WaypointStyle::fadeStart),
            Codec.floatRange(0f, 100_000f).optionalFieldOf("max_distance", FALLBACK.maxDistance).forGetter(WaypointStyle::maxDistance),
            Codec.floatRange(0f, 64f).optionalFieldOf("hide_within", FALLBACK.hideWithin).forGetter(WaypointStyle::hideWithin),
            EdgeMode.CODEC.optionalFieldOf("edge", FALLBACK.edge).forGetter(WaypointStyle::edge)
    ).apply(instance, WaypointStyle::new));

    /** When the waypoint's name shows under its icon. */
    public enum NameMode implements StringRepresentable {
        ALWAYS("always"),
        /** When the player looks near it. */
        NEAR_CROSSHAIR("near_crosshair"),
        /** Always for the tracked quest's waypoints, near the crosshair for the rest. */
        TRACKED("tracked"),
        NEVER("never");

        public static final Codec<NameMode> CODEC = StringRepresentable.fromEnum(NameMode::values);

        private final String name;

        NameMode(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    /** Which waypoints get an arrow at the screen edge while they are off screen. */
    public enum EdgeMode implements StringRepresentable {
        TRACKED("tracked"),
        ALL("all"),
        NEVER("never");

        public static final Codec<EdgeMode> CODEC = StringRepresentable.fromEnum(EdgeMode::values);

        private final String name;

        EdgeMode(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }
}
