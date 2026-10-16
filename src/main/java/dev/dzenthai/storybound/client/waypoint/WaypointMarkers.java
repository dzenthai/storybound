package dev.dzenthai.storybound.client.waypoint;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import dev.dzenthai.storybound.client.ClientModules;
import dev.dzenthai.storybound.client.StoryboundClientConfig;
import dev.dzenthai.storybound.module.waypoint.ClientWaypoint;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.Util;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.jetbrains.annotations.Nullable;

/**
 * Waypoint markers in the world, in the manner of The Witcher 3 and Skyrim: a small tinted icon over the place,
 * the distance under it, the name when the player looks near it (or always for the tracked quest), an arrow at the
 * screen edge for a tracked waypoint that is off screen. Drawn as a HUD layer from points projected with the
 * camera matrices of the same frame, so icons stay crisp and the same size at any distance. Looks come from
 * {@link WaypointStyles}; size, opacity and the whole layer are client settings.
 */
public final class WaypointMarkers {
    /** Markers float this far above the waypoint (a point on the ground, or the top of a followed entity). */
    private static final double LIFT = 0.6;
    /** Names show when the icon is this close to the crosshair, in GUI pixels; they fade over the outer half. */
    private static final float NAME_RADIUS = 60f;
    private static final float EDGE_INSET = 16f;
    /** A followed entity moving farther than this between frames (a teleport) is not glided to. */
    private static final double SNAP_DISTANCE = 8;
    private static final float DIMMED_ALPHA = 0.55f;
    private static final float DIMMED_SIZE = 0.85f;
    private static final float TRACKED_SIZE = 1.15f;
    /** Far markers fade down to this, between the style's fade start and maximum distance. */
    private static final float FAR_ALPHA = 0.4f;
    private static final float DISTANCE_SCALE = 0.75f;

    private static @Nullable Matrix4f view;
    private static @Nullable Matrix4f projection;
    private static Vec3 cameraPos = Vec3.ZERO;
    private static final Map<String, Vec3> SMOOTHED = new HashMap<>();
    private static long lastFrame;

    private WaypointMarkers() {}

