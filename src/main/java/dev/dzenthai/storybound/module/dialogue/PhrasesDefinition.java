package dev.dzenthai.storybound.module.dialogue;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.core.text.StoryText;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.StringRepresentable;

/**
 * {@code data/<ns>/storybound/phrases/<path>.json} (November 5, 2026): a list of an NPC's short lines, spoken one at a
 * time without a conversation (what games call barks: "Fresh fish!", "Hey, you don't hit your own!"). An NPC binding
 * points at it for its chatter and for what it says when a player hits it. A line is a text shown in a bubble over the
 * NPC, or a dialogue started with the player (in bubbles, subtitles or on the screen, as that dialogue says).
 *
 * @param order {@code random}: one at random, never the same twice in a row; {@code sequence}: top to bottom, then the
 *              last one again
 */
public record PhrasesDefinition(Order order, List<Phrase> lines) {
    public static final Codec<PhrasesDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Order.CODEC.optionalFieldOf("order", Order.RANDOM).forGetter(PhrasesDefinition::order),
            Phrase.CODEC.listOf().optionalFieldOf("lines", List.of()).forGetter(PhrasesDefinition::lines)
    ).apply(instance, PhrasesDefinition::new));

    /** A line: a text said in a bubble, or a dialogue started with the player. */
    public record Phrase(Optional<StoryText> text, Optional<ResourceLocation> dialogue) {
        /** {@code { "dialogue": "mypack:betrayal" }} for a dialogue; anything else is a text, as everywhere. */
        private static final Codec<ResourceLocation> DIALOGUE = RecordCodecBuilder.create(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("dialogue").forGetter(id -> id)
        ).apply(instance, id -> id));
        public static final Codec<Phrase> CODEC = Codec.either(DIALOGUE, StoryText.CODEC).xmap(
                either -> either.map(Phrase::ofDialogue, Phrase::ofText),
                phrase -> phrase.dialogue().<Either<ResourceLocation, StoryText>>map(Either::left)
                        .orElseGet(() -> Either.right(phrase.text().orElseThrow())));

        public static Phrase ofText(StoryText text) {
            return new Phrase(Optional.of(text), Optional.empty());
        }

        public static Phrase ofDialogue(ResourceLocation dialogue) {
            return new Phrase(Optional.empty(), Optional.of(dialogue));
        }
    }

    public enum Order implements StringRepresentable {
        RANDOM("random"),
        SEQUENCE("sequence");

        public static final Codec<Order> CODEC = StringRepresentable.fromEnum(Order::values);

        private final String name;

        Order(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }
}
