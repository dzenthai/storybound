package dev.dzenthai.storybound.module.codex;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/** A player's Codex: open sections per entry, and sightings and kills counted towards the next ones. Always personal. */
public final class CodexData {
    /** Counters stop here; no unlock needs more. */
    private static final int MAX_COUNT = 100_000;

    public static final Codec<CodexData> CODEC = Progress.CODEC.listOf().xmap(CodexData::new,
            data -> data.entries.entrySet().stream().map(entry -> entry.getValue().withId(entry.getKey())).toList());

    private final Map<ResourceLocation, Progress> entries = new LinkedHashMap<>();

    public CodexData() {}

    private CodexData(List<Progress> list) {
        list.forEach(progress -> entries.put(progress.id(), progress));
    }

    public Set<String> sections(ResourceLocation entry) {
        Progress progress = entries.get(entry);
        return progress == null ? Set.of() : Set.copyOf(progress.sections());
    }

    public boolean isUnlocked(ResourceLocation entry, String section) {
        return sections(entry).contains(section);
    }

    /** Entries with at least one open section, each with its open sections. */
    public Map<ResourceLocation, List<String>> unlocked() {
        Map<ResourceLocation, List<String>> result = new LinkedHashMap<>();
        entries.forEach((id, progress) -> {
            if (!progress.sections().isEmpty()) {
                result.put(id, List.copyOf(progress.sections()));
            }
        });
        return result;
    }

    public int seen(ResourceLocation entry) {
        Progress progress = entries.get(entry);
        return progress == null ? 0 : progress.seen();
    }

    public int kills(ResourceLocation entry) {
        Progress progress = entries.get(entry);
        return progress == null ? 0 : progress.kills();
    }

    int countSeen(ResourceLocation entry) {
        Progress progress = get(entry);
        int seen = Math.min(MAX_COUNT, progress.seen() + 1);
        entries.put(entry, new Progress(entry, progress.sections(), seen, progress.kills()));
        return seen;
    }

    int countKill(ResourceLocation entry) {
        Progress progress = get(entry);
        int kills = Math.min(MAX_COUNT, progress.kills() + 1);
        entries.put(entry, new Progress(entry, progress.sections(), progress.seen(), kills));
        return kills;
    }

    /** @return true if the section was closed */
    boolean open(ResourceLocation entry, String section) {
        Progress progress = get(entry);
        if (progress.sections().contains(section)) {
            return false;
        }
        Set<String> sections = new LinkedHashSet<>(progress.sections());
        sections.add(section);
        entries.put(entry, new Progress(entry, List.copyOf(sections), progress.seen(), progress.kills()));
        return true;
    }

    /** Closes one section, or the whole entry (with its counters) when {@code section} is null. */
    boolean close(ResourceLocation entry, String section) {
        Progress progress = entries.get(entry);
        if (progress == null) {
            return false;
        }
        if (section == null) {
            entries.remove(entry);
            return true;
        }
        List<String> sections = progress.sections().stream().filter(id -> !id.equals(section)).toList();
        entries.put(entry, new Progress(entry, sections, progress.seen(), progress.kills()));
        return sections.size() != progress.sections().size();
    }

    private Progress get(ResourceLocation entry) {
        return entries.computeIfAbsent(entry, id -> new Progress(id, List.of(), 0, 0));
    }

    record Progress(ResourceLocation id, List<String> sections, int seen, int kills) {
        static final Codec<Progress> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("id").forGetter(Progress::id),
                Codec.STRING.listOf().optionalFieldOf("sections", List.of()).forGetter(Progress::sections),
                Codec.INT.optionalFieldOf("seen", 0).forGetter(Progress::seen),
                Codec.INT.optionalFieldOf("kills", 0).forGetter(Progress::kills)
        ).apply(instance, Progress::new));

        Progress withId(ResourceLocation id) {
            return new Progress(id, sections, seen, kills);
        }
    }
}
