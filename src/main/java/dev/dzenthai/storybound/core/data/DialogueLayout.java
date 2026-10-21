package dev.dzenthai.storybound.core.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Map;

/**
 * Where the dev mode draws a dialogue's nodes, in {@code config/storybound/dialogue_layouts/} under the dialogue's
 * id: {@code { "nodes": { "greet": { "x": 40, "y": 40 } } }}. Kept apart from the dialogue, which the game reads and
 * which has no place for positions; the game never reads layouts.
 */
public record DialogueLayout(Map<String, Position> nodes) {
    public static final Codec<DialogueLayout> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.unboundedMap(Codec.STRING, Position.CODEC).optionalFieldOf("nodes", Map.of()).forGetter(DialogueLayout::nodes)
    ).apply(instance, DialogueLayout::new));

    public record Position(int x, int y) {
        public static final Codec<Position> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.fieldOf("x").forGetter(Position::x),
                Codec.INT.fieldOf("y").forGetter(Position::y)
        ).apply(instance, Position::new));
    }
}
