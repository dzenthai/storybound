package dev.dzenthai.storybound.core.state;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;

/**
 * One migrations file ({@code migrations/<name>.json}): ids renamed since saves were made, by kind, old to new.
 * {@code stages} and {@code objectives} rename inside a quest, named by its new id:
 * <pre>
 * { "quests": { "mypack:hunt": "mypack:wolf_hunt" },
 *   "stages": { "mypack:wolf_hunt": { "start": "find_tracks" } },
 *   "flags": { "mypack:met_elder": "mypack:elder_met" } }
 * </pre>
 *
 * @see StoryMigrations
 */
public record Migration(Map<StoryMigrations.Kind, Map<ResourceLocation, ResourceLocation>> ids,
                        Map<ResourceLocation, Map<String, String>> stages, Map<ResourceLocation, Map<String, String>> objectives) {
    private static final Codec<Map<ResourceLocation, ResourceLocation>> IDS = Codec.unboundedMap(ResourceLocation.CODEC, ResourceLocation.CODEC);
    private static final Codec<Map<ResourceLocation, Map<String, String>>> INSIDE_QUESTS =
            Codec.unboundedMap(ResourceLocation.CODEC, Codec.unboundedMap(Codec.STRING, Codec.STRING));

    public static final Codec<Migration> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            field(StoryMigrations.Kind.FLAGS),
            field(StoryMigrations.Kind.QUESTS),
            field(StoryMigrations.Kind.EVENTS),
            field(StoryMigrations.Kind.DIALOGUES),
            field(StoryMigrations.Kind.NPC_BINDINGS),
            field(StoryMigrations.Kind.DOCUMENTS),
            field(StoryMigrations.Kind.NOTES),
            field(StoryMigrations.Kind.CODEX),
            field(StoryMigrations.Kind.BOSSES),
            field(StoryMigrations.Kind.WAYPOINTS),
            INSIDE_QUESTS.optionalFieldOf("stages", Map.of()).forGetter(Migration::stages),
            INSIDE_QUESTS.optionalFieldOf("objectives", Map.of()).forGetter(Migration::objectives)
    ).apply(instance, (flags, quests, events, dialogues, npcBindings, documents, notes, codex, bosses, waypoints, stages, objectives) -> {
        Map<StoryMigrations.Kind, Map<ResourceLocation, ResourceLocation>> ids = new EnumMap<>(StoryMigrations.Kind.class);
        ids.put(StoryMigrations.Kind.FLAGS, flags);
        ids.put(StoryMigrations.Kind.QUESTS, quests);
        ids.put(StoryMigrations.Kind.EVENTS, events);
        ids.put(StoryMigrations.Kind.DIALOGUES, dialogues);
        ids.put(StoryMigrations.Kind.NPC_BINDINGS, npcBindings);
        ids.put(StoryMigrations.Kind.DOCUMENTS, documents);
        ids.put(StoryMigrations.Kind.NOTES, notes);
        ids.put(StoryMigrations.Kind.CODEX, codex);
        ids.put(StoryMigrations.Kind.BOSSES, bosses);
        ids.put(StoryMigrations.Kind.WAYPOINTS, waypoints);
        return new Migration(ids, stages, objectives);
    }));

    public Migration {
        ids = Map.copyOf(ids);
        stages = Map.copyOf(stages);
        objectives = Map.copyOf(objectives);
    }

    public Map<ResourceLocation, ResourceLocation> renames(StoryMigrations.Kind kind) {
        return ids.getOrDefault(kind, Map.of());
    }

    private static RecordCodecBuilder<Migration, Map<ResourceLocation, ResourceLocation>> field(StoryMigrations.Kind kind) {
        MapCodec<Map<ResourceLocation, ResourceLocation>> field = IDS.optionalFieldOf(kind.field(), Map.of());
        return field.forGetter(migration -> migration.renames(kind));
    }
}
