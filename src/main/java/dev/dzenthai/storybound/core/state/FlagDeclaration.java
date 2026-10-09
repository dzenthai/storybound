package dev.dzenthai.storybound.core.state;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;

/**
 * Declaration of a flag or variable ({@code data/<ns>/storybound/flags/*.json}).
 * Optional, but lets the validator catch typos in keys.
 * <pre>{ "type": "int", "default": 0, "scope": "party", "sync": true, "description": "..." }</pre>
 */
public record FlagDeclaration(StoryValue defaultValue, StoryScope scope, boolean sync, Optional<String> description) {
    public static final Codec<FlagDeclaration> CODEC = StoryValueType.CODEC.dispatch("type", FlagDeclaration::type, FlagDeclaration::codecFor);

    public StoryValueType type() {
        return defaultValue.type();
    }

    private static MapCodec<FlagDeclaration> codecFor(StoryValueType type) {
        return RecordCodecBuilder.mapCodec(instance -> instance.group(
                type.valueCodec().optionalFieldOf("default", type.zero()).forGetter(FlagDeclaration::defaultValue),
                StoryScope.CODEC.optionalFieldOf("scope", StoryScope.PLAYER).forGetter(FlagDeclaration::scope),
                Codec.BOOL.optionalFieldOf("sync", false).forGetter(FlagDeclaration::sync),
                Codec.STRING.optionalFieldOf("description").forGetter(FlagDeclaration::description)
        ).apply(instance, FlagDeclaration::new));
    }
}
