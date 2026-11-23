package dev.dzenthai.storybound.client.waypoint;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import dev.dzenthai.storybound.client.ClientModules;
import dev.dzenthai.storybound.client.HudScale;
import dev.dzenthai.storybound.client.StoryboundClientConfig;
import dev.dzenthai.storybound.compat.cutscene.CutsceneClient;
import dev.dzenthai.storybound.module.waypoint.AreaLook;
import dev.dzenthai.storybound.module.waypoint.ClientWaypoint;
import dev.dzenthai.storybound.module.waypoint.WaypointDefinition;
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
import net.minecraft.world.entity.Entity;
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
    /** A waypoint moving farther than this between server updates (a teleport) is not glided to. */
    private static final double SNAP_DISTANCE = 8;
    /** A waypoint switching to another entity (a {@code nearest} one picking the next mob) glides over this long. */
    private static final long SWITCH_MILLIS = 350;
    private static final float DIMMED_ALPHA = 0.55f;
    /** How often an event's three "!" hop. */
    private static final long EVENT_PERIOD_MS = 1400;
    /** How long the side marks hop after the middle one, both at once. */
    private static final long EVENT_FOLLOW_MS = 70;
    /** How long a mark crouches before it jumps. */
    private static final long EVENT_CROUCH_MS = 110;
    /** How long a mark is in the air. */
    private static final long EVENT_AIR_MS = 340;
    /** How high every mark jumps, in marker sizes: the same for all, as if one gravity held them. */
    private static final float EVENT_JUMP = 0.6f;
    /** How far the side marks lean outwards at the top of the jump, in degrees. */
    private static final float EVENT_LEAN = 8f;
    /**
     * The middle mark's height, in marker sizes: the question's too, so the glyph markers share one height. Taller than
     * a point's icon because a narrow glyph looks smaller than a square of the same height.
     */
    private static final float GLYPH_HEIGHT = 1.1f;
    /** The side marks' size beside the middle one's. */
    private static final float EVENT_SIDE_SCALE = 0.65f;
    /** The distance between the marks, in marker sizes. */
    private static final float EVENT_SPACING = 0.38f;
    /** How often the question's "?" shakes and hops. */
    private static final long QUESTION_PERIOD_MS = 3000;
    /** How high it hops, in marker sizes. */
    private static final float QUESTION_JUMP = 0.2f;
    /** How long its shake lasts, dying away. */
    private static final long QUESTION_SHAKE_MS = 650;
    /** One swing of the shake there and back. */
    private static final long QUESTION_SHAKE_SWING_MS = 260;
    /** How far the shake turns it at first, in degrees. */
    private static final float QUESTION_SHAKE = 9f;
    /** How much of the event's squash and stretch it takes. */
    private static final float QUESTION_SQUASH = 0.5f;
    /** The marks' hard shadow, drawn by the mod under the edge arrow, which turns, so its light stays at the top left. */
    private static final int SHADOW_RGB = 0x18120C;
    /**
     * A point's icon beside a marker's size: the diamond fills 28 of its sprite's 32 rows with its shadow, so at this
     * scale it stands as tall as the "?" and the middle "!".
     */
    private static final float POINT_SCALE = 1.26f;
    /** How often the search area's magnifier searches and takes a closer look. */
    private static final long AREA_PERIOD_MS = 3200;
    /** How long it sweeps left, right and back before it stops on something. */
    private static final long AREA_SWEEP_MS = 700;
    /** How far it sweeps to each side, in marker sizes. */
    private static final float AREA_SWEEP = 0.14f;
    /** How long it draws back before the closer look. */
    private static final long AREA_DRAW_BACK_MS = 100;
    /** How much it shrinks drawing back, and how far past its size it springs coming closer. */
    private static final float AREA_DRAW_BACK = 0.08f;
    private static final float AREA_ZOOM = 0.3f;
    private static final float DIMMED_SIZE = 0.85f;
    private static final float TRACKED_SIZE = 1.15f;
    /** Far markers fade down to this, between the style's fade start and maximum distance. */
    private static final float FAR_ALPHA = 0.4f;
    private static final float DISTANCE_SCALE = 0.75f;
    /** Icons this close on screen, in HUD pixels, are one place: their names are stacked (see {@link #stacks}). */
    private static final float STACK_RADIUS = 8f;
    /**
     * And this close in the world, in blocks, or this share of the distance to them (two searches finding one village a
     * few blocks apart).
     */
    private static final double STACK_WORLD = 4;
    private static final double STACK_WORLD_SHARE = 0.05;
    /** One stacked name above the next, in HUD pixels: the font's line and a pixel. */
    private static final int NAME_LINE = 10;

    private static @Nullable Matrix4f view;
    private static @Nullable Matrix4f projection;
    private static Vec3 cameraPos = Vec3.ZERO;
    private static final Map<String, Vec3> SMOOTHED = new HashMap<>();
    /** Waypoint key → the entity its marker is drawn at, while the client has that entity. */
    private static final Map<String, Integer> FOLLOWED = new HashMap<>();
    /** Waypoint key → a glide from where the marker was to a newly followed entity. */
    private static final Map<String, Glide> GLIDES = new HashMap<>();
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
                || !config.waypointMarkers.get() || !ClientModules.isEnabled("waypoints") || CutsceneClient.playing()) {
            return;
        }
        long now = Util.getMillis();
        float frameSeconds = Math.min(0.25f, (now - lastFrame) / 1000f);
        lastFrame = now;
        ResourceLocation dimension = minecraft.level.dimension().location();
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
        Vec3 eyes = minecraft.player.getEyePosition(partialTick);

        List<Marker> markers = new ArrayList<>();
        Map<String, Vec3> seen = new HashMap<>();
        for (ClientWaypoint waypoint : ClientWaypoints.shown()) {
            // A trail has no marker: TraceTrails draws it.
            if (!waypoint.dimension().equals(dimension) || waypoint.isTrace()) {
                continue;
            }
            Vec3 pos = position(minecraft, waypoint, frameSeconds, partialTick, now);
            seen.put(waypoint.key(), pos);
            Marker marker = marker(waypoint, pos, eyes, graphics, config);
            if (marker != null) {
                markers.add(marker);
            }
        }
        SMOOTHED.keySet().retainAll(seen.keySet());
        FOLLOWED.keySet().retainAll(seen.keySet());
        GLIDES.keySet().retainAll(seen.keySet());

        // Far ones first, the tracked quest's on top.
        markers.sort(Comparator.comparing(Marker::tracked).thenComparing(Comparator.comparingDouble(Marker::distance).reversed()));
        int[] stack = stacks(markers);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        // In HUD pixels, whatever the GUI scale: the markers were projected onto the HUD's width and height.
        HudScale.push(graphics);
        for (int i = 0; i < markers.size(); i++) {
            draw(graphics, minecraft.font, markers.get(i), stack[i], now);
        }
        HudScale.pop(graphics);
        graphics.setColor(1f, 1f, 1f, 1f);
        RenderSystem.disableBlend();
    }

    private static @Nullable Marker marker(ClientWaypoint waypoint, Vec3 pos, Vec3 eyes, GuiGraphics graphics, StoryboundClientConfig config) {
        ClientWaypoints.Display display = ClientWaypoints.display(waypoint);
        boolean tracked = display == ClientWaypoints.Display.TRACKED;
        WaypointStyle style = WaypointStyles.INSTANCE.get();
        Vec3 anchor = pos.add(0, LIFT, 0);
        double distance = eyes.distanceTo(anchor);
        boolean area = waypoint.radius() > 0;
        double horizontal = Math.hypot(eyes.x - pos.x, eyes.z - pos.z);
        // Inside: within the radius and at a height where the area's edge is drawn whole, so a player mining far under
        // an area on the surface gets its distance rather than "Search the area".
        Minecraft minecraft = Minecraft.getInstance();
        boolean inside = area && horizontal <= waypoint.radius() && minecraft.level != null && minecraft.player != null
                && AreaRings.heightShown(minecraft.level, pos, waypoint.radius(), minecraft.player.getY()) >= 1f;
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
        Screen screen = project(anchor, HudScale.width(), HudScale.height());
        if (distance > style.fadeStart()) {
            float far = style.maxDistance() > style.fadeStart()
                    ? (float) Mth.clamp((distance - style.fadeStart()) / (style.maxDistance() - style.fadeStart()), 0, 1) : 0f;
            // A far marker the player aims at is shown at full strength: they are looking for it.
            float aimed = screen.onScreen() ? nearCrosshair(screen, graphics) : 0f;
            alpha *= Mth.lerp(far * (1 - aimed), 1f, FAR_ALPHA);
        }
        if (inside) {
            alpha *= 0.5f;
        } else if (!area && style.hideWithin() > 0) {
            alpha *= (float) Mth.clamp((distance - style.hideWithin() * 0.5) / (style.hideWithin() * 0.5), 0, 1);
        }
        if (alpha < 0.03f) {
            return null;
        }

        StoryboundClientConfig.EdgeArrows arrows = config.waypointEdgeArrows.get();
        boolean edge = arrows == StoryboundClientConfig.EdgeArrows.ALL || (arrows == StoryboundClientConfig.EdgeArrows.TRACKED && tracked);
        if (!screen.onScreen() && !edge) {
            return null;
        }
        // Shown no darker than reads on the icons' dark outline and the font's dark shadow (the hue kept); the maps get it as chosen.
        int tint = MarkerContrast.readable(waypoint.color());
        Component label = inside ? Component.translatable("storybound.waypoint.in_area")
                : style.distance() ? distanceText(area && horizontal > waypoint.radius() ? horizontal - waypoint.radius() : distance) : null;
        return new Marker(waypoint, style, screen, anchor, tracked, area, distance, alpha, size, tint, label);
    }

    /**
     * Markers drawn at one place on screen (two quests' marks on one NPC, a village found by two searches): each one's
     * place in their stack, 0 for the one drawn on top (the tracked or the nearest), counted per group of icons closer
     * than {@link #STACK_RADIUS} to the group's top one. Their names stand one above another instead of over each
     * other, and the distance is drawn once.
     */
    private static int[] stacks(List<Marker> markers) {
        int[] stack = new int[markers.size()];
        List<Integer> leads = new ArrayList<>();
        Map<Integer, Integer> sizes = new HashMap<>();
        // From the top drawn down, so the tracked or nearest one is each group's first.
        for (int i = markers.size() - 1; i >= 0; i--) {
            Screen screen = markers.get(i).screen();
            if (!screen.onScreen()) {
                continue;
            }
            Integer lead = null;
            for (int candidate : leads) {
                Marker top = markers.get(candidate);
                Screen at = top.screen();
                // One place in the world too: a mark far behind another one is not stacked with it.
                boolean near = top.anchor().distanceTo(markers.get(i).anchor()) <= Math.max(STACK_WORLD, top.distance() * STACK_WORLD_SHARE);
                if (near && Math.abs(at.x() - screen.x()) <= STACK_RADIUS && Math.abs(at.y() - screen.y()) <= STACK_RADIUS) {
                    lead = candidate;
                    break;
                }
            }
            if (lead == null) {
                leads.add(i);
                sizes.put(i, 1);
            } else {
                stack[i] = sizes.merge(lead, 1, Integer::sum) - 1;
            }
        }
        return stack;
    }

    private static void draw(GuiGraphics graphics, Font font, Marker marker, int stack, long now) {
        WaypointStyle style = marker.style();
        float x = marker.screen().x();
        float y = marker.screen().y();
        if (!marker.screen().onScreen()) {
            drawArrow(graphics, marker, now);
            return;
        }
        AreaLook.Marker kind = marker.waypoint().marker();
        switch (kind) {
            case EVENT -> drawEvent(graphics, style.eventIcon(), x, y, marker.size(), marker.tint(), marker.alpha(), now);
            case QUESTION -> drawQuestion(graphics, style.questionIcon(), x, y, marker.size(), marker.tint(), marker.alpha(), now);
            // A scent is a trail's, never drawn as a marker; on anything else it shows the usual icon.
            case POINT, SCENT -> {
                if (marker.area()) {
                    drawArea(graphics, style.areaIcon(), x, y, marker.size(), marker.tint(), marker.alpha(), now);
                } else {
                    sprite(graphics, style.icon(), x, y, marker.size() * POINT_SCALE, 0, marker.tint(), marker.alpha());
                }
            }
        }

        float half = marker.size() / 2;
        // Every kind's name on one line, just above the top of the event's jump, so names of markers side by side line up.
        float nameY = y + half - marker.size() * (GLYPH_HEIGHT + EVENT_JUMP) - 10;
        float nameAlpha = nameAlpha(style, marker, graphics);
        if (nameAlpha > 0.03f) {
            Component name = marker.waypoint().name().toComponent();
            if (marker.area() && marker.waypoint().showRadius()) {
                name = name.copy().append(Component.translatable("storybound.waypoint.radius", Math.round(marker.waypoint().radius())));
            }
            // The name in the waypoint's colour, unless it keeps the default: then the look's text colour.
            int nameColor = marker.waypoint().color() == WaypointDefinition.DEFAULT_COLOR ? style.textColor() : marker.tint();
            text(graphics, font, name, x, nameY - stack * NAME_LINE, 1f, nameColor, marker.alpha() * nameAlpha);
        }
        if (marker.label() != null && stack == 0) {
            text(graphics, font, marker.label(), x, y + half + 2, DISTANCE_SCALE, style.distanceColor(), marker.alpha());
        }
    }

    /**
     * An event's marker: three "!", the middle one larger, standing on one line where a point's icon would end. They
     * hop as a cartoon does ({@link #hop}): the middle one leads and the side ones follow together a moment later,
     * every {@link #EVENT_PERIOD_MS}. Each mark squashes and turns around the middle of its bottom edge, so it stays on
     * the line; the side ones are drawn first, so the middle one's shadow lies over them. The sprite is one "!" in greys,
     * tinted by the waypoint's colour like the other icons. Timed by the clock, so it runs the same at any frame rate.
     */
    private static void drawEvent(GuiGraphics graphics, ResourceLocation sprite, float x, float y, float size, int rgb, float alpha, long now) {
        float base = y + size / 2;
        graphics.setColor(((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f, Mth.clamp(alpha, 0f, 1f));
        for (int side : new int[] {-1, 1, 0}) {
            float height = size * GLYPH_HEIGHT * (side == 0 ? 1f : EVENT_SIDE_SCALE);
            float[] hop = hop(Math.floorMod(now - (side == 0 ? 0 : EVENT_FOLLOW_MS), EVENT_PERIOD_MS));
            graphics.pose().pushPose();
            graphics.pose().translate(x + side * size * EVENT_SPACING, base - hop[0] * size * EVENT_JUMP, 0);
            if (side != 0) {
                graphics.pose().mulPose(Axis.ZP.rotationDegrees(hop[0] * EVENT_LEAN * side));
            }
            graphics.pose().scale(height / (16f * hop[1]), hop[1] * height / 16f, 1f);
            graphics.blitSprite(sprite, -8, -16, 16, 16);
            graphics.pose().popPose();
        }
        graphics.setColor(1f, 1f, 1f, 1f);
    }

    /**
     * The question's marker: one "?", standing where a point's icon would end. Every {@link #QUESTION_PERIOD_MS} it
     * shakes, two or three swings that die away softly, and makes a small hop ({@link #hop}, with half the event's squash
     * and stretch), then stands still. It turns around the middle of its bottom edge, its dot. The sprite is one "?" in
     * greys, tinted by the waypoint's colour like the other icons.
     */
    private static void drawQuestion(GuiGraphics graphics, ResourceLocation sprite, float x, float y, float size, int rgb, float alpha, long now) {
        long t = Math.floorMod(now, QUESTION_PERIOD_MS);
        float[] hop = hop(t);
        float squash = 1f + (hop[1] - 1f) * QUESTION_SQUASH;
        float fade = 1f - Math.min(1f, t / (float) QUESTION_SHAKE_MS);
        float shake = QUESTION_SHAKE * Mth.sin(Mth.TWO_PI * t / QUESTION_SHAKE_SWING_MS) * fade * fade;
        float height = size * GLYPH_HEIGHT;
        graphics.setColor(((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f, Mth.clamp(alpha, 0f, 1f));
        graphics.pose().pushPose();
        graphics.pose().translate(x, y + size / 2 - hop[0] * size * QUESTION_JUMP, 0);
        graphics.pose().mulPose(Axis.ZP.rotationDegrees(shake));
        graphics.pose().scale(height / (16f * squash), squash * height / 16f, 1f);
        graphics.blitSprite(sprite, -8, -16, 16, 16);
        graphics.pose().popPose();
        graphics.setColor(1f, 1f, 1f, 1f);
    }

    /**
     * A search area's marker: the magnifier as a cartoon detective holds it. Every {@link #AREA_PERIOD_MS} it searches,
     * sweeping left, right and back to the middle; then it notices something: it draws back a moment and comes closer,
     * growing and springing past its size and back, a closer look; then it rests. Never turned, and kept on window
     * pixels, so its pixels stay as drawn.
     */
    private static void drawArea(GuiGraphics graphics, ResourceLocation sprite, float x, float y, float size, int rgb, float alpha, long now) {
        long t = Math.floorMod(now, AREA_PERIOD_MS);
        float dx = 0f;
        float scale = 1f;
        if (t < AREA_SWEEP_MS) {
            // Left first: an eased run once round a sine, so it starts and ends at rest.
            dx = -size * AREA_SWEEP * Mth.sin(Mth.TWO_PI * smooth(t / (float) AREA_SWEEP_MS));
        } else {
            float look = t - AREA_SWEEP_MS;
            if (look < AREA_DRAW_BACK_MS) {
                scale = 1f - AREA_DRAW_BACK * smooth(look / AREA_DRAW_BACK_MS);
            } else {
                float closer = look - AREA_DRAW_BACK_MS;
                // The draw-back lets go over the first moments while the spring carries it past its size and back.
                float letGo = 1f - AREA_DRAW_BACK * (1f - smooth(closer / 50f));
                scale = letGo * (1f + AREA_ZOOM * (float) Math.exp(-closer / 200f) * Mth.sin(Mth.PI * closer / 260f));
            }
        }
        sprite(graphics, sprite, snap(x + dx), snap(y), size * scale, 0, rgb, alpha);
    }

    /** A HUD coordinate on the nearest window pixel, so a moving sprite keeps its pixels instead of shimmering. */
    private static float snap(float v) {
        int pixel = HudScale.pixel();
        return Math.round(v * pixel) / (float) pixel;
    }

    /**
     * One mark's hop at {@code t} ms into the cycle: its rise (0 to 1 of the jump) and its height scale (the width
     * scale is the inverse, so it keeps its bulk). It crouches, then flies a parabola, as a thrown thing does,
     * stretched by its speed, more going up than coming down, and lands squashed, springing past its shape and back.
     * The squash before the jump lets go over the first moments in the air and the stretch grows in, so nothing jumps
     * from frame to frame but the landing, which is the hit.
     */
    private static float[] hop(long t) {
        if (t < EVENT_CROUCH_MS) {
            return new float[] {0f, 1f - 0.18f * smooth(t / (float) EVENT_CROUCH_MS)};
        }
        float air = t - EVENT_CROUCH_MS;
        if (air < EVENT_AIR_MS) {
            float u = air / EVENT_AIR_MS;
            float speed = 1f - 2f * u;
            float letGo = 1f - 0.18f * (1f - smooth(air / 40f));
            float stretch = 1f + (speed > 0 ? 0.22f : 0.12f) * Math.abs(speed) * Math.min(1f, air / 40f);
            return new float[] {4f * u * (1f - u), letGo * stretch};
        }
        float landed = air - EVENT_AIR_MS;
        return new float[] {0f, 1f - 0.22f * (float) Math.exp(-landed / 90f) * Mth.cos(Mth.TWO_PI * landed / 220f)};
    }

    private static float smooth(float t) {
        t = Mth.clamp(t, 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    /** An arrow at the screen edge, pointing where the off-screen waypoint is, with its distance beside it. */
    private static void drawArrow(GuiGraphics graphics, Marker marker, long now) {
        float angle = (float) Math.atan2(marker.screen().dirX(), marker.screen().dirY());
        float pulse = 0.8f + 0.2f * Mth.sin((now % 1600) / 1600f * Mth.TWO_PI);
        // The shadow as on the other icons (2 of a sprite's 32 pixels right and down), kept there however the arrow turns.
        float drop = marker.size() / 16f;
        sprite(graphics, marker.style().arrow(), marker.screen().x() + drop, marker.screen().y() + drop, marker.size(), angle, SHADOW_RGB, marker.alpha() * pulse);
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
            case TRACKED -> marker.tracked() ? 1f : nearCrosshair(marker.screen(), graphics);
            case NEAR_CROSSHAIR -> nearCrosshair(marker.screen(), graphics);
        };
    }

    /** 1 at the crosshair, fading to 0 at {@link #NAME_RADIUS} from it. */
    private static float nearCrosshair(Screen screen, GuiGraphics graphics) {
        float dx = screen.x() - HudScale.width() / 2f;
        float dy = screen.y() - HudScale.height() / 2f;
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

    /**
     * Where the marker is this frame. Above an entity the client has, it is drawn at the entity's interpolated
     * position, as smooth as the entity itself; otherwise it glides between the server's updates (once a second,
     * to half a block).
     */
    private static Vec3 position(Minecraft minecraft, ClientWaypoint waypoint, float frameSeconds, float partialTick, long now) {
        String key = waypoint.key();
        Vec3 previous = SMOOTHED.get(key);
        Entity entity = waypoint.entity() == ClientWaypoint.NO_ENTITY || minecraft.level == null ? null : minecraft.level.getEntity(waypoint.entity());
        Vec3 next;
        if (entity != null && !entity.isRemoved()) {
            next = entity.getPosition(partialTick).add(0, entity.getBbHeight(), 0);
            Integer followed = FOLLOWED.put(key, waypoint.entity());
            if (previous != null && (followed == null || followed != waypoint.entity())) {
                GLIDES.put(key, new Glide(previous, now));
            }
            Glide glide = GLIDES.get(key);
            if (glide != null) {
                float t = (now - glide.start()) / (float) SWITCH_MILLIS;
                if (t >= 1) {
                    GLIDES.remove(key);
                } else {
                    next = glide.from().lerp(next, t * t * (3 - 2 * t));
                }
            }
        } else {
            FOLLOWED.remove(key);
            GLIDES.remove(key);
            Vec3 target = waypoint.pos();
            next = previous == null || previous.distanceTo(target) > SNAP_DISTANCE
                    ? target : previous.lerp(target, 1 - Math.exp(-frameSeconds * 6));
        }
        SMOOTHED.put(key, next);
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

    private record Glide(Vec3 from, long start) {}

    private record Marker(ClientWaypoint waypoint, WaypointStyle style, Screen screen, Vec3 anchor, boolean tracked, boolean area, double distance,
                          float alpha, float size, int tint, @Nullable Component label) {}
}
