package dev.dzenthai.storybound.module.dialogue;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.core.text.StoryText;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.StringRepresentable;

/**
 * {@code data/<ns>/storybound/phrases/<path>.json}: a list of an NPC's short lines, spoken one at a time without a
 * conversation (what games call barks: "Fresh fish!", "Hey, you don't hit your own!"). An NPC binding points at it for
 * its chatter, for a click and for what it says when a player hits it. A line is a text shown in a bubble over the NPC.
 * Only text: a dialogue is the NPC's own choice where it says something ("Phrases / Dialogue" for a click or a hit),
 * and a phrases node names nothing.
 *
 * @param order {@code random}: one at random, never the same twice in a row; {@code sequence}: top to bottom, then the
 *              last one again (lines that build up: "Careful!", "Last warning!"); {@code loop}: top to bottom, then from
 *              the top again (chatter going round)
 */
public record PhrasesDefinition(Order order, List<PhrasesDefinition.Line> lines) {
    /**
     * One of the lines: its text, and its voice-over ({@code sound}: an .ogg of the story's {@code sounds/} or any sound
     * the game knows), played in place of the NPC's voice. Written as the text alone, or {@code { "text": ..., "sound": ... }}.
     */
    public record Line(StoryText text, Optional<ResourceLocation> sound) {
        static Line of(StoryText text) {
            return new Line(text, Optional.empty());
        }
    }

    private static final Codec<Line> VOICED = RecordCodecBuilder.create(instance -> instance.group(
            StoryText.CODEC.fieldOf("text").forGetter(Line::text),
            ResourceLocation.CODEC.optionalFieldOf("sound").forGetter(Line::sound)
    ).apply(instance, Line::new));

    /** An old line {@code { "dialogue": id }}, read only to say where it goes now. */
    private static final Codec<ResourceLocation> OLD_DIALOGUE_LINE = RecordCodecBuilder.create(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf("dialogue").forGetter(id -> id)
    ).apply(instance, id -> id));
    /** A line is a text; a dialogue in its place fails the file with a message saying where it belongs now. */
    private static final Codec<Line> LINE = Codec.either(OLD_DIALOGUE_LINE, Codec.either(VOICED, StoryText.CODEC)).flatXmap(
            either -> either.map(dialogue -> DataResult.error(() -> "A line of phrases is a text; it no longer starts a dialogue (" + dialogue
                    + "). Start it from the NPC instead: when_attacked's \"dialogue\", or a click entry's \"dialogue\""),
                    line -> DataResult.success(line.map(voiced -> voiced, Line::of))),
            line -> DataResult.success(Either.right(line.sound().isPresent() ? Either.left(line) : Either.right(line.text()))));

    public static final Codec<PhrasesDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Order.CODEC.optionalFieldOf("order", Order.RANDOM).forGetter(PhrasesDefinition::order),
            LINE.listOf().optionalFieldOf("lines", List.of()).forGetter(PhrasesDefinition::lines)
    ).apply(instance, PhrasesDefinition::new));

    public enum Order implements StringRepresentable {
        RANDOM("random"),
        SEQUENCE("sequence"),
        // Top to bottom, then from the top again: chatter that goes round, where "sequence" stays on its last.
        LOOP("loop");

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
