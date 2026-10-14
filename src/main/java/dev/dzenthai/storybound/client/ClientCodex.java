package dev.dzenthai.storybound.client;

import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/** The local player's open Codex sections as last sent by the server. */
public final class ClientCodex {
    private static volatile Map<ResourceLocation, List<String>> unlocked = Map.of();

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

    public static Map<ResourceLocation, List<String>> all() {
        return unlocked;
    }

    public static Set<String> sections(ResourceLocation entry) {
        return Set.copyOf(unlocked.getOrDefault(entry, List.of()));
    }

    public static void clear() {
        unlocked = Map.of();
        version++;
    }
}
