package dev.dzenthai.storybound.module.music;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.core.logic.Condition;
import dev.dzenthai.storybound.core.logic.LogicCodecs;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

/**
 * {@code data/<ns>/storybound/music/<path>.json}: a place's music, a track played while its conditions hold for the
 * player (where they are: a structure, a biome, a dimension; and the story: a quest's state, a flag), when no dialogue and
 * no stage of their tracked quest has music of its own.
 *
 * @param track      the sound: an .ogg of the story's {@code sounds/} folder or any sound the game knows
 * @param conditions when it plays; always without them
 * @param priority   of several whose conditions hold, the highest plays (equal ones: the first by id)
 */
public record MusicDefinition(ResourceLocation track, Optional<Condition> conditions, int priority) {
    public static final Codec<MusicDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf("track").forGetter(MusicDefinition::track),
            LogicCodecs.CONDITION.optionalFieldOf("conditions").forGetter(MusicDefinition::conditions),
            Codec.INT.optionalFieldOf("priority", 0).forGetter(MusicDefinition::priority)
    ).apply(instance, MusicDefinition::new));
}
