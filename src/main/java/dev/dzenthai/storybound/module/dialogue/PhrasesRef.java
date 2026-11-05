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
 * What an NPC says for its chatter or a hit (November 5, 2026): a phrases node by its id, or a text of its own written
 * right there ({@code { "text": ... }}), for an NPC that needs one line and no node.
 */
public record PhrasesRef(Optional<ResourceLocation> node, Optional<StoryText> text) {
    private static final Codec<StoryText> OWN = RecordCodecBuilder.create(instance -> instance.group(
            StoryText.CODEC.fieldOf("text").forGetter(text -> text)
    ).apply(instance, text -> text));
    public static final Codec<PhrasesRef> CODEC = Codec.either(ResourceLocation.CODEC, OWN).xmap(
            either -> either.map(PhrasesRef::ofNode, PhrasesRef::ofText),
            ref -> ref.node().<Either<ResourceLocation, StoryText>>map(Either::left).orElseGet(() -> Either.right(ref.text().orElseThrow())));

    public static PhrasesRef ofNode(ResourceLocation node) {
        return new PhrasesRef(Optional.of(node), Optional.empty());
    }

    public static PhrasesRef ofText(StoryText text) {
        return new PhrasesRef(Optional.empty(), Optional.of(text));
    }

    /** The phrases to say: the node's, or the own text as a list of one; null for a node that is not there. */
    @Nullable PhrasesDefinition resolve() {
        if (text.isPresent()) {
            return new PhrasesDefinition(PhrasesDefinition.Order.SEQUENCE, List.of(PhrasesDefinition.Phrase.ofText(text.get())));
        }
        return DialogueRegistries.PHRASES.serverStore().getOrNull(node.orElseThrow());
    }

    /** "phrases mypack:smith" or "own text", for the trace. */
    String describe() {
        return node.map(id -> "phrases " + id).orElse("own text");
    }
}
