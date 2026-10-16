package dev.dzenthai.storybound.compat.xaero;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.waypoint.ClientWaypoints;
import dev.dzenthai.storybound.client.waypoint.WaypointRenderer;
import dev.dzenthai.storybound.module.waypoint.ClientWaypoint;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import xaero.common.minimap.waypoints.Waypoint;
import xaero.hud.minimap.BuiltInHudModules;
import xaero.hud.minimap.module.MinimapSession;
import xaero.hud.minimap.waypoint.WaypointColor;
import xaero.hud.minimap.waypoint.WaypointPurpose;
import xaero.hud.minimap.waypoint.thirdparty.ThirdPartyWaypoints;
import xaero.hud.minimap.world.MinimapWorld;

/**
 * Puts Storybound waypoints into Xaero's Minimap as "third-party waypoints" of origin {@code storybound:waypoints}:
 * they show on the minimap, the world map and in Xaero's waypoint menu, and are never saved to Xaero's files.
 * Xaero draws them in the world too, which {@code XaeroWaypointWorldRendererMixin} skips: Storybound has its own
 * markers there. Only the current dimension's waypoints; the list is rebuilt on every change and on a dimension
 * change. Xaero has no stable public API, so its version range is pinned.
 */
public final class XaeroWaypointRenderer implements WaypointRenderer {
    private static final ResourceLocation ORIGIN = Storybound.id("waypoints");
    /** The Xaero waypoints we made, by identity; weak, so Xaero dropping them is enough. */
    private static final Set<Waypoint> OURS = Collections.newSetFromMap(new WeakHashMap<>());

    /** A waypoint Storybound put into Xaero, which Xaero must not draw in the world. */
    public static boolean isOurs(Waypoint waypoint) {
        return OURS.contains(waypoint);
    }

    /** The list filled last time; cleared when the player moves to another world container. */
    private @Nullable ThirdPartyWaypoints last;

    @Override
    public String name() {
        return "Xaero's Minimap";
    }

    @Override
    public void refresh(List<ClientWaypoint> waypoints) {
        MinimapSession session = BuiltInHudModules.MINIMAP.getCurrentSession();
        Minecraft minecraft = Minecraft.getInstance();
        MinimapWorld world = session != null ? session.getWorldManager().getCurrentWorld() : null;
        ThirdPartyWaypoints ours = world != null && world.getContainer() != null
                ? world.getContainer().getThirdPartyWaypointManager().get(ORIGIN) : null;
        if (last != null && last != ours) {
            last.clear();
        }
        last = ours;
        if (ours == null || minecraft.level == null) {
            return;
        }
        ours.clear();
        OURS.clear();
        ResourceLocation dimension = minecraft.level.dimension().location();
        for (ClientWaypoint waypoint : waypoints) {
            if (!waypoint.dimension().equals(dimension)) {
                continue;
            }
            String name = waypoint.name().toComponent().getString();
            boolean tracked = ClientWaypoints.display(waypoint) == ClientWaypoints.Display.TRACKED;
            Waypoint xaero = new Waypoint((int) Math.floor(waypoint.pos().x), (int) Math.floor(waypoint.pos().y), (int) Math.floor(waypoint.pos().z),
                    tracked ? "» " + name : name, initials(name), nearest(waypoint.color()), WaypointPurpose.NORMAL);
            OURS.add(xaero);
            ours.add(waypoint.key(), xaero);
            // Deleting a third-party waypoint in Xaero's menu is remembered per id, in the world's config, for
            // good. A story waypoint comes back with the next change instead (tracking a quest, a new stage), as
            // in JourneyMap, where we recreate them every time.
            if (xaero.isThirdPartyDeleted()) {
                xaero.setThirdPartyDeleted(false);
            }
        }
    }

    private static String initials(String name) {
        String trimmed = name.strip();
        return trimmed.isEmpty() ? "S" : trimmed.substring(0, 1).toUpperCase();
    }

    /** Xaero colours waypoints from a fixed palette. */
    private static WaypointColor nearest(int color) {
        WaypointColor best = WaypointColor.WHITE;
        long bestDistance = Long.MAX_VALUE;
        for (WaypointColor candidate : WaypointColor.values()) {
            int hex = candidate.getHex();
            long dr = ((hex >> 16) & 0xFF) - ((color >> 16) & 0xFF);
            long dg = ((hex >> 8) & 0xFF) - ((color >> 8) & 0xFF);
            long db = (hex & 0xFF) - (color & 0xFF);
            long distance = dr * dr + dg * dg + db * db;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }
}
