package dev.dzenthai.storybound.module.boss;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

/** A player's Bosses entries: encountered or not, open sections, victories and statistics. Always personal. */
public final class BossData {
    public static final Codec<BossData> CODEC = Record.CODEC.listOf().xmap(BossData::new, data -> List.copyOf(data.records.values()));

    private final Map<ResourceLocation, Record> records = new LinkedHashMap<>();

    public BossData() {}

    private BossData(List<Record> list) {
        list.forEach(record -> records.put(record.boss(), record));
    }

    public Record get(ResourceLocation boss) {
        return records.getOrDefault(boss, Record.unknown(boss));
    }

    /** Bosses the player has met, for the Bosses tab. */
    public List<Record> known() {
        return records.values().stream().filter(Record::encountered).toList();
    }

    void put(Record record) {
        records.put(record.boss(), record);
    }

    boolean remove(ResourceLocation boss) {
        return records.remove(boss) != null;
    }

    /**
     * @param firstVictory epoch milliseconds of the first victory, 0 before it
     * @param bestTicks    the shortest fight, first hit to death, in ticks; -1 before the first victory
     * @param seen         sightings, counted towards sections that open on {@code seen}
     */
    public record Record(ResourceLocation boss, boolean encountered, List<String> sections, int victories, long firstVictory, long bestTicks, int seen) {
        static final Codec<Record> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("boss").forGetter(Record::boss),
                Codec.BOOL.optionalFieldOf("encountered", false).forGetter(Record::encountered),
                Codec.STRING.listOf().optionalFieldOf("sections", List.of()).forGetter(Record::sections),
                Codec.INT.optionalFieldOf("victories", 0).forGetter(Record::victories),
                Codec.LONG.optionalFieldOf("first_victory", 0L).forGetter(Record::firstVictory),
                Codec.LONG.optionalFieldOf("best_ticks", -1L).forGetter(Record::bestTicks),
                Codec.INT.optionalFieldOf("seen", 0).forGetter(Record::seen)
        ).apply(instance, Record::new));

        public static final StreamCodec<RegistryFriendlyByteBuf, Record> STREAM_CODEC = ByteBufCodecs.fromCodecWithRegistries(CODEC);

        static Record unknown(ResourceLocation boss) {
            return new Record(boss, false, List.of(), 0, 0L, -1L, 0);
        }

        public boolean isUnlocked(String section) {
            return sections.contains(section);
        }

        Record encounter() {
            return new Record(boss, true, sections, victories, firstVictory, bestTicks, seen);
        }

        Record sighted() {
            return new Record(boss, true, sections, victories, firstVictory, bestTicks, Math.min(100_000, seen + 1));
        }

        Record victory(long now, long ticks) {
            long best = bestTicks < 0 ? ticks : Math.min(bestTicks, ticks);
            return new Record(boss, true, sections, victories + 1, firstVictory == 0 ? now : firstVictory, best, seen);
        }

        Record open(String section) {
            return sections.contains(section) ? this
                    : new Record(boss, encountered, Stream.concat(sections.stream(), Stream.of(section)).toList(), victories, firstVictory, bestTicks, seen);
        }
    }
}
