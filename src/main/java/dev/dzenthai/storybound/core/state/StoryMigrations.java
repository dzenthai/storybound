package dev.dzenthai.storybound.core.state;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.CoreDefinitions;
import dev.dzenthai.storybound.core.data.DefinitionType;
import dev.dzenthai.storybound.core.data.DefinitionTypes;
import dev.dzenthai.storybound.core.data.ValidationIssue;
import dev.dzenthai.storybound.core.data.ValidationService;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Renamed ids carried over into saves, so a story can rename a quest, a flag or a dialogue after release without
 * players losing their progress. The renames come from {@code migrations/*.json} (see {@link Migration}) and are
 * applied to the world's and parties' data when the server starts and after {@code /reload}, and to a player's data
 * when they join. Applying them again changes nothing, so no version is kept: an old id found in a save moves to its
 * new id; when the new id already has progress, that progress stays and the old one is dropped. A rename whose old id
 * is still defined is ignored (the validator says so): moving progress away from a live quest would be a mistake.
 *
 * <p>Modules move their own saved data through {@link #registerPlayer} and {@link #registerServer}; story values whose
 * key is built from an id ({@code storybound:once/<ns>/<path>}) through {@link #registerKeyPrefix}. Ids written in
 * items, entities and scheduled actions are not rewritten; the code reading them asks {@link #current()}.
 */
public final class StoryMigrations {
    /** What a migration renames, by its field in the file. */
    public enum Kind {
        FLAGS("flags", null),
        QUESTS("quests", "quest"),
        EVENTS("events", "event"),
        DIALOGUES("dialogues", "dialogue"),
        NPC_BINDINGS("npc_bindings", "npc_binding"),
        DOCUMENTS("documents", "document"),
        NOTES("notes", null),
        CODEX("codex", "codex"),
        BOSSES("bosses", "boss"),
        WAYPOINTS("waypoints", null);

        private final String field;
        private final @Nullable String type;

        Kind(String field, @Nullable String type) {
            this.field = field;
            this.type = type;
        }

        public String field() {
            return field;
        }

        /** The definitions of this kind; {@code null} for ids that need no file (flags, notes, added waypoints). */
        public @Nullable DefinitionType<?> definitions() {
            return type == null ? null : DefinitionTypes.get(Storybound.id(type));
        }
    }

    /** Moves one player's own saved data (an attachment); returns how many ids moved. */
    @FunctionalInterface
    public interface PlayerMigrator {
        int migrate(ServerPlayer player, Renames renames);
    }

    /** Moves saved data of the whole server (a {@code SavedData}); returns how many ids moved. */
    @FunctionalInterface
    public interface ServerMigrator {
        int migrate(MinecraftServer server, Renames renames);
    }

    /**
     * What a quest has now, so a renamed stage or objective moves only where it still means the same thing (October 29,
     * 2026; before, a rename of objective "0" moved every stage's "0", and a stage given the old name again was renamed
     * away on every join).
     */
    public interface QuestShape {
        /** Whether the quest is defined; for one that is not, renames move as written. */
        boolean defines(ResourceLocation quest);

        boolean hasStage(ResourceLocation quest, String stage);

        /** Whether the stage has an objective of that id (its own, or its position when it has none). */
        boolean hasObjective(ResourceLocation quest, String stage, String objective);
    }

    /** Story values keyed {@code storybound:<prefix><ns>/<path>} and then {@code tail} more segments. */
    private record KeyPrefix(String prefix, Kind kind, int tail) {}

    /** Long enough for any real chain of renames; a longer one is a loop. */
    private static final int MAX_CHAIN = 32;

    private static final List<PlayerMigrator> PLAYER = new CopyOnWriteArrayList<>();
    private static final List<ServerMigrator> SERVER = new CopyOnWriteArrayList<>();
    private static final List<KeyPrefix> KEY_PREFIXES = new CopyOnWriteArrayList<>();
    private static volatile Renames current = Renames.NONE;
    private static volatile @Nullable QuestShape questShape;

    private StoryMigrations() {}

    public static void register(IEventBus gameBus) {
        // HIGHEST: before anything sends a joining player their state.
        gameBus.addListener(EventPriority.HIGHEST, StoryMigrations::onDatapackSync);
        gameBus.addListener(StoryMigrations::onServerStarted);
        ValidationService.registerValidator(StoryMigrations::validate);
    }

    public static void registerPlayer(PlayerMigrator migrator) {
        PLAYER.add(migrator);
    }

    public static void registerServer(ServerMigrator migrator) {
        SERVER.add(migrator);
    }

    /** The quest module tells what its quests have; without it, renamed stages and objectives always move. */
    public static void registerQuestShape(@Nullable QuestShape shape) {
        questShape = shape;
    }

    /** Story values whose key carries an id: {@code storybound:<prefix><ns>/<path>}, then {@code tail} segments. */
    public static void registerKeyPrefix(String prefix, Kind kind, int tail) {
        KEY_PREFIXES.add(new KeyPrefix(prefix, kind, tail));
    }

    /** The renames of the last data load. */
    public static Renames current() {
        return current;
    }

    /** Rebuilds the renames from the loaded migration files. Public for tests. */
    public static Renames reload() {
        Map<Kind, Map<ResourceLocation, ResourceLocation>> ids = new EnumMap<>(Kind.class);
        Map<ResourceLocation, Map<String, String>> stages = new HashMap<>();
        Map<ResourceLocation, Map<String, String>> objectives = new HashMap<>();
        new TreeMap<>(CoreDefinitions.MIGRATIONS.serverStore().all()).values().forEach(migration -> {
            for (Kind kind : Kind.values()) {
                migration.renames(kind).forEach((from, to) -> {
                    if (!from.equals(to) && !defined(kind, from)) {
                        ids.computeIfAbsent(kind, k -> new HashMap<>()).putIfAbsent(from, to);
                    }
                });
            }
            migration.stages().forEach((quest, renames) -> renames.forEach((from, to) -> stages.computeIfAbsent(quest, q -> new HashMap<>()).putIfAbsent(from, to)));
            migration.objectives().forEach((quest, renames) -> renames.forEach((from, to) -> objectives.computeIfAbsent(quest, q -> new HashMap<>()).putIfAbsent(from, to)));
        });
        current = new Renames(ids, stages, objectives);
        return current;
    }

    /** Moves the world's and parties' saved data, and the modules' server data, to the new ids. */
    public static int migrate(MinecraftServer server) {
        Renames renames = current;
        if (renames.isEmpty()) {
            return 0;
        }
        int moved = WorldStoryData.get(server).container().migrate(renames);
        for (StoryContainer container : PartyData.get(server).containers()) {
            moved += container.migrate(renames);
        }
        for (ServerMigrator migrator : SERVER) {
            moved += migrator.migrate(server, renames);
        }
        if (moved > 0) {
            Storybound.LOGGER.info("Storybound: moved {} saved ids of the world and parties to their new names (migrations)", moved);
        }
        return moved;
    }

    /** Moves the player's saved data to the new ids. */
    public static int migrate(ServerPlayer player) {
        Renames renames = current;
        if (renames.isEmpty()) {
            return 0;
        }
        int moved = player.getData(StoryAttachments.PLAYER_STATE).migrate(renames);
        for (PlayerMigrator migrator : PLAYER) {
            moved += migrator.migrate(player, renames);
        }
        if (moved > 0) {
            Storybound.LOGGER.info("Storybound: moved {} saved ids of {} to their new names (migrations)", moved, player.getGameProfile().getName());
        }
        return moved;
    }

    /**
     * Renames a map's keys in place, keeping its order; {@code rewrite} may change a value for its (new) key. A moved
     * entry whose new key is already there is dropped: the progress under the new id wins.
     *
     * @return how many keys moved or values changed
     */
    public static <V> int renameKeys(Map<ResourceLocation, V> map, UnaryOperator<ResourceLocation> rename, BiFunction<ResourceLocation, V, V> rewrite) {
        Map<ResourceLocation, ResourceLocation> targets = new LinkedHashMap<>();
        map.keySet().forEach(key -> targets.put(key, rename.apply(key)));
        if (targets.entrySet().stream().allMatch(entry -> entry.getKey().equals(entry.getValue()))
                && map.entrySet().stream().allMatch(entry -> rewrite.apply(entry.getKey(), entry.getValue()).equals(entry.getValue()))) {
            return 0;
        }
        int changed = 0;
        Map<ResourceLocation, V> next = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, V> entry : map.entrySet()) {
            ResourceLocation to = targets.get(entry.getKey());
            boolean moved = !to.equals(entry.getKey());
            if (moved && (next.containsKey(to) || (map.containsKey(to) && targets.get(to).equals(to)))) {
                changed++;
                continue;
            }
            V value = rewrite.apply(to, entry.getValue());
            if (moved || !value.equals(entry.getValue())) {
                changed++;
            }
            next.put(to, value);
        }
        map.clear();
        map.putAll(next);
        return changed;
    }

    public static <V> int renameKeys(Map<ResourceLocation, V> map, Kind kind, Renames renames) {
        return renameKeys(map, id -> renames.id(kind, id), (id, value) -> value);
    }

    /** The ids renamed, in order, without duplicates. */
    public static List<ResourceLocation> rename(List<ResourceLocation> ids, Kind kind, Renames renames) {
        Set<ResourceLocation> next = new LinkedHashSet<>();
        ids.forEach(id -> next.add(renames.id(kind, id)));
        return List.copyOf(next);
    }

    private static boolean defined(Kind kind, ResourceLocation id) {
        DefinitionType<?> type = kind.definitions();
        return type != null && type.serverStore().getOrNull(id) != null;
    }

    private static void onServerStarted(ServerStartedEvent event) {
        reload();
        migrate(event.getServer());
    }

    private static void onDatapackSync(OnDatapackSyncEvent event) {
        if (event.getPlayer() == null) {
            // A reload: the files may have changed.
            reload();
            migrate(event.getPlayerList().getServer());
        }
        event.getRelevantPlayers().forEach(StoryMigrations::migrate);
    }

    private static void validate(Consumer<ValidationIssue> sink) {
        DefinitionType<Migration> type = CoreDefinitions.MIGRATIONS;
        Map<Kind, Map<ResourceLocation, ResourceLocation>> seen = new EnumMap<>(Kind.class);
        new TreeMap<>(type.serverStore().all()).forEach((file, migration) -> {
            for (Kind kind : Kind.values()) {
                migration.renames(kind).forEach((from, to) -> {
                    String what = kind.field() + " " + from + " → " + to;
                    ResourceLocation earlier = seen.computeIfAbsent(kind, k -> new HashMap<>()).putIfAbsent(from, to);
                    if (from.equals(to)) {
                        sink.accept(ValidationIssue.warning(type.id(), file, what + ": renames to itself"));
                    } else if (earlier != null && !earlier.equals(to)) {
                        sink.accept(ValidationIssue.warning(type.id(), file, what + ": an earlier file renames it to " + earlier + ", which wins"));
                    } else if (defined(kind, from)) {
                        sink.accept(ValidationIssue.warning(type.id(), file, what + ": " + from + " is still defined, so the rename is ignored"));
                    } else if (current.chainLoops(kind, from)) {
                        sink.accept(ValidationIssue.error(type.id(), file, what + ": the renames go round in a loop"));
                    } else if (kind.definitions() != null && !defined(kind, current.id(kind, from))) {
                        sink.accept(ValidationIssue.warning(type.id(), file, what + ": " + current.id(kind, from) + " is not defined"));
                    }
                });
            }
        });
    }

    /** Every rename in force, merged from all migration files (the first file by id wins a conflict). */
    public static final class Renames {
        public static final Renames NONE = new Renames(Map.of(), Map.of(), Map.of());

        private final Map<Kind, Map<ResourceLocation, ResourceLocation>> ids;
        private final Map<ResourceLocation, Map<String, String>> stages;
        private final Map<ResourceLocation, Map<String, String>> objectives;
        private final boolean empty;

        public Renames(Map<Kind, Map<ResourceLocation, ResourceLocation>> ids, Map<ResourceLocation, Map<String, String>> stages,
                       Map<ResourceLocation, Map<String, String>> objectives) {
            this.ids = ids;
            this.stages = stages;
            this.objectives = objectives;
            this.empty = ids.values().stream().allMatch(Map::isEmpty) && stages.isEmpty() && objectives.isEmpty();
        }

        public boolean isEmpty() {
            return empty;
        }

        /** The id's current name, following renames of renames. */
        public ResourceLocation id(Kind kind, ResourceLocation id) {
            return follow(ids.getOrDefault(kind, Map.of()), id);
        }

        /** A stage of the quest (by its new id). */
        public String stage(ResourceLocation quest, String stage) {
            return follow(stages.getOrDefault(quest, Map.of()), stage);
        }

        public String objective(ResourceLocation quest, String objective) {
            return follow(objectives.getOrDefault(quest, Map.of()), objective);
        }

        /** A story value's key: a flag's own, or one a module builds from an id ({@code storybound:once/<ns>/<path>}). */
        public ResourceLocation valueKey(ResourceLocation key) {
            ResourceLocation renamed = id(Kind.FLAGS, key);
            if (!renamed.equals(key) || !key.getNamespace().equals(Storybound.MOD_ID)) {
                return renamed;
            }
            for (KeyPrefix rule : KEY_PREFIXES) {
                if (!key.getPath().startsWith(rule.prefix())) {
                    continue;
                }
                String rest = key.getPath().substring(rule.prefix().length());
                for (ResourceLocation old : ids.getOrDefault(rule.kind(), Map.of()).keySet()) {
                    String part = old.getNamespace() + "/" + old.getPath();
                    String tail = rest.equals(part) ? "" : rest.startsWith(part + "/") ? rest.substring(part.length()) : null;
                    if (tail != null && tail.chars().filter(c -> c == '/').count() == rule.tail()) {
                        ResourceLocation to = id(rule.kind(), old);
                        return Storybound.id(rule.prefix() + to.getNamespace() + "/" + to.getPath() + tail);
                    }
                }
            }
            return key;
        }

        /**
         * A quest's progress (under its new id) with its stages and objectives renamed. A stage keeps its name while the
         * quest still has a stage of that name (a new one took the old name), as an id still defined does. The counters
         * are the current stage's, and objective ids repeat across stages ("0" in each): one moves only when the
         * current stage has the new id and not the old one.
         */
        public QuestProgress progress(ResourceLocation quest, QuestProgress progress) {
            if (!stages.containsKey(quest) && !objectives.containsKey(quest)) {
                return progress;
            }
            QuestShape shape = questShape;
            Optional<String> stage = progress.stage().map(current -> stageNow(shape, quest, current));
            Map<String, Integer> counts = new LinkedHashMap<>();
            progress.objectives().forEach((objective, count) -> counts.merge(objectiveNow(shape, quest, stage.orElse(null), objective), count, Math::max));
            List<String> log = new ArrayList<>(new LinkedHashSet<>(progress.log().stream().map(logged -> stageNow(shape, quest, logged)).toList()));
            return new QuestProgress(progress.status(), stage, counts, log, progress.completions(), progress.startedAt(), progress.finishedAt());
        }

        private String stageNow(@Nullable QuestShape shape, ResourceLocation quest, String stage) {
            return shape != null && shape.hasStage(quest, stage) ? stage : stage(quest, stage);
        }

        private String objectiveNow(@Nullable QuestShape shape, ResourceLocation quest, @Nullable String stage, String objective) {
            String renamed = objective(quest, objective);
            if (renamed.equals(objective) || shape == null || !shape.defines(quest)) {
                return renamed;
            }
            return stage != null && shape.hasObjective(quest, stage, renamed) && !shape.hasObjective(quest, stage, objective) ? renamed : objective;
        }

        boolean chainLoops(Kind kind, ResourceLocation id) {
            Map<ResourceLocation, ResourceLocation> map = ids.getOrDefault(kind, Map.of());
            ResourceLocation at = id;
            for (int i = 0; i < MAX_CHAIN && map.containsKey(at); i++) {
                at = map.get(at);
            }
            return map.containsKey(at);
        }

        private static <K> K follow(Map<K, K> map, K start) {
            K at = start;
            for (int i = 0; i < MAX_CHAIN && map.containsKey(at); i++) {
                at = map.get(at);
            }
            return at;
        }
    }
}
