package dev.dzenthai.storybound.core.state;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Where a quest stands in one scope: its status, the current stage, objective counters of that
 * stage and the stages already passed, in order (they make the quest's log). Immutable.
 */
public record QuestProgress(QuestStatus status, Optional<String> stage, Map<String, Integer> objectives, List<String> log) {
    public static final Codec<QuestProgress> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            QuestStatus.CODEC.fieldOf("status").forGetter(QuestProgress::status),
            Codec.STRING.optionalFieldOf("stage").forGetter(QuestProgress::stage),
            Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("objectives", Map.of()).forGetter(QuestProgress::objectives),
            Codec.STRING.listOf().optionalFieldOf("log", List.of()).forGetter(QuestProgress::log)
    ).apply(instance, QuestProgress::new));

    public static final StreamCodec<ByteBuf, QuestProgress> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.idMapper(index -> QuestStatus.values()[index], QuestStatus::ordinal), QuestProgress::status,
            ByteBufCodecs.optional(ByteBufCodecs.STRING_UTF8), QuestProgress::stage,
            ByteBufCodecs.<ByteBuf, String, Integer, Map<String, Integer>>map(LinkedHashMap::new, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.VAR_INT), QuestProgress::objectives,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), QuestProgress::log,
            QuestProgress::new);

    public QuestProgress {
        objectives = Map.copyOf(objectives);
        log = List.copyOf(log);
    }

    public static QuestProgress started(String firstStage) {
        return new QuestProgress(QuestStatus.ACTIVE, Optional.ofNullable(firstStage), Map.of(), List.of());
    }

    public int count(String objective) {
        return objectives.getOrDefault(objective, 0);
    }

    public QuestProgress withStatus(QuestStatus status) {
        return new QuestProgress(status, stage, objectives, log);
    }

    /** Moves to another stage: the current one goes to the log and the counters start over. */
    public QuestProgress withStage(String next) {
        return new QuestProgress(status, Optional.of(next), Map.of(), withCurrentLogged());
    }

    /** Ends the quest: the current stage goes to the log. */
    public QuestProgress finished(QuestStatus status) {
        return new QuestProgress(status, stage, objectives, withCurrentLogged());
    }

    public QuestProgress withCount(String objective, int count) {
        Map<String, Integer> next = new LinkedHashMap<>(objectives);
        next.put(objective, count);
        return new QuestProgress(status, stage, next, log);
    }

    private List<String> withCurrentLogged() {
        List<String> next = new ArrayList<>(log);
        stage.filter(current -> !next.contains(current)).ifPresent(next::add);
        return next;
    }
}
