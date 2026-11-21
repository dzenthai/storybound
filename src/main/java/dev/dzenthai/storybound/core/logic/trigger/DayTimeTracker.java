package dev.dzenthai.storybound.core.logic.trigger;

import dev.dzenthai.storybound.core.logic.StoryContext;
import dev.dzenthai.storybound.core.logic.condition.WorldConditions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * The time of day each player was last seen at, for the {@code time} trigger: it fires when a player's period (day,
 * sunset, night, sunrise) changes, checked with the areas. Only in a level whose time moves (not the Nether's or the
 * End's fixed sky). The first sight of a player (a login, a return from such a level) only records the period: whatever
 * waits for "it is night" counts the state itself ({@link CoreTriggers.TimeReached#holdsNow}). A jump (a night slept
 * through, {@code /time set}) fires once, for the period it lands in.
 */
public final class DayTimeTracker {
    private static final Map<UUID, WorldConditions.Time.Period> SEEN = new HashMap<>();

    private DayTimeTracker() {}

    /** The period of the player's level now; null where the time does not move. */
    public static WorldConditions.Time.@Nullable Period periodOf(ServerPlayer player) {
        if (player.level().dimensionType().hasFixedTime()) {
            return null;
        }
        return WorldConditions.Time.Period.of(Math.floorMod(player.level().getDayTime(), 24000L));
    }

    /** Every 10 ticks, with the areas; public for tests. */
    public static void tick(MinecraftServer server) {
        for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
            WorldConditions.Time.Period now = periodOf(player);
            if (now == null) {
                SEEN.remove(player.getUUID());
                continue;
            }
            WorldConditions.Time.Period before = SEEN.put(player.getUUID(), now);
            if (before != null && before != now) {
                TriggerDispatcher.fire(BuiltinTriggers.TIME.get(), StoryContext.of(player), now);
            }
        }
    }

    static void forget(UUID player) {
        SEEN.remove(player);
    }

    static void reset() {
        SEEN.clear();
    }
}
