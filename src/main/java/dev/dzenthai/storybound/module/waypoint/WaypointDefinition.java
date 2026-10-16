package dev.dzenthai.storybound.module.waypoint;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.core.data.ColorCodec;
import dev.dzenthai.storybound.core.logic.Condition;
import dev.dzenthai.storybound.core.logic.LogicCodecs;
import dev.dzenthai.storybound.core.logic.condition.LogicConditions;
import dev.dzenthai.storybound.core.text.StoryText;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

/**
 * {@code data/<ns>/storybound/waypoints/<path>.json}: a waypoint every player may see, when its conditions pass and,
 * if it is bound to a quest, while that quest is active (at {@code stage}, if given). Quest stages can also list
 * waypoints of their own ({@code waypoints} in a stage). Never sent to clients as such: they get only what they see.
 *
 * @param radius an area (a search zone) instead of a point, in blocks; 0 for a point
 * @param beam   a thin light beam rises from the waypoint in the world
 */
public record WaypointDefinition(StoryText name, WaypointTarget target, int color, Condition conditions,
                                 Optional<ResourceLocation> quest, Optional<String> stage, float radius, boolean beam) {
    public static final int DEFAULT_COLOR = 0xFFE8C66A;

    public static final Codec<WaypointDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            StoryText.CODEC.fieldOf("name").forGetter(WaypointDefinition::name),
            WaypointTarget.CODEC.fieldOf("target").forGetter(WaypointDefinition::target),
            ColorCodec.CODEC.optionalFieldOf("color", DEFAULT_COLOR).forGetter(WaypointDefinition::color),
            LogicCodecs.CONDITION.optionalFieldOf("conditions", LogicConditions.ALWAYS).forGetter(WaypointDefinition::conditions),
            ResourceLocation.CODEC.optionalFieldOf("quest").forGetter(WaypointDefinition::quest),
            Codec.STRING.optionalFieldOf("stage").forGetter(WaypointDefinition::stage),
            Codec.floatRange(0f, 1024f).optionalFieldOf("radius", 0f).forGetter(WaypointDefinition::radius),
            Codec.BOOL.optionalFieldOf("beam", false).forGetter(WaypointDefinition::beam)
    ).apply(instance, WaypointDefinition::new));
}
