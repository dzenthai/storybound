package dev.dzenthai.storybound.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/** The local player's open Codex sections as last sent by the server. */
public final class ClientCodex {
    private static volatile Map<ResourceLocation, List<String>> unlocked = Map.of();
    private static volatile Map<ResourceLocation, Integer> kills = Map.of();

    /** Incremented on every change (on the main thread); open views compare it to know when to refresh. */
    private static volatile int version;

    private ClientCodex() {}

    public static int version() {
        return version;
    }

    public static void apply(Map<ResourceLocation, List<String>> unlocked) {
        ClientCodex.unlocked = Map.copyOf(unlocked);
        version++;
    }

    /** {@code all}: every count, replacing the old ones; otherwise only those that changed. */
    public static void applyKills(Map<ResourceLocation, Integer> changed, boolean all) {
        Map<ResourceLocation, Integer> next = all ? new HashMap<>() : new HashMap<>(kills);
        next.putAll(changed);
        kills = Map.copyOf(next);
        version++;
    }

    /** How many of the entry's creatures the player killed; 0 when none or not counted. */
    public static int kills(ResourceLocation entry) {
        return kills.getOrDefault(entry, 0);
    }

    public static Map<ResourceLocation, List<String>> all() {
        return unlocked;
    }

    public static Set<String> sections(ResourceLocation entry) {
        return Set.copyOf(unlocked.getOrDefault(entry, List.of()));
    }

    public static void clear() {
        unlocked = Map.of();
        kills = Map.of();
        version++;
    }
}
