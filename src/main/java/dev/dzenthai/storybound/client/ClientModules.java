package dev.dzenthai.storybound.client;

import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/** Which modules the connected server has enabled; until it says, everything counts as enabled. */
public final class ClientModules {
    private static volatile @Nullable Set<String> enabled;

    private ClientModules() {}

    public static void apply(List<String> modules) {
        enabled = Set.copyOf(modules);
    }

    public static boolean isEnabled(String module) {
        Set<String> current = enabled;
        return current == null || current.contains(module);
    }

    public static void clear() {
        enabled = null;
    }
}
