package dev.dzenthai.storybound.gametest;

import dev.dzenthai.storybound.Storybound;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.gametest.framework.MultipleTestTracker;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.apache.logging.log4j.LogManager;

/**
 * Keeps the GameTest server from hanging while it stops, a vanilla deadlock that heavier mod sets (Easy NPC) made
 * frequent. A generation task holds a claim on every chunk around it until its last step (FULL) has run on the
 * server thread. {@code MinecraftServer.stopServer} unloads chunks with "always have time", so
 * {@code ChunkMap.processUnloads} keeps re-queueing a claimed chunk inside a single call and never returns; the server
 * thread then never runs the FULL step that would release the claim. The GameTest server stops seconds after the
 * world was created, while chunks around the tests are still generating, so it hit this often.
 *
 * <p>When the server starts stopping (on the server thread, before any ticket is removed), this runs the chunk
 * main-thread tasks until no chunk is claimed for generation any more, so nothing is left in flight. If the server
 * still has not stopped after a while, a watchdog logs the claims and ends the process with the server's own exit
 * code (the number of failed required tests): the results are final and the test world is deleted before every run.
 * GameTest server only; the mod never ships this.
 */
@EventBusSubscriber(modid = Storybound.MOD_ID)
public final class GameTestShutdownWatchdog {
    private static final long SETTLE_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final long GRACE_MILLIS = 30_000;
    private static final int MAX_LISTED = 10;

    private GameTestShutdownWatchdog() {}

    @SubscribeEvent
    public static void onStopping(ServerStoppingEvent event) {
        if (!(event.getServer() instanceof GameTestServer server)) {
            return;
        }
        try {
            settleChunkGeneration(server);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Storybound.LOGGER.error("GameTest watchdog: could not let chunk generation finish", e);
        }
        Thread thread = new Thread(() -> watch(server), "storybound-gametest-shutdown-watchdog");
        thread.setDaemon(true);
        thread.start();
    }

    /** On the server thread, before the stop: runs chunk main-thread tasks until no generation task holds a claim. */
    private static void settleChunkGeneration(GameTestServer server) throws ReflectiveOperationException {
        long start = System.nanoTime();
        int claimed = claimedChunks(server);
        while (claimed > 0 && System.nanoTime() - start < SETTLE_NANOS) {
            boolean ran = false;
            for (ServerLevel level : server.getAllLevels()) {
                ran |= level.getChunkSource().pollTask();
            }
            if (!ran) {
                // The rest of the generation runs on worker threads; give them a moment.
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                claimed = claimedChunks(server);
            }
        }
        if (claimed > 0) {
            Storybound.LOGGER.warn("GameTest watchdog: {} chunks still claimed for generation after {} s; stopping anyway",
                    claimed, TimeUnit.NANOSECONDS.toSeconds(SETTLE_NANOS));
        } else {
            Storybound.LOGGER.debug("GameTest watchdog: chunk generation settled in {} ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        }
    }

    private static void watch(GameTestServer server) {
        try {
            Thread.sleep(GRACE_MILLIS);
        } catch (InterruptedException e) {
            return;
        }
        MultipleTestTracker tracker;
        try {
            tracker = (MultipleTestTracker) field(GameTestServer.class, "testTracker").get(server);
        } catch (ReflectiveOperationException e) {
            Storybound.LOGGER.error("GameTest watchdog: cannot read the test results", e);
            return;
        }
        if (tracker == null || !tracker.isDone()) {
            return;
        }
        Storybound.LOGGER.error("GameTest watchdog: the server is still stopping {} s after the tests finished", GRACE_MILLIS / 1000);
        int listed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            try {
                for (ChunkHolder holder : holders(level.getChunkSource().chunkMap)) {
                    if (holder.getGenerationRefCount() > 0 && listed++ < MAX_LISTED) {
                        Storybound.LOGGER.error("    {} chunk {} claims={} ticket={} latest={}", level.dimension().location(), holder.getPos(),
                                holder.getGenerationRefCount(), holder.getTicketLevel(), holder.getLatestStatus());
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                Storybound.LOGGER.error("GameTest watchdog: could not inspect {}", level.dimension().location(), e);
            }
        }
        int failed = tracker.getFailedRequiredCount();
        Storybound.LOGGER.error("GameTest watchdog: ending the process with {} failed required tests", failed);
        LogManager.shutdown();
        Runtime.getRuntime().halt(failed);
    }

    private static int claimedChunks(GameTestServer server) throws ReflectiveOperationException {
        int claimed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (ChunkHolder holder : holders(level.getChunkSource().chunkMap)) {
                if (holder.getGenerationRefCount() > 0) {
                    claimed++;
                }
            }
        }
        return claimed;
    }

    @SuppressWarnings("unchecked")
    private static List<ChunkHolder> holders(ChunkMap map) throws ReflectiveOperationException {
        List<ChunkHolder> holders = new ArrayList<>();
        for (String name : List.of("updatingChunkMap", "pendingUnloads")) {
            holders.addAll(((Long2ObjectMap<ChunkHolder>) field(ChunkMap.class, name).get(map)).values());
        }
        return holders;
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
