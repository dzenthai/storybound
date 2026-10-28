package dev.dzenthai.storybound.core.logic;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiPredicate;
import net.minecraft.server.MinecraftServer;

/**
 * Whether an entity that is gone from the world will come back with its uuid (an NPC waiting to respawn). Modules
 * that bring entities back register a check; others (waypoints) ask, without depending on those modules.
 */
public final class EntityReturns {
    private static final List<BiPredicate<MinecraftServer, UUID>> CHECKS = new CopyOnWriteArrayList<>();

    private EntityReturns() {}

    public static void register(BiPredicate<MinecraftServer, UUID> check) {
        CHECKS.add(check);
    }

    public static boolean comingBack(MinecraftServer server, UUID entity) {
        return CHECKS.stream().anyMatch(check -> check.test(server, entity));
    }
}
