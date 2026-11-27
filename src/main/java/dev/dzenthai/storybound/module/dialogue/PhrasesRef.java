package dev.dzenthai.storybound.module.dialogue;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.core.text.StoryText;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * What an NPC says for its chatter, a click or a hit: a phrases node by its id, or a line of its own written right
 * there ({@code { "text": ..., "sound": ... }}), for an NPC that needs one line and no node.
 */
public record PhrasesRef(Optional<ResourceLocation> node, Optional<PhrasesDefinition.Line> own) {
    private static final Codec<PhrasesDefinition.Line> OWN = RecordCodecBuilder.create(instance -> instance.group(
            StoryText.CODEC.fieldOf("text").forGetter(PhrasesDefinition.Line::text),
            ResourceLocation.CODEC.optionalFieldOf("sound").forGetter(PhrasesDefinition.Line::sound)
    ).apply(instance, PhrasesDefinition.Line::new));
    public static final Codec<PhrasesRef> CODEC = Codec.either(ResourceLocation.CODEC, OWN).xmap(
            either -> either.map(PhrasesRef::ofNode, PhrasesRef::ofLine),
            ref -> ref.node().<Either<ResourceLocation, PhrasesDefinition.Line>>map(Either::left).orElseGet(() -> Either.right(ref.own().orElseThrow())));

    public static PhrasesRef ofNode(ResourceLocation node) {
        return new PhrasesRef(Optional.of(node), Optional.empty());
    }

    public static PhrasesRef ofText(StoryText text) {
        return ofLine(PhrasesDefinition.Line.of(text));
    }

    public static PhrasesRef ofLine(PhrasesDefinition.Line line) {
        return new PhrasesRef(Optional.empty(), Optional.of(line));
    }

    /** The phrases to say: the node's, or the own line as a list of one; null for a node that is not there. */
    @Nullable PhrasesDefinition resolve() {
        if (own.isPresent()) {
            return new PhrasesDefinition(PhrasesDefinition.Order.SEQUENCE, List.of(own.get()));
        }
        return DialogueRegistries.PHRASES.serverStore().getOrNull(node.orElseThrow());
    }

    /** "phrases mypack:smith" or "own text", for the trace. */
    String describe() {
        return node.map(id -> "phrases " + id).orElse("own text");
    }
}
