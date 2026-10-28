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
 *
 * @param completions how many times it was completed (a repeatable quest more than once)
 * @param startedAt   overworld game time of the last start; 0 when unknown (progress saved before it was kept)
 * @param finishedAt  overworld game time of the last completion or failure; 0 while never finished
 */
public record QuestProgress(QuestStatus status, Optional<String> stage, Map<String, Integer> objectives, List<String> log,
                            int completions, long startedAt, long finishedAt) {
    public static final Codec<QuestProgress> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            QuestStatus.CODEC.fieldOf("status").forGetter(QuestProgress::status),
            Codec.STRING.optionalFieldOf("stage").forGetter(QuestProgress::stage),
            Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("objectives", Map.of()).forGetter(QuestProgress::objectives),
            Codec.STRING.listOf().optionalFieldOf("log", List.of()).forGetter(QuestProgress::log),
            Codec.INT.optionalFieldOf("completions", 0).forGetter(QuestProgress::completions),
            Codec.LONG.optionalFieldOf("started_at", 0L).forGetter(QuestProgress::startedAt),
            Codec.LONG.optionalFieldOf("finished_at", 0L).forGetter(QuestProgress::finishedAt)
    ).apply(instance, QuestProgress::new));

    private static final StreamCodec<ByteBuf, QuestStatus> STATUS = ByteBufCodecs.idMapper(index -> QuestStatus.values()[index], QuestStatus::ordinal);
    private static final StreamCodec<ByteBuf, Optional<String>> STAGE = ByteBufCodecs.optional(ByteBufCodecs.STRING_UTF8);
    private static final StreamCodec<ByteBuf, Map<String, Integer>> COUNTS =
            ByteBufCodecs.<ByteBuf, String, Integer, Map<String, Integer>>map(LinkedHashMap::new, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.VAR_INT);
    private static final StreamCodec<ByteBuf, List<String>> LOG = ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list());

    public static final StreamCodec<ByteBuf, QuestProgress> STREAM_CODEC = StreamCodec.of((buf, progress) -> {
        STATUS.encode(buf, progress.status());
        STAGE.encode(buf, progress.stage());
        COUNTS.encode(buf, progress.objectives());
        LOG.encode(buf, progress.log());
        ByteBufCodecs.VAR_INT.encode(buf, progress.completions());
        ByteBufCodecs.VAR_LONG.encode(buf, progress.startedAt());
        ByteBufCodecs.VAR_LONG.encode(buf, progress.finishedAt());
    }, buf -> new QuestProgress(STATUS.decode(buf), STAGE.decode(buf), COUNTS.decode(buf), LOG.decode(buf),
            ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_LONG.decode(buf), ByteBufCodecs.VAR_LONG.decode(buf)));

    public QuestProgress {
        objectives = Map.copyOf(objectives);
        log = List.copyOf(log);
    }

    public QuestProgress(QuestStatus status, Optional<String> stage, Map<String, Integer> objectives, List<String> log) {
        this(status, stage, objectives, log, 0, 0, 0);
    }

    public static QuestProgress started(String firstStage) {
        return started(firstStage, 0);
    }

    /** A new start at {@code now} (overworld game time). */
    public static QuestProgress started(String firstStage, long now) {
        return new QuestProgress(QuestStatus.ACTIVE, Optional.ofNullable(firstStage), Map.of(), List.of(), 0, now, 0);
    }

    /** A repeatable quest started again: the counters and the log start over, the completions stay. */
    public QuestProgress restarted(String firstStage, long now) {
        return new QuestProgress(QuestStatus.ACTIVE, Optional.ofNullable(firstStage), Map.of(), List.of(), completions, now, 0);
    }

    /**
     * A finished quest active again at the stage it ended on, its time counting from {@code now}: that stage leaves
     * the log again (it is the current one), and its counters start over when {@code freshCounters} (it ended with
     * them all done, so they could never move again).
     */
    public QuestProgress resumed(long now, boolean freshCounters) {
        List<String> next = new ArrayList<>(log);
        if (stage.isPresent() && !next.isEmpty() && next.getLast().equals(stage.get())) {
            next.removeLast();
        }
        return new QuestProgress(QuestStatus.ACTIVE, stage, freshCounters ? Map.of() : objectives, next, completions, now, finishedAt);
    }

    /** Puts an active quest at another stage without logging the current one (which no longer exists). */
    public QuestProgress movedTo(String next) {
        return new QuestProgress(status, Optional.of(next), Map.of(), log, completions, startedAt, finishedAt);
    }

    /** The quest's time (its time limit) counts from {@code now}. */
    public QuestProgress startedNow(long now) {
        return new QuestProgress(status, stage, objectives, log, completions, now, finishedAt);
    }

    public int count(String objective) {
        return objectives.getOrDefault(objective, 0);
    }

    public QuestProgress withStatus(QuestStatus status) {
        return new QuestProgress(status, stage, objectives, log, completions, startedAt, finishedAt);
    }

    /** Moves to another stage: the current one goes to the log and the counters start over. */
    public QuestProgress withStage(String next) {
        return new QuestProgress(status, Optional.of(next), Map.of(), withCurrentLogged(), completions, startedAt, finishedAt);
    }

    /** Ends the quest: the current stage goes to the log. */
    public QuestProgress finished(QuestStatus status) {
        return finished(status, finishedAt);
    }

    /** Ends the quest at {@code now}; a completion is counted. */
    public QuestProgress finished(QuestStatus status, long now) {
        return new QuestProgress(status, stage, objectives, withCurrentLogged(),
                completions + (status == QuestStatus.COMPLETED ? 1 : 0), startedAt, now);
    }

    public QuestProgress withCount(String objective, int count) {
        Map<String, Integer> next = new LinkedHashMap<>(objectives);
        next.put(objective, count);
        return new QuestProgress(status, stage, next, log, completions, startedAt, finishedAt);
    }

    private List<String> withCurrentLogged() {
        List<String> next = new ArrayList<>(log);
        stage.filter(current -> !next.contains(current)).ifPresent(next::add);
        return next;
    }
}
