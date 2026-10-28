package dev.dzenthai.storybound.module.waypoint;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.Storybound;
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
 * @param look   how an area shows (its radius by the name, its edge on the ground)
 * @param style  how clients draw its marker: {@code assets/<ns>/storybound/waypoint_styles/<path>.json} in a resource pack
 */
public record WaypointDefinition(StoryText name, WaypointTarget target, int color, Condition conditions,
                                 Optional<ResourceLocation> quest, Optional<String> stage, float radius, AreaLook look,
                                 ResourceLocation style) {
    public static final int DEFAULT_COLOR = 0xFFE8C66A;
    public static final ResourceLocation DEFAULT_STYLE = Storybound.id("default");

    public static final Codec<WaypointDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            StoryText.CODEC.fieldOf("name").forGetter(WaypointDefinition::name),
            WaypointTarget.CODEC.fieldOf("target").forGetter(WaypointDefinition::target),
            ColorCodec.CODEC.optionalFieldOf("color", DEFAULT_COLOR).forGetter(WaypointDefinition::color),
            LogicCodecs.CONDITION.optionalFieldOf("conditions", LogicConditions.ALWAYS).forGetter(WaypointDefinition::conditions),
            ResourceLocation.CODEC.optionalFieldOf("quest").forGetter(WaypointDefinition::quest),
            Codec.STRING.optionalFieldOf("stage").forGetter(WaypointDefinition::stage),
            Codec.floatRange(0f, 1024f).optionalFieldOf("radius", 0f).forGetter(WaypointDefinition::radius),
            AreaLook.MAP_CODEC.forGetter(WaypointDefinition::look),
            ResourceLocation.CODEC.optionalFieldOf("style", DEFAULT_STYLE).forGetter(WaypointDefinition::style)
    ).apply(instance, WaypointDefinition::new));
}
