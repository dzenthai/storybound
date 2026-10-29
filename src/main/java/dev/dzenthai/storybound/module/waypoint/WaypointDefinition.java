package dev.dzenthai.storybound.module.waypoint;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.data.ColorCodec;
import dev.dzenthai.storybound.core.logic.Condition;
import dev.dzenthai.storybound.core.logic.LogicCodecs;
import dev.dzenthai.storybound.core.logic.condition.LogicConditions;
import dev.dzenthai.storybound.core.text.StoryText;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.resources.ResourceLocation;

/**
 * {@code data/<ns>/storybound/waypoints/<path>.json}: a waypoint every player may see, when its conditions pass and,
 * if it is bound to a quest, while that quest is active (at {@code stage}, if given). Quest stages can also list
 * waypoints of their own ({@code waypoints} in a stage). Never sent to clients as such: they get only what they see.
 *
 * @param radius an area (a search zone) instead of a point, in blocks; 0 for a point
 * @param look   how an area shows (its radius by the name, its edge on the ground)
 * @param color  on the maps (Xaero's Minimap, JourneyMap) and on the marker in the world while it is not tracked (a
 *               tracked marker is gold); one of {@link #PALETTE} shows the same everywhere
 */
public record WaypointDefinition(StoryText name, WaypointTarget target, int color, Condition conditions,
                                 Optional<ResourceLocation> quest, Optional<String> stage, float radius, AreaLook look) {
    public static final int DEFAULT_COLOR = 0xFFE8C66A;
    /**
     * The colours a waypoint offers: Minecraft's 16 text colours, the only ones Xaero's Minimap shows (any other is
     * shown there as the nearest of them, while JourneyMap and the markers in the world show it as it is), so a colour
     * from here looks the same everywhere (October 29, 2026). The dev mode offers these.
     */
    public static final List<Integer> PALETTE = Arrays.stream(ChatFormatting.values())
            .filter(ChatFormatting::isColor).map(format -> 0xFF000000 | format.getColor()).toList();

    public static final Codec<WaypointDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            StoryText.CODEC.fieldOf("name").forGetter(WaypointDefinition::name),
            WaypointTarget.CODEC.fieldOf("target").forGetter(WaypointDefinition::target),
            ColorCodec.CODEC.optionalFieldOf("color", DEFAULT_COLOR).forGetter(WaypointDefinition::color),
            LogicCodecs.CONDITION.optionalFieldOf("conditions", LogicConditions.ALWAYS).forGetter(WaypointDefinition::conditions),
            ResourceLocation.CODEC.optionalFieldOf("quest").forGetter(WaypointDefinition::quest),
            Codec.STRING.optionalFieldOf("stage").forGetter(WaypointDefinition::stage),
            Codec.floatRange(0f, 1024f).optionalFieldOf("radius", 0f).forGetter(WaypointDefinition::radius),
            AreaLook.MAP_CODEC.forGetter(WaypointDefinition::look)
    ).apply(instance, WaypointDefinition::new));

    /** The colour of {@link #PALETTE} nearest to {@code color}, as Xaero's Minimap shows it. */
    public static int nearestInPalette(int color) {
        int best = PALETTE.getFirst();
        long bestDistance = Long.MAX_VALUE;
        for (int candidate : PALETTE) {
            long dr = ((candidate >> 16) & 0xFF) - ((color >> 16) & 0xFF);
            long dg = ((candidate >> 8) & 0xFF) - ((color >> 8) & 0xFF);
            long db = (candidate & 0xFF) - (color & 0xFF);
            long distance = dr * dr + dg * dg + db * db;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }
}
