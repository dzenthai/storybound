package dev.dzenthai.storybound.core.state;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;

/**
 * Declaration of a flag or variable ({@code data/<ns>/storybound/flags/*.json}).
 * Optional, but lets the validator catch typos in keys.
 * <pre>{ "type": "int", "default": 0, "scope": "party", "sync": true, "description": "...", "min": -50, "max": 50 }</pre>
 *
 * @param min a number flag's least value: what writes it is held to it
 * @param max its greatest value
 */
public record FlagDeclaration(StoryValue defaultValue, StoryScope scope, boolean sync, Optional<String> description,
                              Optional<Integer> min, Optional<Integer> max) {
    public static final Codec<FlagDeclaration> CODEC = StoryValueType.CODEC.dispatch("type", FlagDeclaration::type, FlagDeclaration::codecFor);

    public FlagDeclaration(StoryValue defaultValue, StoryScope scope, boolean sync, Optional<String> description) {
        this(defaultValue, scope, sync, description, Optional.empty(), Optional.empty());
    }

    public StoryValueType type() {
        return defaultValue.type();
    }

    /** A number held to {@code min} and {@code max} where they are given; anything else as it is. */
    public StoryValue bounded(StoryValue value) {
        if (value instanceof StoryValue.Int(int number)) {
            int held = Math.max(min.orElse(Integer.MIN_VALUE), Math.min(max.orElse(Integer.MAX_VALUE), number));
            return held == number ? value : new StoryValue.Int(held);
        }
        return value;
    }

    private static MapCodec<FlagDeclaration> codecFor(StoryValueType type) {
        return RecordCodecBuilder.mapCodec(instance -> instance.group(
                type.valueCodec().optionalFieldOf("default", type.zero()).forGetter(FlagDeclaration::defaultValue),
                StoryScope.CODEC.optionalFieldOf("scope", StoryScope.PLAYER).forGetter(FlagDeclaration::scope),
                Codec.BOOL.optionalFieldOf("sync", false).forGetter(FlagDeclaration::sync),
                Codec.STRING.optionalFieldOf("description").forGetter(FlagDeclaration::description),
                Codec.INT.optionalFieldOf("min").forGetter(FlagDeclaration::min),
                Codec.INT.optionalFieldOf("max").forGetter(FlagDeclaration::max)
        ).apply(instance, FlagDeclaration::new));
    }
}
