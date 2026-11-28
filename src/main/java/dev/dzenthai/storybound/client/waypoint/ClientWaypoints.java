package dev.dzenthai.storybound.client.waypoint;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.ClientQuests;
import dev.dzenthai.storybound.client.StoryboundClientConfig;
import dev.dzenthai.storybound.module.waypoint.ClientWaypoint;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * The waypoints the server says this player sees, and the renderers that show them: our own HUD always,
 * Xaero's Minimap and JourneyMap when installed. Renderers are refreshed when the waypoints, the tracked
 * quest or the dimension change.
 */
public final class ClientWaypoints {
    private static final Map<String, ClientWaypoint> WAYPOINTS = new LinkedHashMap<>();
    private static final List<WaypointRenderer> RENDERERS = new CopyOnWriteArrayList<>();
    private static int version;
    private static boolean hasAreaRings;
    private static boolean hasTraces;
    private static List<ClientWaypoint> shownCache = List.of();
    private static int shownCacheVersion = -1;
    private static int shownCacheQuestVersion = -1;
    private static @Nullable StoryboundClientConfig.Untracked shownCacheUntracked;
    private static int shownVersion = -1;
    private static List<ResourceLocation> shownTracked = List.of();
    private static @Nullable ClientLevel shownLevel;
    private static @Nullable StoryboundClientConfig.Untracked shownUntracked;

    private ClientWaypoints() {}

    /** For other mods' map integrations; ours register themselves when their mod is present. */
    public static void registerRenderer(WaypointRenderer renderer) {
        RENDERERS.add(renderer);
        shownVersion = -1;
    }

    public static void apply(boolean full, List<ClientWaypoint> changed, List<String> removed) {
        if (full) {
            WAYPOINTS.clear();
        }
        removed.forEach(WAYPOINTS::remove);
        changed.forEach(waypoint -> WAYPOINTS.put(waypoint.key(), waypoint));
        recomputeFlags();
        version++;
    }

    private static void recomputeFlags() {
        boolean rings = false;
        boolean traces = false;
        for (ClientWaypoint waypoint : WAYPOINTS.values()) {
            if (!waypoint.placed()) {
                continue;
            }
            if (waypoint.isTrace()) {
                if (waypoint.trace().size() >= 2) {
                    traces = true;
                }
            } else if (waypoint.radius() > 0 && waypoint.showRing()) {
                rings = true;
            }
            if (rings && traces) {
                break;
            }
        }
        hasAreaRings = rings;
        hasTraces = traces;
    }

    public static boolean isEmpty() {
        return WAYPOINTS.isEmpty();
    }

    /** True when at least one placed waypoint requests an area ring; lets {@link AreaRings} short-circuit cheaply. */
    public static boolean hasAreaRings() {
        return hasAreaRings;
    }

    /** True when at least one placed waypoint is a drawable trace; lets {@link TraceTrails} short-circuit cheaply. */
    public static boolean hasTraces() {
        return hasTraces;
    }

    /** Live unmodifiable view without copying; safe on the render thread when not mutating waypoints. */
    public static Collection<ClientWaypoint> values() {
        return java.util.Collections.unmodifiableCollection(WAYPOINTS.values());
    }

    public static Collection<ClientWaypoint> all() {
        return List.copyOf(WAYPOINTS.values());
    }

    public static int version() {
        return version;
    }

    /** How a waypoint shows given the tracked quest and the client setting. */
    public static Display display(ClientWaypoint waypoint) {
        if (waypoint.quest().isEmpty()) {
            return Display.NORMAL;
        }
        if (waypoint.quest().map(ClientQuests::isPinned).orElse(false)) {
            return Display.TRACKED;
        }
        return switch (StoryboundClientConfig.INSTANCE.untrackedQuestWaypoints.get()) {
            case SHOW -> Display.NORMAL;
            case DIM -> Display.DIMMED;
            case HIDE -> Display.HIDDEN;
        };
    }

    /** Waypoints that should be drawn at all, in the order received. */
    public static List<ClientWaypoint> shown() {
        if (WAYPOINTS.isEmpty()) {
            return List.of();
        }
        int questVersion = ClientQuests.version();
        StoryboundClientConfig.Untracked untracked = StoryboundClientConfig.INSTANCE.untrackedQuestWaypoints.get();
        if (shownCacheVersion == version && shownCacheQuestVersion == questVersion && shownCacheUntracked == untracked) {
            return shownCache;
        }
        List<ClientWaypoint> shown = new ArrayList<>();
        for (ClientWaypoint waypoint : WAYPOINTS.values()) {
            // One with only its dimension known is drawn nowhere: the quest tracker names the dimension.
            if (waypoint.placed() && display(waypoint) != Display.HIDDEN) {
                shown.add(waypoint);
            }
        }
        shownCache = List.copyOf(shown);
        shownCacheVersion = version;
        shownCacheQuestVersion = questVersion;
        shownCacheUntracked = untracked;
        return shownCache;
    }

    /** Pushes changes to the renderers; every client tick, cheap when nothing changed. */
    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        List<ResourceLocation> tracked = ClientQuests.pinned();
        StoryboundClientConfig.Untracked untracked = StoryboundClientConfig.INSTANCE.untrackedQuestWaypoints.get();
        if (version == shownVersion && tracked.equals(shownTracked) && minecraft.level == shownLevel && untracked == shownUntracked) {
            return;
        }
        shownVersion = version;
        shownTracked = tracked;
        shownLevel = minecraft.level;
        shownUntracked = untracked;
        // A trail is found on the ground, never shown on a map.
        List<ClientWaypoint> shown = shown().stream().filter(waypoint -> !waypoint.isTrace()).toList();
        for (WaypointRenderer renderer : RENDERERS) {
            try {
                renderer.refresh(shown);
                Storybound.LOGGER.debug("Storybound: {} waypoints sent to {} (pinned quests: {})", shown.size(), renderer.name(), tracked);
            } catch (RuntimeException | LinkageError e) {
                // A map mod changed its internals: drop that integration, keep the others.
                Storybound.LOGGER.error("Storybound: waypoint renderer {} failed and is disabled", renderer.name(), e);
                RENDERERS.remove(renderer);
            }
        }
    }

    public static void clear() {
        WAYPOINTS.clear();
        hasAreaRings = false;
        hasTraces = false;
        shownCache = List.of();
        shownCacheVersion = -1;
        version++;
        for (WaypointRenderer renderer : RENDERERS) {
            try {
                renderer.refresh(List.of());
            } catch (RuntimeException | LinkageError e) {
                RENDERERS.remove(renderer);
            }
        }
        shownVersion = version;
    }

    public enum Display {
        TRACKED,
        NORMAL,
        DIMMED,
        HIDDEN
    }
}