    /** Keeps the camera matrices of the frame being drawn; the HUD is drawn right after the level. */
    public static void capture(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            view = new Matrix4f(event.getModelViewMatrix());
            projection = new Matrix4f(event.getProjectionMatrix());
            cameraPos = event.getCamera().getPosition();
        }
    }

    public static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        StoryboundClientConfig config = StoryboundClientConfig.INSTANCE;
        if (minecraft.level == null || minecraft.player == null || view == null || projection == null
                || !config.waypointMarkers.get() || !ClientModules.isEnabled("waypoints")) {
            return;
        }
        long now = Util.getMillis();
        float frameSeconds = Math.min(0.25f, (now - lastFrame) / 1000f);
        lastFrame = now;
        ResourceLocation dimension = minecraft.level.dimension().location();
        Vec3 eyes = minecraft.player.getEyePosition(deltaTracker.getGameTimeDeltaPartialTick(false));

        List<Marker> markers = new ArrayList<>();
        Map<String, Vec3> seen = new HashMap<>();
        for (ClientWaypoint waypoint : ClientWaypoints.shown()) {
            if (!waypoint.dimension().equals(dimension)) {
                continue;
            }
            Vec3 pos = smooth(waypoint, frameSeconds);
            seen.put(waypoint.key(), pos);
            Marker marker = marker(waypoint, pos, eyes, graphics, config);
            if (marker != null) {
                markers.add(marker);
            }
        }
        SMOOTHED.keySet().retainAll(seen.keySet());

        // Far ones first, the tracked quest's on top.
        markers.sort(Comparator.comparing(Marker::tracked).thenComparing(Comparator.comparingDouble(Marker::distance).reversed()));
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        for (Marker marker : markers) {
            draw(graphics, minecraft.font, marker, now);
        }
        graphics.setColor(1f, 1f, 1f, 1f);
        RenderSystem.disableBlend();
    }

    private static @Nullable Marker marker(ClientWaypoint waypoint, Vec3 pos, Vec3 eyes, GuiGraphics graphics, StoryboundClientConfig config) {
        ClientWaypoints.Display display = ClientWaypoints.display(waypoint);
        boolean tracked = display == ClientWaypoints.Display.TRACKED;
        WaypointStyle style = WaypointStyles.INSTANCE.get(waypoint.style());
        Vec3 anchor = pos.add(0, LIFT, 0);
        double distance = eyes.distanceTo(anchor);
        boolean area = waypoint.radius() > 0;
        double horizontal = Math.hypot(eyes.x - pos.x, eyes.z - pos.z);
        boolean inside = area && horizontal <= waypoint.radius();
        if (!tracked && style.maxDistance() > 0 && distance > style.maxDistance()) {
            return null;
        }

        float alpha = config.waypointMarkerOpacity.get().floatValue();
        float size = style.size() * config.waypointMarkerScale.get().floatValue();
        if (display == ClientWaypoints.Display.DIMMED) {
            alpha *= DIMMED_ALPHA;
            size *= DIMMED_SIZE;
        } else if (tracked) {
            size *= TRACKED_SIZE;
        }
        if (distance > style.fadeStart()) {
            float far = style.maxDistance() > style.fadeStart()
                    ? (float) Mth.clamp((distance - style.fadeStart()) / (style.maxDistance() - style.fadeStart()), 0, 1) : 0f;
            alpha *= Mth.lerp(far, 1f, FAR_ALPHA);
        }
        if (inside) {
            alpha *= 0.5f;
        } else if (!area && style.hideWithin() > 0) {
            alpha *= (float) Mth.clamp((distance - style.hideWithin() * 0.5) / (style.hideWithin() * 0.5), 0, 1);
        }
        if (alpha < 0.03f) {
            return null;
        }

        Screen screen = project(anchor, graphics.guiWidth(), graphics.guiHeight());
        boolean edge = style.edge() == WaypointStyle.EdgeMode.ALL || (style.edge() == WaypointStyle.EdgeMode.TRACKED && tracked);
        if (!screen.onScreen() && !edge) {
            return null;
        }
        int tint = tracked ? style.trackedColor() : waypoint.color();
        Component label = inside ? Component.translatable("storybound.waypoint.in_area")
                : style.distance() ? distanceText(area ? Math.max(0, horizontal - waypoint.radius()) : distance) : null;
        return new Marker(waypoint, style, screen, tracked, area, distance, alpha, size, tint, label);
    }

    private static void draw(GuiGraphics graphics, Font font, Marker marker, long now) {
        WaypointStyle style = marker.style();
        float x = marker.screen().x();
        float y = marker.screen().y();
        if (!marker.screen().onScreen()) {
            drawArrow(graphics, marker, now);
            return;
        }
        sprite(graphics, marker.area() ? style.areaIcon() : style.icon(), x, y, marker.size(), 0, marker.tint(), marker.alpha());

        float half = marker.size() / 2;
        float nameAlpha = nameAlpha(style, marker, graphics);
        if (nameAlpha > 0.03f) {
            Component name = marker.waypoint().name().toComponent();
            text(graphics, font, name, x, y - half - 10, 1f, style.textColor(), marker.alpha() * nameAlpha);
        }
        if (marker.label() != null) {
            text(graphics, font, marker.label(), x, y + half + 2, DISTANCE_SCALE, style.distanceColor(), marker.alpha());
        }
    }

    /** An arrow at the screen edge, pointing where the off-screen waypoint is, with its distance beside it. */
    private static void drawArrow(GuiGraphics graphics, Marker marker, long now) {
        float angle = (float) Math.atan2(marker.screen().dirX(), marker.screen().dirY());
        float pulse = 0.8f + 0.2f * Mth.sin((now % 1600) / 1600f * Mth.TWO_PI);
        sprite(graphics, marker.style().arrow(), marker.screen().x(), marker.screen().y(), marker.size(), angle, marker.tint(), marker.alpha() * pulse);
        if (marker.label() != null) {
            // Inside the arrow, towards the screen centre, so it never leaves the screen.
            float inward = marker.size() * 0.9f + 5;
            float tx = marker.screen().x() - Mth.sin(angle) * inward;
            float ty = marker.screen().y() + Mth.cos(angle) * inward - 3;
            text(graphics, Minecraft.getInstance().font, marker.label(), tx, ty, DISTANCE_SCALE, marker.style().distanceColor(), marker.alpha());
        }
    }

    private static float nameAlpha(WaypointStyle style, Marker marker, GuiGraphics graphics) {
        return switch (style.name()) {
            case ALWAYS -> 1f;
            case NEVER -> 0f;
            case TRACKED -> marker.tracked() ? 1f : nearCrosshair(marker, graphics);
            case NEAR_CROSSHAIR -> nearCrosshair(marker, graphics);
        };
    }

    private static float nearCrosshair(Marker marker, GuiGraphics graphics) {
        float dx = marker.screen().x() - graphics.guiWidth() / 2f;
        float dy = marker.screen().y() - graphics.guiHeight() / 2f;
        float off = (float) Math.sqrt(dx * dx + dy * dy);
        return Mth.clamp((NAME_RADIUS - off) / (NAME_RADIUS / 2), 0f, 1f);
    }

    /** A tinted GUI sprite centred on (x, y), turned clockwise by {@code angle} radians. */
    private static void sprite(GuiGraphics graphics, ResourceLocation sprite, float x, float y, float size, float angle, int rgb, float alpha) {
        graphics.setColor(((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f, Mth.clamp(alpha, 0f, 1f));
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0);
        if (angle != 0) {
            graphics.pose().mulPose(Axis.ZP.rotation(angle));
        }
        graphics.pose().scale(size / 16f, size / 16f, 1f);
        graphics.blitSprite(sprite, -8, -8, 16, 16);
        graphics.pose().popPose();
        graphics.setColor(1f, 1f, 1f, 1f);
    }

    private static void text(GuiGraphics graphics, Font font, Component text, float x, float y, float scale, int rgb, float alpha) {
        int a = (int) (Mth.clamp(alpha, 0f, 1f) * 255);
        // The font treats alpha below 4 as opaque.
        if (a < 8) {
            return;
        }
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0);
        graphics.pose().scale(scale, scale, 1f);
        graphics.drawString(font, text, -font.width(text) / 2, 0, (a << 24) | (rgb & 0xFFFFFF), true);
        graphics.pose().popPose();
    }

    private static Component distanceText(double blocks) {
        if (blocks >= 1000) {
            return Component.translatable("storybound.waypoint.distance_km", String.format("%.1f", blocks / 1000));
        }
        return Component.translatable("storybound.waypoint.distance", (int) Math.round(blocks));
    }

    /** Glides a followed entity's waypoint between the server's half-block steps. */
    private static Vec3 smooth(ClientWaypoint waypoint, float frameSeconds) {
        Vec3 target = waypoint.pos();
        Vec3 current = SMOOTHED.get(waypoint.key());
        Vec3 next = current == null || current.distanceTo(target) > SNAP_DISTANCE
                ? target : current.lerp(target, 1 - Math.exp(-frameSeconds * 6));
        SMOOTHED.put(waypoint.key(), next);
        return next;
    }

    /**
     * Where a world point lands in GUI coordinates. Off screen (or behind the camera), the point is pulled to the
     * screen edge along its direction from the centre, for the edge arrow.
     */
    static Screen project(Vec3 point, int width, int height) {
        Vector4f eye = new Vector4f((float) (point.x - cameraPos.x), (float) (point.y - cameraPos.y), (float) (point.z - cameraPos.z), 1f);
        view.transform(eye);
        Vector4f clip = projection.transform(new Vector4f(eye));
        if (clip.w > 0.05f) {
            float ndcX = clip.x / clip.w;
            float ndcY = clip.y / clip.w;
            float x = (ndcX * 0.5f + 0.5f) * width;
            float y = (0.5f - ndcY * 0.5f) * height;
            if (Math.abs(ndcX) <= 1 && Math.abs(ndcY) <= 1) {
                return new Screen(x, y, true, ndcX, ndcY);
            }
        }
        // The direction on screen: right and up in camera space; straight behind points down.
        float dirX = eye.x;
        float dirY = eye.y;
        if (Math.abs(dirX) < 1e-4 && Math.abs(dirY) < 1e-4) {
            dirY = -1;
        }
        float halfW = width / 2f - EDGE_INSET;
        float halfH = height / 2f - EDGE_INSET;
        float t = Math.min(halfW / Math.max(1e-4f, Math.abs(dirX)), halfH / Math.max(1e-4f, Math.abs(dirY)));
        return new Screen(width / 2f + dirX * t, height / 2f - dirY * t, false, dirX, dirY);
    }

    /** @param dirX right and {@code dirY} up from the screen centre towards the point (for the edge arrow) */
    record Screen(float x, float y, boolean onScreen, float dirX, float dirY) {}

    private record Marker(ClientWaypoint waypoint, WaypointStyle style, Screen screen, boolean tracked, boolean area, double distance,
                          float alpha, float size, int tint, @Nullable Component label) {}
}
