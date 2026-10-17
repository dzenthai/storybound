package dev.dzenthai.storybound.module.waypoint;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.logic.StructureRef;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Structure searches for waypoints, off the server thread. A search like {@code /locate} can take minutes when the
 * structure is rare or absent, and on the server thread that would freeze the server; here it runs on one background
 * thread, one search at a time, and the chunks it needs are still loaded on the server thread, one by one, the way
 * vanilla hands off chunk requests from other threads. Results are shared by everyone asking the same question and
 * kept until data reloads or the server stops; waypoints refresh when a search finishes.
 */
public final class StructureLocator {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Storybound structure search");
        thread.setDaemon(true);
        return thread;
    });
    private static final Map<Search, Optional<BlockPos>> RESULTS = new ConcurrentHashMap<>();
    private static final Set<Search> PENDING = ConcurrentHashMap.newKeySet();
    /** Bumped by {@link #reset}, so a search started before it does not store its now stale result. */
    private static volatile int generation;

    private StructureLocator() {}

    /** One question: the nearest such structure to {@code origin}, within {@code radius} chunks, in that dimension. */
    public record Search(StructureRef structure, ResourceKey<Level> dimension, BlockPos origin, int radius) {}

    /**
     * The answer if known: the structure's position, or empty when there is none in range. Null while the search
     * runs; the first question starts it.
     */
    public static @Nullable Optional<BlockPos> result(MinecraftServer server, Search search) {
        Optional<BlockPos> known = RESULTS.get(search);
        if (known != null || !PENDING.add(search)) {
            return known;
        }
        ServerLevel level = server.getLevel(search.dimension());
        if (level == null) {
            PENDING.remove(search);
            RESULTS.put(search, Optional.empty());
            return Optional.empty();
        }
        int started = generation;
        EXECUTOR.execute(() -> {
            Optional<BlockPos> found;
            try {
                found = search.structure().locate(level, search.origin(), search.radius());
            } catch (RuntimeException e) {
                Storybound.LOGGER.error("Storybound: searching for {} failed", search.structure(), e);
                found = Optional.empty();
            }
            Optional<BlockPos> answer = found;
            server.execute(() -> {
                if (started != generation) {
                    return;
                }
                RESULTS.put(search, answer);
                PENDING.remove(search);
                Waypoints.refreshAll(server);
            });
        });
        return null;
    }

    /** Stores an answer as if a search had found it. Public for tests: the GameTest world has no structures. */
    public static void remember(Search search, Optional<BlockPos> answer) {
        RESULTS.put(search, answer);
        PENDING.remove(search);
    }

    /** Forgets every answer; a search still running finishes but its result is dropped. */
    public static void reset() {
        generation++;
        RESULTS.clear();
        PENDING.clear();
    }
}
