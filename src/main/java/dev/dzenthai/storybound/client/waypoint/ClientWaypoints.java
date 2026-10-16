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
import java.util.Optional;
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
    private static int shownVersion = -1;
    private static Optional<ResourceLocation> shownTracked = Optional.empty();
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
        version++;
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
        if (waypoint.quest().equals(ClientQuests.tracked())) {
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
        List<ClientWaypoint> shown = new ArrayList<>();
        for (ClientWaypoint waypoint : WAYPOINTS.values()) {
            if (display(waypoint) != Display.HIDDEN) {
                shown.add(waypoint);
            }
        }
        return shown;
    }

    /** Pushes changes to the renderers; every client tick, cheap when nothing changed. */
    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        Optional<ResourceLocation> tracked = ClientQuests.tracked();
        StoryboundClientConfig.Untracked untracked = StoryboundClientConfig.INSTANCE.untrackedQuestWaypoints.get();
        if (version == shownVersion && tracked.equals(shownTracked) && minecraft.level == shownLevel && untracked == shownUntracked) {
            return;
        }
        shownVersion = version;
        shownTracked = tracked;
        shownLevel = minecraft.level;
        shownUntracked = untracked;
        List<ClientWaypoint> shown = shown();
        for (WaypointRenderer renderer : RENDERERS) {
            try {
                renderer.refresh(shown);
            } catch (RuntimeException | LinkageError e) {
                // A map mod changed its internals: drop that integration, keep the others.
                Storybound.LOGGER.error("Storybound: waypoint renderer {} failed and is disabled", renderer.name(), e);
                RENDERERS.remove(renderer);
            }
        }
    }

    public static void clear() {
        WAYPOINTS.clear();
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
