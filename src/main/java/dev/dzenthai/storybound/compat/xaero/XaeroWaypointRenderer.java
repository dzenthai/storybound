package dev.dzenthai.storybound.compat.xaero;

import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.waypoint.ClientWaypoints;
import dev.dzenthai.storybound.client.waypoint.WaypointRenderer;
import dev.dzenthai.storybound.module.waypoint.ClientWaypoint;
import java.util.List;
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
 * they show on the minimap, in the world and in Xaero's waypoint menu, and are never saved to Xaero's files.
 * Only the current dimension's waypoints; the list is rebuilt on every change and on a dimension change.
 * Xaero has no stable public API, so its version range is pinned.
 */
final class XaeroWaypointRenderer implements WaypointRenderer {
    private static final ResourceLocation ORIGIN = Storybound.id("waypoints");

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
        ResourceLocation dimension = minecraft.level.dimension().location();
        for (ClientWaypoint waypoint : waypoints) {
            if (!waypoint.dimension().equals(dimension)) {
                continue;
            }
            String name = waypoint.name().toComponent().getString();
            boolean tracked = ClientWaypoints.display(waypoint) == ClientWaypoints.Display.TRACKED;
            ours.add(waypoint.key(), new Waypoint((int) Math.floor(waypoint.pos().x), (int) Math.floor(waypoint.pos().y), (int) Math.floor(waypoint.pos().z),
                    tracked ? "» " + name : name, initials(name), nearest(waypoint.color()), WaypointPurpose.NORMAL));
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
