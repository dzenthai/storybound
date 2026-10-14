package dev.dzenthai.storybound.module.codex;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.core.logic.Condition;
import dev.dzenthai.storybound.core.logic.LogicCodecs;
import dev.dzenthai.storybound.core.logic.condition.LogicConditions;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

/**
 * When a part of a Codex or Bosses entry opens: {@code { "on": ["seen", "killed"], "count": 5, "conditions": ... }}.
 * Any of the sources reaching {@code count} (sightings or kills; a read counts once) opens it, if the conditions hold then.
 */
public record Unlock(List<Source> on, int count, Condition conditions) {
    /** Clients do not learn how a part opens. */
    public static final Unlock HIDDEN = new Unlock(List.of(), 1, LogicConditions.ALWAYS);

    public static final Codec<Unlock> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Source.CODEC.listOf().fieldOf("on").forGetter(Unlock::on),
            Codec.intRange(1, 100_000).optionalFieldOf("count", 1).forGetter(Unlock::count),
            LogicCodecs.CONDITION.optionalFieldOf("conditions", LogicConditions.ALWAYS).forGetter(Unlock::conditions)
    ).apply(instance, Unlock::new));

    public static Unlock on(Source source) {
        return new Unlock(List.of(source), 1, LogicConditions.ALWAYS);
    }

    /** {@code seen}, {@code killed}, {@code read:<document>} or {@code manual} (only actions, commands and scripts). */
    public sealed interface Source {
        Source SEEN = new Seen();
        Source KILLED = new Killed();
        Source MANUAL = new Manual();

        Codec<Source> CODEC = Codec.STRING.comapFlatMap(Source::parse, Source::serialize);

        private static DataResult<Source> parse(String value) {
            return switch (value) {
                case "seen" -> DataResult.success(SEEN);
                case "killed" -> DataResult.success(KILLED);
                case "manual" -> DataResult.success(MANUAL);
                default -> {
                    if (value.startsWith("read:")) {
                        ResourceLocation document = ResourceLocation.tryParse(value.substring(5));
                        yield document != null ? DataResult.success(new Read(document)) : DataResult.error(() -> "Bad document id in " + value);
                    }
                    yield DataResult.error(() -> "Unknown unlock source '" + value + "': use seen, killed, read:<document> or manual");
                }
            };
        }

        private static String serialize(Source source) {
            return switch (source) {
                case Seen seen -> "seen";
                case Killed killed -> "killed";
                case Manual manual -> "manual";
                case Read read -> "read:" + read.document();
            };
        }

        record Seen() implements Source {}

        record Killed() implements Source {}

        record Manual() implements Source {}

        record Read(ResourceLocation document) implements Source {}
    }
}
